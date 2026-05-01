package com.example.btmusic.client

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.btmusic.R
import com.example.btmusic.common.Constants
import com.example.btmusic.common.Prefs

class ClientActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTrack: TextView
    private lateinit var tvSavedDevice: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnForget: Button
    private lateinit var btnPrev: Button
    private lateinit var btnPlay: Button
    private lateinit var btnNext: Button

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_CONNECTION_CHANGED -> {
                    val ok = intent.getBooleanExtra(Constants.EXTRA_CONNECTED, false)
                    tvStatus.text = if (ok) "✅ Подключён" else "❌ Нет соединения"
                    setControlsEnabled(ok)
                }
                Constants.ACTION_TRACK_UPDATED ->
                    tvTrack.text = intent.getStringExtra(Constants.EXTRA_TRACK_INFO) ?: ""
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

        tvStatus     = findViewById(R.id.tv_status)
        tvTrack      = findViewById(R.id.tv_track)
        tvSavedDevice = findViewById(R.id.tv_saved_device)
        btnConnect   = findViewById(R.id.btn_connect)
        btnForget    = findViewById(R.id.btn_forget)
        btnPrev      = findViewById(R.id.btn_prev)
        btnPlay      = findViewById(R.id.btn_play)
        btnNext      = findViewById(R.id.btn_next)

        setControlsEnabled(false)
        refreshSavedDeviceUI()

        btnConnect.setOnClickListener { requestBtPermsAndPick() }

        btnForget.setOnClickListener {
            Prefs(this).forget()
            stopService(Intent(this, BluetoothClientService::class.java))
            refreshSavedDeviceUI()
            tvStatus.text = "❌ Нет соединения"
            setControlsEnabled(false)
        }

        btnPrev.setOnClickListener { BluetoothClientService.instance?.sendCommand(Constants.CMD_PREV) }
        btnPlay.setOnClickListener { BluetoothClientService.instance?.sendCommand(Constants.CMD_PLAY) }
        btnNext.setOnClickListener { BluetoothClientService.instance?.sendCommand(Constants.CMD_NEXT) }
    }

    override fun onResume() {
        super.onResume()
        // FIX: на Android 13+ registerReceiver без флага бросает SecurityException
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_CONNECTION_CHANGED)
            addAction(Constants.ACTION_TRACK_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        refreshSavedDeviceUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun refreshSavedDeviceUI() {
        val prefs = Prefs(this)
        val name = prefs.savedDeviceName
        val addr = prefs.savedDeviceAddress
        if (addr != null) {
            tvSavedDevice.text = "📱 Привязано: ${name ?: addr}"
            btnForget.visibility = android.view.View.VISIBLE
            btnConnect.text = "Сменить устройство"
        } else {
            tvSavedDevice.text = "Устройство не привязано"
            btnForget.visibility = android.view.View.GONE
            btnConnect.text = "Выбрать устройство"
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
        } else {
            showDevicePicker()
        }
    }

    private fun showDevicePicker() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val paired = adapter.bondedDevices.toList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "Нет сопряжённых устройств. Сначала свяжите телефоны в настройках Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }

        val labels = paired.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Выберите сервер")
            .setItems(labels) { _, idx -> connectTo(paired[idx]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        // Сохраняем устройство — при следующем запуске подключится автоматически
        Prefs(this).apply {
            savedDeviceAddress = device.address
            savedDeviceName    = device.name ?: device.address
        }
        refreshSavedDeviceUI()

        tvStatus.text = "⏳ Подключение к ${device.name}..."
        val intent = Intent(this, BluetoothClientService::class.java).apply {
            putExtra(Constants.EXTRA_DEVICE_ADDRESS, device.address)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPrev.isEnabled = enabled
        btnPlay.isEnabled = enabled
        btnNext.isEnabled = enabled
    }
}
