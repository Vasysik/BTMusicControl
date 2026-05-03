package com.example.btmusic.client

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.btmusic.R
import com.example.btmusic.common.Constants
import com.example.btmusic.common.Prefs
import java.util.Locale

class ClientActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTrack: TextView
    private lateinit var tvSavedDevice: TextView
    private lateinit var tvTime: TextView
    private lateinit var ivAlbumArt: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var btnConnect: Button
    private lateinit var btnForget: Button
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnVolUp: ImageButton
    private lateinit var btnVolDown: ImageButton

    private var isDraggingSeekBar = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_CONNECTION_CHANGED -> {
                    val ok = intent.getBooleanExtra(Constants.EXTRA_CONNECTED, false)
                    tvStatus.text = if (ok) "Подключён" else "Нет соединения"
                    setControlsEnabled(ok)
                }
                Constants.ACTION_TRACK_UPDATED ->
                    tvTrack.text = intent.getStringExtra(Constants.EXTRA_TRACK_INFO) ?: ""
                Constants.ACTION_ART_UPDATED -> {
                    val b64 = intent.getStringExtra(Constants.EXTRA_ART_BASE64) ?: return
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                    ivAlbumArt.setImageBitmap(bmp)
                    ivAlbumArt.setPadding(0, 0, 0, 0)
                    ivAlbumArt.clearColorFilter()
                }
                Constants.ACTION_STATE_UPDATED -> {
                    val isPlaying = intent.getBooleanExtra(Constants.EXTRA_IS_PLAYING, false)
                    // Меняем иконку в зависимости от статуса
                    btnPlay.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_pause
                    )
                }
                Constants.ACTION_POSITION_UPDATED -> {
                    if (isDraggingSeekBar) return // Не дергаем ползунок, если юзер его тащит
                    
                    val posMs = intent.getLongExtra(Constants.EXTRA_POSITION_MS, 0)
                    val durMs = intent.getLongExtra(Constants.EXTRA_DURATION_MS, 0)
                    
                    seekBar.max = durMs.toInt()
                    seekBar.progress = posMs.toInt()
                    tvTime.text = "${formatTime(posMs)} / ${formatTime(durMs)}"
                }
            }
        }
    }

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) showDevicePicker()
        else Toast.makeText(this, "Нужны разрешения Bluetooth", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        tvStatus      = findViewById(R.id.tv_status)
        tvTrack       = findViewById(R.id.tv_track)
        tvSavedDevice = findViewById(R.id.tv_saved_device)
        tvTime        = findViewById(R.id.tv_time)
        ivAlbumArt    = findViewById(R.id.iv_album_art)
        seekBar       = findViewById(R.id.seek_bar)
        btnConnect    = findViewById(R.id.btn_connect)
        btnForget     = findViewById(R.id.btn_forget)
        btnPrev       = findViewById(R.id.btn_prev)
        btnPlay       = findViewById(R.id.btn_play)
        btnNext       = findViewById(R.id.btn_next)
        btnVolUp      = findViewById(R.id.btn_vol_up)
        btnVolDown    = findViewById(R.id.btn_vol_down)

        ivAlbumArt.setColorFilter(
            ContextCompat.getColor(this, android.R.color.darker_gray)
        )

        btnConnect.setOnClickListener { requestBtPermsAndPick() }
        btnForget.setOnClickListener {
            Prefs(this).forget()
            stopService(Intent(this, BluetoothClientService::class.java))
            tvStatus.text = "Нет соединения"
            tvTrack.text  = ""
            tvTime.text   = "00:00 / 00:00"
            seekBar.progress = 0
            btnPlay.setImageResource(R.drawable.ic_play_pause)
            ivAlbumArt.setImageResource(android.R.drawable.ic_media_play)
            setControlsEnabled(false)
            refreshSavedDeviceUI()
        }

        btnPrev.setOnClickListener    { BluetoothClientService.instance?.sendCommand(Constants.CMD_PREV) }
        btnPlay.setOnClickListener    { BluetoothClientService.instance?.sendCommand(Constants.CMD_PLAY) }
        btnNext.setOnClickListener    { BluetoothClientService.instance?.sendCommand(Constants.CMD_NEXT) }
        btnVolUp.setOnClickListener   { BluetoothClientService.instance?.sendCommand(Constants.CMD_VOL_UP) }
        btnVolDown.setOnClickListener { BluetoothClientService.instance?.sendCommand(Constants.CMD_VOL_DOWN) }

        // Логика перемещения ползунка пользователем
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvTime.text = "${formatTime(progress.toLong())} / ${formatTime(bar?.max?.toLong() ?: 0)}"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                isDraggingSeekBar = true
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                isDraggingSeekBar = false
                val pos = bar?.progress ?: 0
                // Отправляем команду перемотки на сервер
                BluetoothClientService.instance?.sendCommand("${Constants.CMD_SEEK_PREFIX}$pos")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_CONNECTION_CHANGED)
            addAction(Constants.ACTION_TRACK_UPDATED)
            addAction(Constants.ACTION_ART_UPDATED)
            addAction(Constants.ACTION_STATE_UPDATED)
            addAction(Constants.ACTION_POSITION_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        val connected = BluetoothClientService.instance?.isConnected ?: false
        tvStatus.text = if (connected) "Подключён" else "Нет соединения"
        setControlsEnabled(connected)
        refreshSavedDeviceUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun refreshSavedDeviceUI() {
        val prefs = Prefs(this)
        val name  = prefs.savedDeviceName
        val addr  = prefs.savedDeviceAddress
        if (addr != null) {
            tvSavedDevice.text   = "Привязано: ${name ?: addr}"
            btnForget.visibility = View.VISIBLE
            btnConnect.text      = "Сменить устройство"
        } else {
            tvSavedDevice.text   = "Устройство не привязано"
            btnForget.visibility = View.GONE
            btnConnect.text      = "Выбрать устройство"
        }
    }

    private fun requestBtPermsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) showDevicePicker()
            else btPermLauncher.launch(needed.toTypedArray())
        } else showDevicePicker()
    }

    private fun showDevicePicker() {
        val adapter = try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } catch (_: Exception) { null }
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        val paired = try { adapter.bondedDevices.toList() } catch (_: SecurityException) { emptyList() }
        if (paired.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых устройств", Toast.LENGTH_LONG).show()
            return
        }
        val labels = paired.map {
            try { "${it.name} (${it.address})" } catch (_: SecurityException) { it.address }
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Выберите сервер")
            .setItems(labels) { _, idx -> connectTo(paired[idx]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        val name = try { device.name } catch (_: SecurityException) { device.address }
        Prefs(this).apply {
            savedDeviceAddress = device.address
            savedDeviceName    = name ?: device.address
        }
        refreshSavedDeviceUI()
        tvStatus.text = "Подключение..."
        val intent = Intent(this, BluetoothClientService::class.java).apply {
            putExtra(Constants.EXTRA_DEVICE_ADDRESS, device.address)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPrev.isEnabled    = enabled
        btnPlay.isEnabled    = enabled
        btnNext.isEnabled    = enabled
        btnVolUp.isEnabled   = enabled
        btnVolDown.isEnabled = enabled
        seekBar.isEnabled    = enabled
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSecs = ms / 1000
        val m = totalSecs / 60
        val s = totalSecs % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
