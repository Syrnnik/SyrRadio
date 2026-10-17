"""Перекодирование в формат, который клиент Minecraft проигрывает сам."""

from __future__ import annotations

import functools
import json
import subprocess
from pathlib import Path

from . import config
from .sources.base import SourceError


def to_ogg(src: Path, dest: Path) -> int:
    """Перегоняет любой аудиофайл в ogg vorbis и возвращает длительность в мс.

    Громкость выравниваем loudnorm: треки с разных площадок свели по-разному,
    и без этого YouTube-трек может оказаться вдвое громче яндексовского.
    """
    tmp = dest.with_suffix(".part.ogg")
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(src),
        "-vn", "-map_metadata", "-1",
        "-ac", "2", "-ar", "48000",
        "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
        *_encoder_args(),
        str(tmp),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=300)
    except subprocess.CalledProcessError as e:
        tail = (e.stderr or b"").decode(errors="replace").strip().splitlines()[-1:] or ["?"]
        raise SourceError(f"ffmpeg не смог перекодировать трек: {tail[0][:200]}") from e
    except subprocess.TimeoutExpired as e:
        raise SourceError("ffmpeg перекодировал трек слишком долго") from e
    tmp.replace(dest)
    return probe_duration_ms(dest)


@functools.cache
def _encoder_args() -> tuple[str, ...]:
    """libvorbis, если ffmpeg собран с ним (в образе — да). Иначе встроенный
    экспериментальный кодер: хуже звучит, но годится для проверки на маке."""
    encoders = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    if "libvorbis" in encoders:
        return ("-c:a", "libvorbis", "-q:a", config.OGG_QUALITY)
    return ("-c:a", "vorbis", "-strict", "-2", "-b:a", "128k")


def probe_duration_ms(path: Path) -> int:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)],
        check=True, capture_output=True, timeout=30,
    ).stdout
    return int(float(json.loads(out)["format"]["duration"]) * 1000)
