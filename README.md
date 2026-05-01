# BT Music Control

Управление музыкой между двумя Android-телефонами по Bluetooth.
Без рута. Работает с Android 8.0+.

## Как открыть в Android Studio

1. Открой Android Studio
2. File → Open → выбери папку BTMusicControl
3. Подожди пока Gradle синхронизируется (~2 минуты)
4. Подключи телефон по USB, включи отладку разработчика
5. Run → Run 'app'

Установи APK на ОБА телефона.

## Как пользоваться

### Телефон с музыкой (СЕРВЕР):
1. Запусти приложение → "Я СЕРВЕР"
2. Нажми "Запустить сервер"
3. Запусти Spotify / YouTube Music / любой плеер
4. Опционально: нажми "Доступ к уведомлениям" и выдай разрешение
   (тогда название трека будет показываться на пульте)

### Японофон (КЛИЕНТ/ПУЛЬТ):
1. Сначала свяжи оба телефона через Bluetooth в настройках Android
2. Запусти приложение → "Я КЛИЕНТ"
3. Нажми "Подключиться к серверу"
4. Выбери сервер из списка
5. Управляй кнопками ⏮ ⏯ ⏭

### KeyMapper (опционально, для кнопок телефона):
См. файл KEYMAPPER_GUIDE.md

## Разрешения (ВСЕ без рута)

| Разрешение                    | Для чего                           | Рут? |
|------------------------------|------------------------------------|------|
| BLUETOOTH / BLUETOOTH_ADMIN  | Базовый BT (Android 8-11)          | Нет  |
| BLUETOOTH_CONNECT            | BT (Android 12+)                   | Нет  |
| MODIFY_AUDIO_SETTINGS        | dispatchMediaKeyEvent              | Нет  |
| FOREGROUND_SERVICE           | Фоновая работа сервиса             | Нет  |
| Доступ к уведомлениям        | Чтение трека из плеера (вручную)   | Нет  |
| Accessibility (KeyMapper)    | Перехват кнопок (в KeyMapper)      | Нет  |

## Структура проекта

```
app/src/main/java/com/example/btmusic/
├── MainActivity.kt              — Экран выбора роли
├── common/Constants.kt          — Константы протокола
├── server/
│   ├── BluetoothServerService.kt    — BT RFCOMM сервер + dispatchMediaKeyEvent
│   ├── ServerActivity.kt            — UI сервера
│   └── MusicNotificationListener.kt — Читает трек из уведомлений (опц.)
└── client/
    ├── BluetoothClientService.kt    — BT RFCOMM клиент + автопереподключение
    ├── ClientActivity.kt            — UI пульта
    └── KeyMapperReceiver.kt         — Принимает intents от KeyMapper
```
