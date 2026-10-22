"""Настройки сервиса. Всё приходит из переменных окружения (.env рядом с compose)."""

import os
from pathlib import Path


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


# Общий секрет с модом: без него сервис отвечает 401 на всё, кроме /health.
BRIDGE_TOKEN = _env("BRIDGE_TOKEN")

# Токен аккаунта Яндекс Музыки. Пустой — площадка выключена.
YANDEX_TOKEN = _env("YANDEX_TOKEN")

# Отправлять ли Яндексу отметку о прослушивании после трека. Отметка попадает
# в историю и «Мою волну» аккаунта — на личном аккаунте туда уехали бы треки,
# которые ставят другие игроки. Поэтому по умолчанию выключено.
YANDEX_REPORT_PLAYS = _env("YANDEX_REPORT_PLAYS", "false").lower() in ("1", "true", "yes", "on")

# YouTube из России замедлен, поэтому его качаем через VPN-прокси servnnik.
# Остальные площадки по умолчанию идут напрямую.
PROXY_YOUTUBE = _env("PROXY_YOUTUBE")
PROXY_OTHER = _env("PROXY_OTHER")

# Длиннее не берём: файл рассылается всем через соединение игры, часовой микс его забьёт.
MAX_DURATION_SEC = int(_env("MAX_DURATION_SEC", "1200"))

# Готовые ogg живут недолго: это очередь, а не медиатека.
CACHE_DIR = Path(_env("CACHE_DIR", "/cache"))
CACHE_TTL_SEC = int(_env("CACHE_TTL_SEC", "3600"))

# Сколько раз пробовать скачать трек и сколько ждать между попытками.
# Площадки иногда отказывают разово (YouTube отвечает 403, если выход VPN
# сменился между получением ссылки и скачиванием), со второго раза проходит.
DOWNLOAD_ATTEMPTS = max(1, int(_env("DOWNLOAD_ATTEMPTS", "3")))
DOWNLOAD_RETRY_DELAY_SEC = float(_env("DOWNLOAD_RETRY_DELAY_SEC", "10"))

# Качество ogg vorbis (ffmpeg -q:a). 3 — около 112 кбит/с: четырёхминутный трек
# весит ~3,5 МБ, а на слух от исходного mp3 не отличается.
OGG_QUALITY = _env("OGG_QUALITY", "3")
