package com.example.btmusic.client

import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import com.example.btmusic.common.Constants
import com.example.btmusic.common.Prefs
import kotlinx.coroutines.*
import java.io.*

class BluetoothClientService : Service() {

    companion object {
        @Volatile var instance: BluetoothClientService? = null

        private const val ACTION_NOTIF_PREV    = "com.btmusic.notif.PREV"
        private const val ACTION_NOTIF_PLAY    = "com.btmusic.notif.PLAY"
        private const val ACTION_NOTIF_NEXT    = "com.btmusic.notif.NEXT"
        private const val ACTION_NOTIF_VOL_UP  = "com.btmusic.notif.VOL_UP"
        private const val ACTION_NOTIF_VOL_DN  = "com.btmusic.notif.VOL_DN"
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
            addAction(ACTION_NOTIF_PREV)
            addAction(ACTION_NOTIF_PLAY)
            addAction(ACTION_NOTIF_NEXT)
            addAction(ACTION_NOTIF_VOL_UP)
            addAction(ACTION_NOTIF_VOL_DN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }

        startForeground(Constants.NOTIF_ID_CLIENT, buildNotification("Инициализация..."))

        val savedAddress = Prefs(this).savedDeviceAddress
        if (savedAddress != null) {
            targetAddress = savedAddress
            connectTo(savedAddress)
        } else {
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
            START_STICKY
        } else {
            stopSelf()
            START_NOT_STICKY
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
                    } catch (e: Exception) {
                        device.javaClass
                            .getMethod("createRfcommSocket", Int::class.java)
                            .invoke(device, 1) as BluetoothSocket
                    }

                    btAdapter.cancelDiscovery()
                    btSocket.connect()

                    socket = btSocket
                    writer = PrintWriter(BufferedWriter(OutputStreamWriter(btSocket.outputStream)), true)

                    retryDelay = 3_000L
                    isConnected = true
                    val name = runCatching { device.name }.getOrDefault(address)
                    updateNotification(if (currentTrack.isNotEmpty()) currentTrack else "Подключён: $name")
                    broadcastState(true)

                    readLoop(btSocket)

                } catch (e: IOException) {
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
                if (line.startsWith(Constants.CMD_TRACK_PREFIX)) {
                    val info = line.removePrefix(Constants.CMD_TRACK_PREFIX)
                    currentTrack = info
                    updateNotification(info)
                    broadcastTrack(info)
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

    // ─── Уведомление ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        ensureChannel()

        // FIX: переименовали в piFlags — иначе конфликт с Intent.flags внутри apply {}
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val prevIntent  = PendingIntent.getBroadcast(this, 10, Intent(ACTION_NOTIF_PREV),   piFlags)
        val playIntent  = PendingIntent.getBroadcast(this, 11, Intent(ACTION_NOTIF_PLAY),   piFlags)
        val nextIntent  = PendingIntent.getBroadcast(this, 12, Intent(ACTION_NOTIF_NEXT),   piFlags)
        val volUpIntent = PendingIntent.getBroadcast(this, 13, Intent(ACTION_NOTIF_VOL_UP), piFlags)
        val volDnIntent = PendingIntent.getBroadcast(this, 14, Intent(ACTION_NOTIF_VOL_DN), piFlags)

        // Тап → ClientActivity
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ClientActivity::class.java).apply {
                // здесь this — Intent, поэтому this.flags без конфликта
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            piFlags
        )

        return Notification.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setContentTitle("🎮 BT Music Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            // Порядок: 🔉 ⏮ ⏯ ⏭ 🔊
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,
                "🔉", volDnIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,
                "⏮", prevIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_play,
                "⏯", playIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,
                "⏭", nextIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,
                "🔊", volUpIntent).build())
            // В компактном виде показываем только ⏮ ⏯ ⏭ (индексы 1, 2, 3)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(1, 2, 3))
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIF_ID_CLIENT, buildNotification(text))

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
