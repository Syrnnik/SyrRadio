# Документация SyrRadio

Каждый файл — про одну узкую вещь. Описание мода для игроков — в
[../README.md](../README.md).

## Как устроено — [architecture/](architecture/)

- [overview.md](architecture/overview.md) — части системы и путь трека
- [playlist.md](architecture/playlist.md) — список с курсором, режимы плеера, хранение
- [playback.md](architecture/playback.md) — рассылка файлов и синхронный старт
- [protocol.md](architecture/protocol.md) — пакеты и совместимость версий

## Bridge — [bridge/](bridge/)

- [api.md](bridge/api.md) — HTTP API для мода
- [sources.md](bridge/sources.md) — площадки и как добавить новую
- [config.md](bridge/config.md) — переменные окружения

## Развёртывание — [deploy/](deploy/)

- [bridge.md](deploy/bridge.md) — сборка и выкладка bridge
- [server.md](deploy/server.md) — мод на сервере Minecraft
- [yandex.md](deploy/yandex.md) — токен Яндекс Музыки
- [servnnik.md](deploy/servnnik.md) — как это стоит на servnnik

## Разработка — [dev/](dev/)

- [local.md](dev/local.md) — dev-сервер, клиент и bridge на одной машине
- [icons.md](dev/icons.md) — иконки плеера

## Правила — [contributing/](contributing/)

- [commits.md](contributing/commits.md) — сообщения коммитов
- [versioning.md](contributing/versioning.md) — версия мода и протокола
- [release.md](contributing/release.md) — выпуск версии
- [docs.md](contributing/docs.md) — как вести эту документацию

## Задачи — [tasks.md](tasks.md)

Что решили сделать потом.
