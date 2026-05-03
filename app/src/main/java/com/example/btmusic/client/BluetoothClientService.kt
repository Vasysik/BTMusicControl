package com.example.btmusic.client

import android.app.*
import android.bluetooth.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.*
import android.util.Base64
import com.example.btmusic.R
import com.example.btmusic.common.Constants
import com.example.btmusic.common.Prefs
import kotlinx.coroutines.*
import java.io.*

class BluetoothClientService : Service() {

    companion object {
        @Volatile var instance: BluetoothClientService? = null

        private const val ACTION_NOTIF_PREV   = "com.btmusic.notif.PREV"
        private const val ACTION_NOTIF_PLAY   = "com.btmusic.notif.PLAY"
        private const val ACTION_NOTIF_NEXT   = "com.btmusic.notif.NEXT"
        private const val ACTION_NOTIF_VOL_UP = "com.btmusic.notif.VOL_UP"
        private const val ACTION_NOTIF_VOL_DN = "com.btmusic.notif.VOL_DN"
    }

    private val btAdapter: BluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var socket: BluetoothSocket? = null
    private var writer: PrintWriter?     = null
    private var targetAddress: String?   = null

    @Volatile var isConnected: Boolean = false
        private set

    private var currentTrack: String = ""
    private var currentArtBitmap: Bitmap? = null   // кэш для уведомления

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_NOTIF_PREV   -> sendCommand(Constants.CMD_PREV)
                ACTION_NOTIF_PLAY   -> sendCommand(Constants.CMD_PLAY)
                ACTION_NOTIF_NEXT   -> sendCommand(Constants.CMD_NEXT)
                ACTION_NOTIF_VOL_UP -> sendCommand(Constants.CMD_VOL_UP)
                ACTION_NOTIF_VOL_DN -> sendCommand(Constants.CMD_VOL_DOWN)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val filter = IntentFilter().apply {
            addAction(ACTION_NOTIF_PREV); addAction(ACTION_NOTIF_PLAY)
            addAction(ACTION_NOTIF_NEXT); addAction(ACTION_NOTIF_VOL_UP)
            addAction(ACTION_NOTIF_VOL_DN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }

        startForeground(Constants.NOTIF_ID_CLIENT, buildNotification("Инициализация..."))

        val saved = Prefs(this).savedDeviceAddress
        if (saved != null) { targetAddress = saved; connectTo(saved) }
        else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
            ?: Prefs(this).savedDeviceAddress

        return if (address != null) {
            if (address != targetAddress) { targetAddress = address; connectTo(address) }
            START_STICKY
        } else {
            stopSelf(); START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(notifReceiver)
        scope.cancel()
        closeSocket()
    }

    private fun connectTo(address: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            isConnected = false
            broadcastState(false)
            updateNotification("Подключение...")
            var retryDelay = 3_000L

            while (isActive) {
                try {
                    val device = btAdapter.getRemoteDevice(address)

                    val btSocket = try {
                        device.createRfcommSocketToServiceRecord(Constants.BT_UUID)
                    } catch (_: SecurityException) {
                        delay(retryDelay); continue    // нет разрешения — ждём
                    } catch (e: Exception) {
                        @Suppress("UNCHECKED_CAST")
                        device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                            .invoke(device, 1) as BluetoothSocket
                    }

                    try { btAdapter.cancelDiscovery() } catch (_: SecurityException) {}

                    btSocket.connect()
                    socket = btSocket
                    writer = PrintWriter(BufferedWriter(OutputStreamWriter(btSocket.outputStream)), true)

                    retryDelay = 3_000L
                    isConnected = true
                    val name = try { device.name } catch (_: SecurityException) { address }
                    val label = if (currentTrack.isNotEmpty()) currentTrack else "Подключён: $name"
                    updateNotification(label)
                    broadcastState(true)
                    readLoop(btSocket)

                } catch (_: SecurityException) {
                    // BLUETOOTH_CONNECT не выдан — нет смысла ретраиться быстро
                    delay(30_000L)
                } catch (_: IOException) {
                    closeSocket()
                    isConnected = false
                    broadcastState(false)
                    updateNotification("Переподключение через ${retryDelay / 1000}с...")
                    delay(retryDelay)
                    retryDelay = minOf(retryDelay * 2, 60_000L)
                }
            }
        }
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith(Constants.CMD_TRACK_PREFIX) -> {
                        val info = line.removePrefix(Constants.CMD_TRACK_PREFIX)
                        currentTrack = info
                        updateNotification(info)
                        broadcastTrack(info)
                    }
                    line.startsWith(Constants.CMD_ART_PREFIX) -> {
                        val b64 = line.removePrefix(Constants.CMD_ART_PREFIX)
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            currentArtBitmap = bmp
                            updateNotification(currentTrack.ifEmpty { "BT Music Remote" })
                            broadcastArt(b64)
                        }
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            closeSocket()
            isConnected = false
            broadcastState(false)
        }
    }

    fun sendCommand(cmd: String) {
        scope.launch { runCatching { writer?.println(cmd) } }
    }

    // FIX: setPackage → гарантирует доставку в RECEIVER_NOT_EXPORTED
    private fun broadcastState(connected: Boolean) {
        sendBroadcast(Intent(Constants.ACTION_CONNECTION_CHANGED).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_CONNECTED, connected)
        })
    }

    private fun broadcastTrack(info: String) {
        sendBroadcast(Intent(Constants.ACTION_TRACK_UPDATED).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_TRACK_INFO, info)
        })
    }

    private fun broadcastArt(b64: String) {
        sendBroadcast(Intent(Constants.ACTION_ART_UPDATED).apply {
            setPackage(packageName)
            putExtra(Constants.EXTRA_ART_BASE64, b64)
        })
    }

    private fun closeSocket() {
        runCatching { writer?.close() };   writer = null
        runCatching { socket?.close() };   socket = null
    }

    // ─── Уведомление с кнопками управления ───────────────────────────────────

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val prevIntent  = PendingIntent.getBroadcast(this, 10, Intent(ACTION_NOTIF_PREV),   piFlags)
        val playIntent  = PendingIntent.getBroadcast(this, 11, Intent(ACTION_NOTIF_PLAY),   piFlags)
        val nextIntent  = PendingIntent.getBroadcast(this, 12, Intent(ACTION_NOTIF_NEXT),   piFlags)
        val volUpIntent = PendingIntent.getBroadcast(this, 13, Intent(ACTION_NOTIF_VOL_UP), piFlags)
        val volDnIntent = PendingIntent.getBroadcast(this, 14, Intent(ACTION_NOTIF_VOL_DN), piFlags)

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ClientActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, piFlags
        )

        // FIX: новый API Icon вместо устаревшего конструктора с Int
        fun action(iconRes: Int, label: String, pi: PendingIntent) =
            Notification.Action.Builder(
                Icon.createWithResource(this, iconRes), label, pi
            ).build()

        val builder = Notification.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("BT Music Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .addAction(action(android.R.drawable.ic_lock_silent_mode,   "Vol-", volDnIntent))
            .addAction(action(android.R.drawable.ic_media_previous, "Пред", prevIntent))
            .addAction(action(android.R.drawable.ic_media_play,    "Play", playIntent))
            .addAction(action(android.R.drawable.ic_media_next,     "След", nextIntent))
            .addAction(action(android.R.drawable.ic_media_ff,     "Vol+", volUpIntent))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(1, 2, 3))

        // Обложка в уведомлении если есть
        currentArtBitmap?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIF_ID_CLIENT, buildNotification(text))

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
