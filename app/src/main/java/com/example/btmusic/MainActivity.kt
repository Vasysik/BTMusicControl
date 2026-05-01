package com.example.btmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.btmusic.client.ClientActivity
import com.example.btmusic.server.ServerActivity

class MainActivity : AppCompatActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* разрешение получено или отклонено — в любом случае идём дальше */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+ — запрашиваем разрешение на уведомления.
        // Без него Foreground Service не может показать уведомление и падает.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<Button>(R.id.btn_server).setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_client).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
    }
}
