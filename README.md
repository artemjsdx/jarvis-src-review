# Jarvis

Нативный голосовой ассистент для Android с агентским runtime (инструменты, песочница, терминал, память, ADB).
Состояние разработки — в [PLAN.md](PLAN.md), решения и блокеры — в [NOTES.md](NOTES.md).

## Сборка (в Termux, без компьютера)

```bash
pkg install openjdk-21 aapt2 d8 apksigner zipalign python
bash tools/setup.sh        # скачивает android.jar (API 36) и собирает lambda-stub в .cache/
bash build.sh debug        # build/outputs/jarvis-<версия>-debug.apk
bash build.sh release      # без отладочных компонентов
bash build.sh test         # JVM-тесты чистого ядра
```

`./build.sh` через `bash`: на общем хранилище исполняемый бит не работает.

## Установка

```bash
termux-open ~/jarvis/releases/jarvis-<версия>.apk   # откроет системный установщик
```

Разрешите Termux «Установку неизвестных приложений», затем нажмите «Установить» / «Обновить».
Подпись постоянная (`~/.jarvis-keys/`), поэтому новые версии обновляют установленную.

## Статус

Работа идёт; список реально проверенных функций и известных ограничений — в NOTES.md. Функции, которые не проверены, не заявлены как готовые.
