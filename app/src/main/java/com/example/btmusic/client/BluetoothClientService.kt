package com.example.btmusic.client

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.os.*
import com.example.btmusic.common.Constants
import kotlinx.coroutines.*
import java.io.*

class BluetoothClientService : Service() {

    companion object {
        @Volatile var instance: BluetoothClientService? = null
    }

    private val btAdapter: BluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var socket: BluetoothSocket? = null
    private var writer: PrintWriter?     = null
    private var targetAddress: String?   = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(Constants.NOTIF_ID_CLIENT, buildNotification("Инициализация..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
        if (address != null && address != targetAddress) {
            targetAddress = address
            connectTo(address)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        closeSocket()
    }

    private fun connectTo(address: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            broadcastState(false)
            updateNotification("Подключение...")

            while (isActive) {
                try {
                    val device = btAdapter.getRemoteDevice(address)

                    val btSocket = try {
                        // Стандартный способ
                        device.createRfcommSocketToServiceRecord(Constants.BT_UUID)
                    } catch (e: Exception) {
                        // Fallback для нестандартных Bluetooth-стеков (японские телефоны и т.п.)
                        device.javaClass
                            .getMethod("createRfcommSocket", Int::class.java)
                            .invoke(device, 1) as BluetoothSocket
                    }

                    // Критично! Без этого connect() нестабилен
                    btAdapter.cancelDiscovery()

                    btSocket.connect()
                    socket = btSocket
                    writer = PrintWriter(BufferedWriter(OutputStreamWriter(btSocket.outputStream)), true)

                    val name = runCatching { device.name }.getOrDefault(address)
                    updateNotification("Подключён: $name")
                    broadcastState(true)

                    readLoop(btSocket)

                } catch (e: IOException) {
                    closeSocket()
                    broadcastState(false)
                    updateNotification("Переподключение...")
                    delay(3_000)
                }
            }
        }
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith(Constants.CMD_TRACK_PREFIX)) {
                    val info = line.removePrefix(Constants.CMD_TRACK_PREFIX)
                    broadcastTrack(info)
                }
            }
        } catch (_: IOException) {
            // сервер отключился
        } finally {
            closeSocket()
            broadcastState(false)
        }
    }

    /** Публичный метод — вызывают Activity и KeyMapperReceiver */
    fun sendCommand(cmd: String) {
        scope.launch {
            runCatching { writer?.println(cmd) }
        }
    }

    private fun broadcastState(connected: Boolean) {
        sendBroadcast(Intent(Constants.ACTION_CONNECTION_CHANGED).apply {
            putExtra(Constants.EXTRA_CONNECTED, connected)
        })
    }

    private fun broadcastTrack(info: String) {
        sendBroadcast(Intent(Constants.ACTION_TRACK_UPDATED).apply {
            putExtra(Constants.EXTRA_TRACK_INFO, info)
        })
    }

    private fun closeSocket() {
        runCatching { writer?.close() };  writer = null
        runCatching { socket?.close() };  socket = null
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.NOTIF_CHANNEL_ID,
                    Constants.NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return Notification.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("BT Music Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIF_ID_CLIENT, buildNotification(text))
}
