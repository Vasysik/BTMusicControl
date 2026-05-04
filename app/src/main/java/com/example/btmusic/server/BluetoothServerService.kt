package com.example.btmusic.server

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.view.KeyEvent
import com.example.btmusic.R
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
    @Volatile var connectedClientName: String = ""
        private set

    // Текущий активный MediaController (для seek)
    var activeMediaController: MediaController? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(Constants.NOTIF_ID_SERVER, buildNotification("Ожидание подключения..."))
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
        scope.launch {
            try {
                serverSocket = btAdapter.listenUsingRfcommWithServiceRecord(
                    Constants.BT_SERVER_NAME, Constants.BT_UUID
                )
                broadcastState(false, "")
                val socket = serverSocket!!.accept()
                handleClient(socket)
            } catch (_: SecurityException) { broadcastState(false, "") }
              catch (e: IOException) {
                if (isActive) { broadcastState(false, ""); delay(2_000); beginAccepting() }
            }
        }
    }

    private suspend fun handleClient(socket: BluetoothSocket) {
        clientSocket = socket
        writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.outputStream)), true)
        val name = try { socket.remoteDevice.name ?: socket.remoteDevice.address }
                   catch (_: SecurityException) { socket.remoteDevice.address }
        isClientConnected   = true
        connectedClientName = name
        updateNotification("Подключён: $name")
        broadcastState(true, name)
        runCatching { serverSocket?.close() }
        serverSocket = null
        readLoop(socket)
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) dispatchCommand(reader.readLine() ?: break)
        } catch (_: IOException) {
        } finally {
            closeClient()
            isClientConnected   = false
            connectedClientName = ""
            broadcastState(false, "")
            updateNotification("Ожидание подключения...")
            delay(500)
            beginAccepting()
        }
    }

    private fun dispatchCommand(cmd: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val ctrl = activeMediaController  // Берём контроллер из MusicNotificationListener
        
        when {
            cmd == Constants.CMD_PLAY -> {
                if (ctrl != null) {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                } else {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
            }
            
            cmd == Constants.CMD_NEXT -> {
                ctrl?.transportControls?.skipToNext() ?: run {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_NEXT))
                }
            }
            
            cmd == Constants.CMD_PREV -> {
                ctrl?.transportControls?.skipToPrevious() ?: run {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                }
            }
            
            cmd == Constants.CMD_VOL_UP   -> am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            cmd == Constants.CMD_VOL_DOWN -> am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            
            cmd.startsWith(Constants.CMD_SEEK_PREFIX) -> {
                val posMs = cmd.removePrefix(Constants.CMD_SEEK_PREFIX).toLongOrNull() ?: return
                ctrl?.transportControls?.seekTo(posMs)
            }
        }
    }

    fun sendTrackInfo(info: String)         { send("${Constants.CMD_TRACK_PREFIX}$info") }
    fun sendAlbumCover(base64: String)      { send("${Constants.CMD_ART_PREFIX}$base64") }
    fun sendPlaybackState(playing: Boolean) { send("${Constants.CMD_STATE_PREFIX}${if (playing) 1 else 0}") }
    fun sendPosition(posMs: Long, durMs: Long) { send("${Constants.CMD_POS_PREFIX}$posMs:$durMs") }

    private fun send(line: String) {
        scope.launch { runCatching { writer?.println(line) } }
    }

    private fun broadcastState(connected: Boolean, clientName: String) {
        sendBroadcast(Intent(Constants.ACTION_CONNECTION_CHANGED).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_CONNECTED, connected)
            putExtra(Constants.EXTRA_CLIENT_NAME, clientName)
        })
    }

    private fun closeClient() {
        runCatching { writer?.close() };       writer       = null
        runCatching { clientSocket?.close() }; clientSocket = null
    }

    private fun closeAll() {
        closeClient()
        runCatching { serverSocket?.close() }; serverSocket = null
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ServerActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, piFlags
        )
        return Notification.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("BT Music — Сервер")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
                NotificationChannel(Constants.NOTIF_CHANNEL_ID, Constants.NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
