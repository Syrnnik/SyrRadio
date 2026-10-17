# Docker Hub из России не открывается, на servnnik образы берём с зеркала
# (REGISTRY=mirror.gcr.io в .env).
ARG REGISTRY=docker.io

# deno нужен yt-dlp, чтобы решать JS-проверки YouTube.
FROM ${REGISTRY}/denoland/deno:bin-2.7.14 AS deno

FROM ${REGISTRY}/library/python:3.12-slim-bookworm

# ffmpeg из Debian собран с libvorbis — именно им перегоняем треки в ogg.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=deno /deno /usr/local/bin/deno

# Свой venv, принадлежащий непривилегированному пользователю: так
# entrypoint может обновить yt-dlp при старте, не будучи root.
RUN useradd -u 1000 -m radio \
    && python -m venv /opt/venv \
    && mkdir /cache \
    && chown -R radio:radio /opt/venv /cache
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && chown -R radio:radio /opt/venv
COPY app ./app
COPY entrypoint.sh /entrypoint.sh

USER radio
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4)"
ENTRYPOINT ["/entrypoint.sh"]
