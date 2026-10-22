"""Список площадок. Порядок важен: побеждает первая, чей шаблон совпал со ссылкой."""

from __future__ import annotations

from . import config
from .sources.base import Source, SourceError
from .sources.yandex import YandexSource
from .sources.ytdlp import YtDlpSource


def build_sources() -> list[Source]:
    return [
        YandexSource(config.YANDEX_TOKEN, report_plays=config.YANDEX_REPORT_PLAYS),
        YtDlpSource(
            "youtube", "YouTube",
            proxy=config.PROXY_YOUTUBE,
            search_prefix="ytsearch",
            url_patterns=[r"(^|\.)youtube\.com/", r"(^|//)youtu\.be/"],
        ),
        YtDlpSource(
            "soundcloud", "SoundCloud",
            proxy=config.PROXY_OTHER,
            search_prefix="scsearch",
            url_patterns=[r"soundcloud\.com/"],
        ),
        # Всё остальное, что понимает yt-dlp: Bandcamp, VK Видео, прямые ссылки на файлы и т.д.
        YtDlpSource("other", "yt-dlp", proxy=config.PROXY_OTHER),
    ]


SOURCES = build_sources()


def source_for_url(url: str) -> Source:
    for s in SOURCES:
        if s.matches(url):
            if not s.available():
                raise SourceError(f"{s.name} не настроена на сервере", 503)
            return s
    raise SourceError("Не знаю, откуда качать эту ссылку", 400)
