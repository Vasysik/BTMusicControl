package com.example.btmusic.common

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("btmusic_prefs", Context.MODE_PRIVATE)

    // ─── Сохранённое устройство (клиент) ─────────────────────────────────────
    var savedDeviceAddress: String?
        get() = sp.getString(KEY_DEVICE_ADDRESS, null)
        set(v) = sp.edit().apply { if (v != null) putString(KEY_DEVICE_ADDRESS, v) else remove(KEY_DEVICE_ADDRESS) }.apply()

    var savedDeviceName: String?
        get() = sp.getString(KEY_DEVICE_NAME, null)
        set(v) = sp.edit().apply { if (v != null) putString(KEY_DEVICE_NAME, v) else remove(KEY_DEVICE_NAME) }.apply()

    fun forget() = sp.edit().remove(KEY_DEVICE_ADDRESS).remove(KEY_DEVICE_NAME).apply()

    // ─── Доверенные устройства (сервер) ──────────────────────────────────────
    /** Проверяет, доверяем ли мы MAC-адресу */
    fun isTrustedDevice(address: String): Boolean =
        getTrustedSet().contains(address)

    /** Добавляет устройство в доверенные */
    fun addTrustedDevice(address: String) {
        val set = getTrustedSet().toMutableSet()
        set.add(address)
        sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    /** Удаляет устройство из доверенных */
    fun removeTrustedDevice(address: String) {
        val set = getTrustedSet().toMutableSet()
        set.remove(address)
        sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    private fun getTrustedSet(): Set<String> =
        sp.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME    = "device_name"
        private const val KEY_TRUSTED        = "trusted_devices"
    }
}
