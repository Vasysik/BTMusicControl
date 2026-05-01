package com.example.btmusic.server

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.view.KeyEvent
import com.example.btmusic.common.Constants
import kotlinx.coroutines.*
import java.io.*

class BluetoothServerService : Service() {

    companion object {
        @Volatile var instance: BluetoothServerService? = null
    }

    private val btAdapter: BluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket?       = null
    private var writer: PrintWriter?                 = null

    @Volatile var isClientConnected: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(Constants.NOTIF_ID_SERVER, buildNotification("Ожидание клиента..."))
        beginAccepting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        closeAll()
    }

    private fun beginAccepting() {
        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                serverSocket = btAdapter.listenUsingRfcommWithServiceRecord(
                    Constants.BT_SERVER_NAME, Constants.BT_UUID
                )
                broadcastState(false)
                val socket = serverSocket!!.accept()
                handleClient(socket)
            } catch (e: IOException) {
                if (isActive) {
                    broadcastState(false)
                    delay(2_000)
                    beginAccepting()
                }
            }
        }
    }

    private suspend fun handleClient(socket: BluetoothSocket) {
        clientSocket = socket
        writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.outputStream)), true)
        val name = runCatching { socket.remoteDevice.name }.getOrDefault(socket.remoteDevice.address)
        isClientConnected = true
        updateNotification("Подключён: $name")
        broadcastState(true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        readLoop(socket)
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                dispatchCommand(line.trim())
            }
        } catch (_: IOException) {
        } finally {
            closeClient()
            isClientConnected = false
            broadcastState(false)
            updateNotification("Ожидание клиента...")
            delay(500)
            beginAccepting()
        }
    }

    private fun dispatchCommand(cmd: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (cmd) {
            Constants.CMD_PLAY -> {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            Constants.CMD_NEXT -> {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            Constants.CMD_PREV -> {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
            Constants.CMD_VOL_UP ->
                am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            Constants.CMD_VOL_DOWN ->
                am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun sendTrackInfo(info: String) {
        scope.launch {
            runCatching { writer?.println("${Constants.CMD_TRACK_PREFIX}$info") }
        }
    }

    private fun broadcastState(connected: Boolean) {
        sendBroadcast(Intent(Constants.ACTION_CONNECTION_CHANGED).apply {
            putExtra(Constants.EXTRA_CONNECTED, connected)
        })
    }

    private fun closeClient() {
        runCatching { writer?.close() };  writer = null
        runCatching { clientSocket?.close() };  clientSocket = null
    }

    private fun closeAll() {
        closeClient()
        runCatching { serverSocket?.close() };  serverSocket = null
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ServerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("🎵 BT Music Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIF_ID_SERVER, buildNotification(text))

    private fun ensureChannel() {
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
    }
}
