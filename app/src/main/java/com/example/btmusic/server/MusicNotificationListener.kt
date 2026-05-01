package com.example.btmusic.server

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Читает уведомления музыкальных плееров и передаёт название трека клиенту.
 *
 * ВАЖНО: пользователь должен вручную выдать доступ:
 * Настройки → Приложения → Специальный доступ → Доступ к уведомлениям
 *
 * Рут не нужен — это стандартный API Android.
 */
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in musicPackages) return

        val extras = sbn.notification.extras
        val title  = extras.getString("android.title")?.takeIf { it.isNotBlank() } ?: return
        val artist = extras.getString("android.text")?.takeIf { it.isNotBlank() } ?: ""

        val trackInfo = if (artist.isNotEmpty()) "$artist — $title" else title
        BluetoothServerService.instance?.sendTrackInfo(trackInfo)
    }
}
