#!/bin/sh
# Выкладка bridge на servnnik: копирует исходники в ~/minecraft/syrradio-bridge
# и пересобирает контейнер. .env на сервере не трогает.
set -e
cd "$(dirname "$0")"
SSH="${SSH:-servnnik-local-ssh -o BatchMode=yes}"
DIR="${DIR:-minecraft/syrradio-bridge}"

COPYFILE_DISABLE=1 tar --no-xattrs --exclude .env --exclude __pycache__ -czf - Dockerfile entrypoint.sh requirements.txt \
    docker-compose.yml .env.example .dockerignore app \
    | $SSH "mkdir -p ~/$DIR && tar -xzf - -C ~/$DIR"

$SSH "cd ~/$DIR && test -f .env || { echo 'нет .env: скопируй .env.example и заполни BRIDGE_TOKEN'; exit 1; }
      docker compose up -d --build && docker compose ps"
