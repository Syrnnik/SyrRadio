# Переменные окружения

Файл `.env` рядом с `docker-compose.yml`, образец — `bridge/.env.example`.

| Переменная | По умолчанию | Что |
|------------|--------------|-----|
| `COMPOSE_PROJECT_NAME` | — | проект docker compose; от него имя сети |
| `REGISTRY` | `docker.io` | откуда тянуть базовые образы при сборке |
| `BRIDGE_TOKEN` | — | общий секрет с модом; без него всё, кроме `/health`, — 503 |
| `YANDEX_TOKEN` | — | токен Яндекс Музыки, пусто — площадка выключена |
| `PROXY_YOUTUBE` | — | HTTP-прокси для YouTube |
| `PROXY_OTHER` | — | HTTP-прокси для остальных площадок через yt-dlp |
| `MAX_DURATION_SEC` | `1200` | длиннее не берём |
| `CACHE_DIR` | `/cache` | где лежат готовые ogg |
| `CACHE_TTL_SEC` | `3600` | сколько их хранить |
| `OGG_QUALITY` | `3` | качество libvorbis (≈112 кбит/с) |
| `YTDLP_AUTO_UPDATE` | `true` | обновлять yt-dlp при старте контейнера |

Яндекс всегда идёт напрямую: ему нужен российский IP.

Токен сгенерировать: `openssl rand -hex 24`. Тот же токен — в
`bridgeToken` конфига мода на сервере.
