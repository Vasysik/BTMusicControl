package com.example.btmusic.common

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("btmusic_prefs", Context.MODE_PRIVATE)

    var savedDeviceAddress: String?
        get() = sp.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = if (value != null)
            sp.edit().putString(KEY_DEVICE_ADDRESS, value).apply()
        else
            sp.edit().remove(KEY_DEVICE_ADDRESS).apply()

    var savedDeviceName: String?
        get() = sp.getString(KEY_DEVICE_NAME, null)
        set(value) = if (value != null)
            sp.edit().putString(KEY_DEVICE_NAME, value).apply()
        else
            sp.edit().remove(KEY_DEVICE_NAME).apply()

    fun forget() {
        sp.edit().remove(KEY_DEVICE_ADDRESS).remove(KEY_DEVICE_NAME).apply()
    }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME    = "device_name"
    }
}
