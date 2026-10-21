# Выпуск версии

1. Поднять `mod_version` (и `Net.PROTOCOL`, если менялись пакеты).
2. Собрать: `cd mod && ./gradlew build`.
3. Коммит и тег `v<версия>`, пуш.
4. Релиз на GitHub с jar:

   ```bash
   gh release create v<версия> mod/build/libs/syrradio-forge-1.20.1-<версия>.jar \
     --title "SyrRadio <версия>" --notes "..."
   ```

   Ссылка в чате у игроков без мода ведёт на `releases/latest` — новый
   релиз сразу становится тем, что им предложат.
5. Обновить мод на сервере ([../deploy/server.md](../deploy/server.md)).
6. Если менялся bridge — `bridge/deploy.sh`.
