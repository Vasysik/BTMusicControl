package com.example.btmusic.client

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.os.*
import com.example.btmusic.common.Constants
import com.example.btmusic.common.Prefs
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

        // Автоподключение к сохранённому устройству
        val savedAddress = Prefs(this).savedDeviceAddress
        if (savedAddress != null) {
            targetAddress = savedAddress
            connectTo(savedAddress)
        } else {
            // Нет привязанного устройства — останавливаемся немедленно, батарею не тратим
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
            ?: Prefs(this).savedDeviceAddress

        return if (address != null) {
            if (address != targetAddress) {
                targetAddress = address
                connectTo(address)
            }
            START_STICKY   // перезапуск системой — продолжаем работать
        } else {
            stopSelf()
            START_NOT_STICKY  // нет устройства — не перезапускаться
        }
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

            var retryDelay = 3_000L  // экспоненциальная задержка: 3s, 6s, 12s ... max 60s

            while (isActive) {
                try {
                    val device = btAdapter.getRemoteDevice(address)

                    val btSocket = try {
                        device.createRfcommSocketToServiceRecord(Constants.BT_UUID)
                    } catch (e: Exception) {
                        // Fallback для нестандартных Bluetooth-стеков
                        device.javaClass
                            .getMethod("createRfcommSocket", Int::class.java)
                            .invoke(device, 1) as BluetoothSocket
                    }

                    // Без этого connect() нестабилен при активном сканировании
                    btAdapter.cancelDiscovery()

                    btSocket.connect()
                    socket = btSocket
                    writer = PrintWriter(BufferedWriter(OutputStreamWriter(btSocket.outputStream)), true)

                    retryDelay = 3_000L  // успешно — сбрасываем задержку
                    val name = runCatching { device.name }.getOrDefault(address)
                    updateNotification("Подключён: $name")
                    broadcastState(true)

                    readLoop(btSocket)  // блокируем до дисконнекта

                } catch (e: IOException) {
                    closeSocket()
                    broadcastState(false)
                    updateNotification("Переподключение через ${retryDelay / 1000}с...")
                    delay(retryDelay)
                    retryDelay = minOf(retryDelay * 2, 60_000L)  // не чаще раза в минуту
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
