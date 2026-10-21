# Мод на сервере

1. Jar в `mods/` сервера.
2. `config/syrradio-server.toml`:

   ```toml
   bridgeUrl = "http://syrradio-bridge:8080"
   bridgeToken = "<BRIDGE_TOKEN из .env bridge>"
   chunksPerTick = 4

   [joinMessages]
   downloadUrl = "https://github.com/Syrnnik/SyrRadio/releases/latest"

   [joinMessages.ru]
   missingMod = "..."
   outdatedMod = "..."

   [joinMessages.en]
   missingMod = "..."
   outdatedMod = "..."
   ```

   Чего нет в файле, мод допишет сам со значениями по умолчанию.
3. Перезапустить сервер. Перед установкой — бэкап мира.

Конфиг создаётся только на выделенном сервере: в одиночной игре радио не
работает, у игроков пустого серверного конфига не появляется.

Проверка после запуска:

- в списке модов при старте есть `syrradio <версия>`;
- `/syrradio queue` отвечает (из консоли или RCON);
- из контейнера сервера отвечает `curl http://syrradio-bridge:8080/health`.

Обновление мода — заменить jar и перезапустить. Если поменялась версия
протокола, игроки со старым модом заходят, но радио у них молчит, а сервер
пишет им `outdatedMod` со ссылкой.
