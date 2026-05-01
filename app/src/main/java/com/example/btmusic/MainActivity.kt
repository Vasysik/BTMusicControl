package com.example.btmusic

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.btmusic.client.ClientActivity
import com.example.btmusic.server.ServerActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_server).setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_client).setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }
    }
}
