#!/bin/sh
# YouTube регулярно ломает загрузчики, а yt-dlp так же регулярно чинится.
# Поэтому при каждом старте подтягиваем свежий yt-dlp; не вышло — работаем
# с тем, что в образе. Починить YouTube = перезапустить контейнер.
if [ "${YTDLP_AUTO_UPDATE:-true}" = "true" ]; then
    timeout 120 pip install --no-cache-dir --quiet --upgrade "yt-dlp[default]" \
        || echo "yt-dlp: обновить не удалось, работаю с версией из образа"
fi
echo "yt-dlp $(python -m yt_dlp --version)"
exec uvicorn app.main:app --host 0.0.0.0 --port 8080 --no-access-log
