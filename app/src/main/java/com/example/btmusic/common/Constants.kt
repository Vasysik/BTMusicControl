package com.example.btmusic.common

import java.util.UUID

object Constants {

    val BT_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    const val BT_SERVER_NAME = "BTMusicServer"

    // Команды клиент → сервер
    const val CMD_PLAY     = "PLAY"
    const val CMD_NEXT     = "NEXT"
    const val CMD_PREV     = "PREV"
    const val CMD_VOL_UP   = "VOL_UP"
    const val CMD_VOL_DOWN = "VOL_DOWN"
    const val CMD_SEEK_PREFIX = "SEEK:"    // SEEK:<position_ms>

    // Данные сервер → клиент
    const val CMD_TRACK_PREFIX = "TRACK:"
    const val CMD_ART_PREFIX   = "ART:"
    const val CMD_STATE_PREFIX = "STATE:"  // STATE:1 = playing, STATE:0 = paused
    const val CMD_POS_PREFIX   = "POS:"    // POS:<position_ms>:<duration_ms>

    // KeyMapper
    const val ACTION_BT_PLAY = "com.mygadget.action.PLAY_PAUSE"
    const val ACTION_BT_NEXT = "com.mygadget.action.NEXT_TRACK"
    const val ACTION_BT_PREV = "com.mygadget.action.PREV_TRACK"

    // Broadcasts Service → Activity
    const val ACTION_CONNECTION_CHANGED  = "com.btmusic.CONNECTION_CHANGED"
    const val ACTION_TRACK_UPDATED       = "com.btmusic.TRACK_UPDATED"
    const val ACTION_ART_UPDATED         = "com.btmusic.ART_UPDATED"
    const val ACTION_STATE_UPDATED       = "com.btmusic.STATE_UPDATED"
    const val ACTION_POSITION_UPDATED    = "com.btmusic.POSITION_UPDATED"

    const val EXTRA_CONNECTED    = "connected"
    const val EXTRA_TRACK_INFO   = "track_info"
    const val EXTRA_ART_BASE64   = "art_b64"
    const val EXTRA_IS_PLAYING   = "is_playing"
    const val EXTRA_POSITION_MS  = "position_ms"
    const val EXTRA_DURATION_MS  = "duration_ms"
    const val EXTRA_CLIENT_NAME  = "client_name"
    const val EXTRA_DEVICE_ADDRESS = "device_address"

    const val NOTIF_CHANNEL_ID   = "bt_music_ch"
    const val NOTIF_CHANNEL_NAME = "BT Music Control"
    const val NOTIF_ID_SERVER    = 1001
    const val NOTIF_ID_CLIENT    = 1002
}
