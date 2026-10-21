# Выкладка bridge

Bridge живёт в той же docker-сети, что и сервер Minecraft, наружу порт не
открыт. Мод ходит к нему по имени сервиса: `http://syrradio-bridge:8080`.

Первый раз на сервере:

```bash
mkdir -p ~/minecraft/syrradio-bridge
# .env по образцу bridge/.env.example, BRIDGE_TOKEN — openssl rand -hex 24
```

`COMPOSE_PROJECT_NAME` — тот же проект, что у сервера Minecraft, тогда они
окажутся в одной сети `<проект>_default`.

Дальше выкладка одной командой с машины разработчика:

```bash
bridge/deploy.sh
```

Скрипт копирует исходники в `~/minecraft/syrradio-bridge` и делает
`docker compose up -d --build`; `.env` на сервере не трогает. Куда
подключаться — переменные `SSH` (команда ssh) и `DIR` (папка на сервере).

Проверка изнутри сети:

```bash
docker run --rm --network <проект>_default curlimages/curl \
  -s http://syrradio-bridge:8080/health
```

Сломался YouTube — `docker compose restart`: при старте подтянется свежий
yt-dlp. Логи — `docker compose logs -f`: на каждый трек строка
«готов … МБ за … с» или «отказ: …».
