package com.example.btmusic.server

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream

class MusicNotificationListener : NotificationListenerService() {

    private var lastCoverHash = 0
    private var lastTrackId: String? = null
    private var activeController: MediaController? = null
    private var sessionManager: MediaSessionManager? = null

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
            
            val title  = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            
            val trackId = "${artist}_${title}"
            val isNewTrack = trackId != lastTrackId
            lastTrackId = trackId
            
            val info = if (artist.isNotEmpty()) "$artist — $title" else title
            BluetoothServerService.instance?.sendTrackInfo(info)

            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            
            if (art != null) {
                val hash = art.hashCode()
                if (hash != lastCoverHash) {
                    lastCoverHash = hash
                    BluetoothServerService.instance?.sendAlbumCover(compressToBase64(art))
                }
            } else if (isNewTrack) {
                lastCoverHash = 0
                BluetoothServerService.instance?.sendAlbumCover("")  // Пустая строка = сброс
            }
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val playing = controllers?.firstOrNull { 
            it.playbackState?.state == PlaybackState.STATE_PLAYING 
        }
        val candidate = playing ?: controllers?.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
        }

        if (candidate != null && candidate != activeController) {
            switchToController(candidate)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        
        try {
            val component = ComponentName(this, this::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val sessions = sessionManager?.getActiveSessions(component) ?: emptyList()
            sessionsChangedListener.onActiveSessionsChanged(sessions)
        } catch (e: SecurityException) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionRunnable)
        activeController?.unregisterCallback(controllerCallback)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        BluetoothServerService.instance?.activeMediaController = null
    }

    private fun switchToController(ctrl: MediaController) {
        activeController?.unregisterCallback(controllerCallback)
        activeController = ctrl
        ctrl.registerCallback(controllerCallback)
        BluetoothServerService.instance?.activeMediaController = ctrl
        
        lastTrackId = null
        lastCoverHash = 0
        
        controllerCallback.onPlaybackStateChanged(ctrl.playbackState)
        controllerCallback.onMetadataChanged(ctrl.metadata)
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
