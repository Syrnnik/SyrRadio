# Проверка на одной машине

Нужны JDK 17, Python 3.11+ и ffmpeg.

1. Bridge:

   ```bash
   cd bridge
   python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
   BRIDGE_TOKEN=test CACHE_DIR=/tmp/syrradio-cache .venv/bin/uvicorn app.main:app --port 8790
   ```

2. Dev-сервер — `./gradlew runServer` в `mod/`. Папка `run-server/`
   (в git не попадает). В `run-server/server.properties`: `online-mode=false`,
   RCON на 25575; в `run-server/config/syrradio-server.toml`:
   `bridgeUrl = "http://127.0.0.1:8790"`, `bridgeToken = "test"`.

3. Клиент — `./gradlew runClient -PjoinLocal`: сразу заходит на
   `localhost:25565` игроком `Dev`, окно 1500×880. Папка `run/`.

Особенности:

- быстрый вход (`--quickPlayMultiplayer`) иногда виснет на логине: клиент
  принимает сервер за ванильный. Второй запуск входит; надёжнее — через
  список серверов, туда стоит добавить `localhost`;
- код подхватывается только при перезапуске: клиентский — клиента,
  серверный — сервера (клиент при этом отключается);
- ffmpeg из Homebrew без libvorbis — bridge кодирует встроенным кодером,
  файлы вдвое больше и звучат хуже, для проверки годится.
