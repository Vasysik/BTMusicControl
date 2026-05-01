package com.example.btmusic.client

import android.content.*
import com.example.btmusic.common.Constants

/**
 * Слушает broadcast-интенты от KeyMapper и пересылает команду на сервер.
 *
 * Настройка в KeyMapper (без рута):
 * ─────────────────────────────────
 * 1. Создай новый маппинг (+ снизу)
 * 2. Триггер: выбери кнопку (например, Vol Down)
 * 3. Action → "Send broadcast intent"
 *    - Action: com.mygadget.action.NEXT_TRACK  (или PREV_TRACK, PLAY_PAUSE)
 *    - Package: оставь пустым
 *    - Class: оставь пустым
 * 4. Дополнительно можно поставить ограничение:
 *    Constraint → "BT устройство подключено" → выбери свой сервер
 *
 * Интенты которые слушаем:
 *   com.mygadget.action.PLAY_PAUSE → PLAY
 *   com.mygadget.action.NEXT_TRACK → NEXT
 *   com.mygadget.action.PREV_TRACK → PREV
 */
class KeyMapperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = when (intent.action) {
            Constants.ACTION_BT_NEXT -> Constants.CMD_NEXT
            Constants.ACTION_BT_PREV -> Constants.CMD_PREV
            Constants.ACTION_BT_PLAY -> Constants.CMD_PLAY
            else -> return
        }
        BluetoothClientService.instance?.sendCommand(cmd)
    }
}
