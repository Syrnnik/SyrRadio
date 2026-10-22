"""Общий интерфейс площадки.

Площадка умеет три вещи: узнать трек по ссылке, найти трек по тексту и скачать
его в исходном формате. Перекодированием в ogg занимается сервис, площадке
об этом знать не нужно.
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path


class SourceError(Exception):
    """Ошибка, текст которой можно показать игроку.

    retry — есть ли смысл попробовать ещё раз. Ошибки запроса (4xx: не та
    ссылка, плейлист, трек не найден) не повторяются никогда.
    """

    def __init__(self, message: str, status: int = 502, retry: bool = True):
        super().__init__(message)
        self.message = message
        self.status = status
        self.retry = retry and status >= 500


@dataclass
class Candidate:
    source: str
    native_id: str
    title: str
    artist: str
    duration_ms: int
    page_url: str
    # Всё, что площадке понадобится при скачивании, чтобы не искать заново.
    extra: dict = field(default_factory=dict)

    def display(self) -> str:
        """«Исполнитель — название», или только название, если исполнителя нет."""
        return f"{self.artist} — {self.title}" if self.artist and self.artist != "—" else self.title


URL_RE = re.compile(r"^https?://", re.IGNORECASE)


def is_url(text: str) -> bool:
    return bool(URL_RE.match(text))


class Source(ABC):
    id: str
    name: str
    # Можно ли искать по тексту (иначе — только по ссылке).
    searchable: bool = True

    def available(self) -> bool:
        return True

    @abstractmethod
    def matches(self, url: str) -> bool: ...

    @abstractmethod
    def resolve_url(self, url: str) -> Candidate: ...

    def search(self, query: str) -> Candidate:
        raise SourceError(f"{self.name}: поиск по названию не поддерживается", 400)

    @abstractmethod
    def download(self, cand: Candidate, dest_dir: Path) -> Path:
        """Скачивает трек и возвращает путь к файлу в исходном формате."""

    def report_played(self, cand: Candidate, seconds: float) -> None:
        """Сообщает площадке, сколько трек реально играл. По умолчанию — никак."""
