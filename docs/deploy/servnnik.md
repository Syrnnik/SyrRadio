# Как это стоит на servnnik

Домашний сервер, где живут серверы Minecraft (docker, проект `mc-server`).

| Что | Где |
|-----|-----|
| bridge | `~/minecraft/syrradio-bridge`, контейнер `mc-server-syrradio-bridge-1` |
| сеть | `mc-server_default` — общая с серверами Minecraft |
| мод | сервер `tfgm`: `mods/` и `config/syrradio-server.toml` в вольюме `mc-server_tfgm` (`server/`) |
| выкладка bridge | `bridge/deploy.sh` с мака, ssh через `servnnik-local-ssh` |

Особые настройки в `.env` bridge:

- `REGISTRY=mirror.gcr.io` — Docker Hub с servnnik не открывается, базовые
  образы берутся с зеркала Google. ghcr.io и PyPI открываются.
- `PROXY_YOUTUBE` и `PROXY_OTHER` = `http://10.200.200.2:8888` — HTTP-прокси
  на хосте, который выходит в интернет через RedBread VPN. С домашнего IP
  YouTube и SoundCloud заблокированы; Яндекс идёт напрямую.

Проверка прокси:

```bash
docker run --rm --network mc-server_default curlimages/curl \
  -s -m 10 -x http://10.200.200.2:8888 https://api.ipify.org
```

Должен вернуться адрес VPN, а не домашний. Таймаут — прокси или VPN лежит,
YouTube и SoundCloud при этом отказывают «Read timed out».

Перезапуск tfgm после замены мода — `rcon-cli stop` внутри контейнера:
у сервиса `restart: always`, docker поднимет его сам. Перед этим — бэкап
мира и проверка, что на сервере никого нет (`rcon-cli list`).
