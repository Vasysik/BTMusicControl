package com.example.btmusic.common

import java.util.UUID

object Constants {

    val BT_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    const val BT_SERVER_NAME = "BTMusicServer"

    // Протокол
    const val CMD_PLAY         = "PLAY"
    const val CMD_NEXT         = "NEXT"
    const val CMD_PREV         = "PREV"
    const val CMD_VOL_UP       = "VOL_UP"
    const val CMD_VOL_DOWN     = "VOL_DOWN"
    const val CMD_TRACK_PREFIX = "TRACK:"

    // Интенты от KeyMapper → Client
    const val ACTION_BT_PLAY = "com.mygadget.action.PLAY_PAUSE"
    const val ACTION_BT_NEXT = "com.mygadget.action.NEXT_TRACK"
    const val ACTION_BT_PREV = "com.mygadget.action.PREV_TRACK"

    // Широковещательные события Service → Activity
    const val ACTION_CONNECTION_CHANGED = "com.btmusic.CONNECTION_CHANGED"
    const val ACTION_TRACK_UPDATED      = "com.btmusic.TRACK_UPDATED"
    const val EXTRA_CONNECTED           = "connected"
    const val EXTRA_TRACK_INFO          = "track_info"

    // Параметры для старта сервисов
    const val EXTRA_DEVICE_ADDRESS = "device_address"

    // Foreground-уведомления
    const val NOTIF_CHANNEL_ID   = "bt_music_ch"
    const val NOTIF_CHANNEL_NAME = "BT Music Control"
    const val NOTIF_ID_SERVER    = 1001
    const val NOTIF_ID_CLIENT    = 1002
}
