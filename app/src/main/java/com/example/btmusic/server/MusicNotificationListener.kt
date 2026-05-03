package com.example.btmusic.server

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream

class MusicNotificationListener : NotificationListenerService() {

    private val musicPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "ru.yandex.music",
        "deezer.android.app",
        "com.soundcloud.android",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.vk.vkclient",
        "com.vkontakte.android"
    )

    // Чтобы не спамить одинаковой обложкой при каждом обновлении уведомления
    private var lastCoverHash = 0

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in musicPackages) return

        val extras: Bundle = sbn.notification.extras

        val title  = extras.getString("android.title")?.takeIf { it.isNotBlank() } ?: return
        val artist = extras.getString("android.text")?.takeIf { it.isNotBlank() } ?: ""
        val trackInfo = if (artist.isNotEmpty()) "$artist — $title" else title

        BluetoothServerService.instance?.sendTrackInfo(trackInfo)

        // ─── Обложка через MediaSession ───────────────────────────────────────
        @Suppress("DEPRECATION")
        val token = extras.getParcelable<MediaSession.Token>("android.mediaSession")
        if (token != null) {
            try {
                val meta = MediaController(this, token).metadata ?: return
                val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

                if (art != null && art.hashCode() != lastCoverHash) {
                    lastCoverHash = art.hashCode()
                    val b64 = compressToBase64(art)
                    BluetoothServerService.instance?.sendAlbumCover(b64)
                }
            } catch (_: Exception) { /* MediaSession может быть недоступна */ }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    /**
     * Сжимает bitmap до 250×250, JPEG 55% → ~15-30 КБ → base64 ~20-40 КБ.
     * Отправляется одной строкой через RFCOMM.
     */
    private fun compressToBase64(src: Bitmap): String {
        val size = 250
        val scaled = if (src.width > size || src.height > size) {
            val ratio = size.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
        } else src

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
