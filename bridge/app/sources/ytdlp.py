"""Площадки через yt-dlp: YouTube, SoundCloud и любые ссылки, которые он понимает.

Трек всегда скачивается целиком до проигрывания, поэтому обрывы потока и
протухшие ссылки до игроков не доходят.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

import yt_dlp

from .base import Candidate, Source, SourceError

log = logging.getLogger(__name__)


class _Quiet:
    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        log.info("yt-dlp: %s", msg)

    def error(self, msg):
        log.warning("yt-dlp: %s", msg)


class YtDlpSource(Source):
    def __init__(
        self,
        id: str,
        name: str,
        proxy: str = "",
        search_prefix: str | None = None,
        url_patterns: list[str] | None = None,
    ):
        self.id = id
        self.name = name
        self._proxy = proxy
        self._search_prefix = search_prefix
        self.searchable = search_prefix is not None
        self._patterns = [re.compile(p, re.IGNORECASE) for p in (url_patterns or [])]

    def _opts(self, **extra) -> dict:
        opts = {
            "format": "bestaudio/best",
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "logger": _Quiet(),
            "socket_timeout": 20,
            "retries": 3,
        }
        if self._proxy:
            opts["proxy"] = self._proxy
        opts.update(extra)
        return opts

    def matches(self, url: str) -> bool:
        # Без шаблонов площадка ловит любую ссылку — это запасной вариант.
        return not self._patterns or any(p.search(url) for p in self._patterns)

    def _extract(self, target: str, search: bool = False, **opts) -> dict:
        try:
            with yt_dlp.YoutubeDL(self._opts(**opts)) as ydl:
                info = ydl.extract_info(target, download=False)
        except yt_dlp.utils.DownloadError as e:
            raise SourceError(f"{self.name}: {_short(e)}") from e
        if info is None:
            raise SourceError(f"{self.name}: ничего не нашлось", 404)
        if info.get("_type") == "playlist":
            if not search:
                raise SourceError(f"{self.name}: пока поддерживаются только ссылки на отдельные треки", 400)
            entries = [e for e in (info.get("entries") or []) if e]
            if not entries:
                raise SourceError(f"{self.name}: ничего не нашлось", 404)
            info = entries[0]
        return info

    def resolve_url(self, url: str) -> Candidate:
        # extract_flat: на плейлист yt-dlp сразу вернёт список, не разбирая
        # каждое видео, и мы быстро ответим, что плейлисты пока не берём.
        return self._candidate(self._extract(url, extract_flat="in_playlist"))

    def search(self, query: str) -> Candidate:
        if not self._search_prefix:
            return super().search(query)
        return self._candidate(self._extract(f"{self._search_prefix}1:{query}", search=True))

    def _candidate(self, info: dict) -> Candidate:
        if info.get("is_live"):
            raise SourceError(f"{self.name}: прямые эфиры не поддерживаются", 400)
        title = info.get("track") or info.get("title") or "Без названия"
        artist = info.get("artist") or info.get("creator") or info.get("uploader") or "—"
        # На YouTube исполнитель часто уже в названии: «Rick Astley - Never Gonna…».
        # Без этого вышло бы «Rick Astley — Rick Astley - Never Gonna…».
        for sep in (" - ", " — ", " – "):
            prefix = artist + sep
            if title.lower().startswith(prefix.lower()):
                title = title[len(prefix):]
                break
        url = info.get("webpage_url") or info.get("original_url") or info.get("url")
        return Candidate(
            source=self.id,
            native_id=str(info.get("id") or url),
            title=title,
            artist=artist,
            duration_ms=int((info.get("duration") or 0) * 1000),
            page_url=url,
        )

    def download(self, cand: Candidate, dest_dir: Path) -> Path:
        opts = self._opts(outtmpl=str(dest_dir / "source.%(ext)s"))
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(cand.page_url, download=True)
                if info and info.get("_type") == "playlist":
                    info = next(e for e in info["entries"] if e)
                downloads = (info or {}).get("requested_downloads") or []
                path = Path(downloads[0]["filepath"]) if downloads else Path(ydl.prepare_filename(info))
        except yt_dlp.utils.DownloadError as e:
            raise SourceError(f"{self.name}: {_short(e)}") from e
        if not path.exists():
            raise SourceError(f"{self.name}: файл не скачался")
        return path


def _short(e: Exception) -> str:
    text = str(e).replace("ERROR: ", "").strip()
    return text.splitlines()[0][:200] if text else "ошибка загрузки"
