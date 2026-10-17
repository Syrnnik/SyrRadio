"""Яндекс Музыка через неофициальную библиотеку yandex-music.

Ведём себя как один слушатель: качаем один трек по запросу, лучшее доступное
mp3, а после проигрывания отправляем отметку о прослушивании с реальной
длительностью — как это делает приложение.
"""

from __future__ import annotations

import logging
import re
import threading
import uuid
from pathlib import Path

from yandex_music import Client
from yandex_music.exceptions import YandexMusicError

from .base import Candidate, Source, SourceError

log = logging.getLogger(__name__)

# music.yandex.ru/album/123/track/456, music.yandex.com/track/456 и т.п.
TRACK_URL = re.compile(r"music\.yandex\.[a-z]+/(?:album/(\d+)/)?track/(\d+)", re.IGNORECASE)
ANY_URL = re.compile(r"music\.yandex\.[a-z]+/", re.IGNORECASE)


class YandexSource(Source):
    id = "yandex"
    name = "Яндекс Музыка"

    def __init__(self, token: str):
        self._token = token
        self._client: Client | None = None
        self._lock = threading.Lock()

    def available(self) -> bool:
        return bool(self._token)

    def _api(self) -> Client:
        with self._lock:
            if self._client is None:
                try:
                    self._client = Client(self._token).init()
                except YandexMusicError as e:
                    raise SourceError(f"Яндекс Музыка не пускает: {e}") from e
            return self._client

    def matches(self, url: str) -> bool:
        # Ловим весь домен, чтобы на альбом или плейлист ответить понятной
        # ошибкой, а не отдать ссылку yt-dlp.
        return bool(ANY_URL.search(url))

    def resolve_url(self, url: str) -> Candidate:
        m = TRACK_URL.search(url)
        if not m:
            raise SourceError("Яндекс Музыка: пока поддерживаются только ссылки на отдельные треки", 400)
        album_id, track_id = m.group(1), m.group(2)
        key = f"{track_id}:{album_id}" if album_id else track_id
        try:
            tracks = self._api().tracks([key])
        except YandexMusicError as e:
            raise SourceError(f"Яндекс Музыка: {e}") from e
        if not tracks:
            raise SourceError("Яндекс Музыка: трек не найден", 404)
        return self._candidate(tracks[0])

    def search(self, query: str) -> Candidate:
        try:
            result = self._api().search(query, type_="track")
        except YandexMusicError as e:
            raise SourceError(f"Яндекс Музыка: {e}") from e
        if not result or not result.tracks or not result.tracks.results:
            raise SourceError(f"Яндекс Музыка: ничего не нашлось по «{query}»", 404)
        return self._candidate(result.tracks.results[0])

    def _candidate(self, track) -> Candidate:
        if not track.available:
            raise SourceError(f"Яндекс Музыка: «{track.title}» недоступен для прослушивания", 404)
        album_id = track.albums[0].id if track.albums else None
        title = track.title + (f" ({track.version})" if track.version else "")
        artist = ", ".join(a.name for a in track.artists if a.name) or "—"
        page = (
            f"https://music.yandex.ru/album/{album_id}/track/{track.id}"
            if album_id
            else f"https://music.yandex.ru/track/{track.id}"
        )
        return Candidate(
            source=self.id,
            native_id=str(track.id),
            title=title,
            artist=artist,
            duration_ms=int(track.duration_ms or 0),
            page_url=page,
            extra={"album_id": album_id},
        )

    def download(self, cand: Candidate, dest_dir: Path) -> Path:
        api = self._api()
        try:
            track = api.tracks([cand.native_id])[0]
            infos = [i for i in track.get_download_info() if i.codec == "mp3"]
            if not infos:
                raise SourceError("Яндекс Музыка: нет mp3 для этого трека")
            best = max(infos, key=lambda i: i.bitrate_in_kbps)
            if best.preview:
                # Без подписки отдают только 30 секунд — такой трек не нужен.
                raise SourceError("Яндекс Музыка отдаёт только отрывок: проверь подписку на аккаунте")
            path = dest_dir / "source.mp3"
            best.download(str(path))
            return path
        except YandexMusicError as e:
            raise SourceError(f"Яндекс Музыка: {e}") from e

    def report_played(self, cand: Candidate, seconds: float) -> None:
        album_id = cand.extra.get("album_id")
        if not album_id:
            return
        try:
            self._api().play_audio(
                track_id=cand.native_id,
                from_="desktop_win-search-track-default",
                album_id=album_id,
                play_id=str(uuid.uuid4()),
                track_length_seconds=int(cand.duration_ms / 1000),
                total_played_seconds=seconds,
                end_position_seconds=seconds,
            )
        except Exception:  # отметка — не повод ронять запрос
            log.warning("play_audio для %s не прошёл", cand.native_id, exc_info=True)
