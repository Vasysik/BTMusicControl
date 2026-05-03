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
    private lateinit var tvClientName: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnNotifAccess: Button

    private var serviceRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val connected  = intent.getBooleanExtra(Constants.EXTRA_CONNECTED, false)
            val clientName = intent.getStringExtra(Constants.EXTRA_CLIENT_NAME) ?: ""
            applyState(connected, clientName)
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

        tvStatus     = findViewById(R.id.tv_status)
        tvClientName = findViewById(R.id.tv_client_name)
        btnToggle    = findViewById(R.id.btn_toggle)
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

        val filter = IntentFilter(Constants.ACTION_CONNECTION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }

        // Синхронизируем UI с живым состоянием сервиса
        val svc = BluetoothServerService.instance
        serviceRunning = svc != null
        if (svc == null) {
            btnToggle.text  = "Запустить сервер"
            applyState(false, "")
        } else {
            btnToggle.text = "Остановить сервер"
            applyState(svc.isClientConnected, svc.connectedClientName)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun applyState(connected: Boolean, clientName: String) {
        if (connected) {
            tvStatus.text     = "Пульт подключён"
            tvClientName.text = clientName
            tvClientName.visibility = android.view.View.VISIBLE
        } else {
            tvStatus.text     = if (serviceRunning) "Ожидание подключения..." else "Сервер остановлен"
            tvClientName.visibility = android.view.View.GONE
        }
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
        } else startServer()
    }

    private fun startServer() {
        ContextCompat.startForegroundService(this, Intent(this, BluetoothServerService::class.java))
        serviceRunning = true
        btnToggle.text = "Остановить сервер"
        applyState(false, "")
    }

    private fun stopServer() {
        stopService(Intent(this, BluetoothServerService::class.java))
        serviceRunning = false
        btnToggle.text = "Запустить сервер"
        applyState(false, "")
    }
}
