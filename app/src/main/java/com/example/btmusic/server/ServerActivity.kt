package com.example.btmusic.server

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.btmusic.R
import com.example.btmusic.common.Constants

class ServerActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnNotifAccess: Button

    private var serviceRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(Constants.EXTRA_CONNECTED, false)
            tvStatus.text = if (connected) "✅ Клиент подключён" else "⏳ Ожидание клиента..."
        }
    }

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startServer()
        else Toast.makeText(this, "Нужны разрешения Bluetooth", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        tvStatus       = findViewById(R.id.tv_status)
        btnToggle      = findViewById(R.id.btn_toggle)
        btnNotifAccess = findViewById(R.id.btn_notif_access)

        btnToggle.setOnClickListener {
            if (serviceRunning) stopServer() else requestBtPermissionsAndStart()
        }

        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // FIX: на Android 13+ registerReceiver без флага бросает SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                stateReceiver,
                IntentFilter(Constants.ACTION_CONNECTION_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, IntentFilter(Constants.ACTION_CONNECTION_CHANGED))
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun requestBtPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isEmpty()) startServer()
            else btPermissionLauncher.launch(needed.toTypedArray())
        } else {
            startServer()
        }
    }

    private fun startServer() {
        ContextCompat.startForegroundService(this, Intent(this, BluetoothServerService::class.java))
        serviceRunning = true
        btnToggle.text = "Остановить сервер"
        tvStatus.text  = "⏳ Ожидание клиента..."
    }

    private fun stopServer() {
        stopService(Intent(this, BluetoothServerService::class.java))
        serviceRunning = false
        btnToggle.text = "Запустить сервер"
        tvStatus.text  = "Сервер остановлен"
    }
}
