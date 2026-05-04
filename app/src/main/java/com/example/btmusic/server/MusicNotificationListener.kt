package com.example.btmusic.server

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream

class MusicNotificationListener : NotificationListenerService() {

    private var lastCoverHash = 0
    private var activeController: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            val ctrl  = activeController ?: return
            val state = ctrl.playbackState ?: return
            val dur   = ctrl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur > 0) {
                BluetoothServerService.instance?.sendPosition(state.position, dur)
            }
            if (state.state == PlaybackState.STATE_PLAYING) {
                handler.postDelayed(this, 500)
            }
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            BluetoothServerService.instance?.sendPlaybackState(playing)
            handler.removeCallbacks(positionRunnable)
            if (playing) handler.post(positionRunnable)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata ?: return
            val title  = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val info = if (artist.isNotEmpty()) "$artist — $title" else title
            BluetoothServerService.instance?.sendTrackInfo(info)

            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            
            if (art != null && art.hashCode() != lastCoverHash) {
                lastCoverHash = art.hashCode()
                BluetoothServerService.instance?.sendAlbumCover(compressToBase64(art))
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras: Bundle = sbn.notification.extras

        @Suppress("DEPRECATION")
        val token = extras.getParcelable<MediaSession.Token>("android.mediaSession")
        
        if (token != null) {
            try {
                val ctrl = MediaController(this, token)
                if (ctrl.packageName != activeController?.packageName) {
                    activeController?.unregisterCallback(controllerCallback)
                    activeController = ctrl
                    ctrl.registerCallback(controllerCallback)
                    BluetoothServerService.instance?.activeMediaController = ctrl
                    controllerCallback.onPlaybackStateChanged(ctrl.playbackState)
                    controllerCallback.onMetadataChanged(ctrl.metadata)
                }
            } catch (_: Exception) { 
                fallbackFromExtras(extras) 
            }
        }
    }

    private fun fallbackFromExtras(extras: Bundle) {
        val title  = extras.getString("android.title")?.takeIf { it.isNotBlank() } ?: return
        val artist = extras.getString("android.text")?.takeIf { it.isNotBlank() } ?: ""
        val info   = if (artist.isNotEmpty()) "$artist — $title" else title
        BluetoothServerService.instance?.sendTrackInfo(info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // TODO
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionRunnable)
        activeController?.unregisterCallback(controllerCallback)
        BluetoothServerService.instance?.activeMediaController = null
    }

    private fun compressToBase64(src: Bitmap): String {
        val size = 300
        val scaled = if (src.width > size || src.height > size) {
            val ratio = size.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
