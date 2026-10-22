"""HTTP-сервис, к которому ходит серверная часть мода SyrRadio.

Наружу он не открыт: слушает только внутри docker-сети mc-server_default.
"""

from __future__ import annotations

import hashlib
import logging
import shutil
import tempfile
import threading
import time
from dataclasses import asdict
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from . import audio, config
from .registry import SOURCES, source_for_url
from .sources.base import Candidate, SourceError, is_url

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("syrradio")

app = FastAPI(title="SyrRadio bridge", docs_url=None, redoc_url=None)

config.CACHE_DIR.mkdir(parents=True, exist_ok=True)

# Метаданные треков, которые уже резолвили: нужны для /audio и /played,
# чтобы не ходить на площадку второй раз.
_candidates: dict[str, Candidate] = {}
_key_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()


class UrlBody(BaseModel):
    url: str


class PlayedBody(BaseModel):
    url: str
    seconds: float


def require_token(authorization: str = Header(default="")) -> None:
    if not config.BRIDGE_TOKEN:
        raise HTTPException(503, "BRIDGE_TOKEN не задан")
    if authorization != f"Bearer {config.BRIDGE_TOKEN}":
        raise HTTPException(401, "Неверный токен")


@app.exception_handler(SourceError)
def _source_error(_: Request, e: SourceError):
    log.info("отказ: %s", e.message)
    return JSONResponse({"error": e.message}, status_code=e.status)


@app.exception_handler(HTTPException)
def _http_error(_: Request, e: HTTPException):
    return JSONResponse({"error": e.detail}, status_code=e.status_code)


@app.get("/health")
def health():
    return {"ok": True, "sources": [{"id": s.id, "name": s.name, "enabled": s.available()} for s in SOURCES]}


def _norm(url: str) -> str:
    url = url.strip()
    if not is_url(url):
        raise SourceError("Нужна ссылка на трек (https://...)", 400)
    return url


def _resolve(url: str) -> Candidate:
    cached = _candidates.get(url)
    if cached:
        return cached
    cand = source_for_url(url).resolve_url(url)
    if cand.duration_ms and cand.duration_ms > config.MAX_DURATION_SEC * 1000:
        limit = config.MAX_DURATION_SEC // 60
        raise SourceError(f"Трек длиннее {limit} минут, такие не берём", 400)
    _candidates[url] = cand
    return cand


def _track_json(cand: Candidate) -> dict:
    d = asdict(cand)
    d.pop("extra", None)
    return d


@app.post("/resolve", dependencies=[Depends(require_token)])
def resolve(body: UrlBody):
    url = _norm(body.url)
    cand = _resolve(url)
    log.info("resolve %s → %s (%s)", url, cand.display(), cand.source)
    return _track_json(cand)


def _lock_for(key: str) -> threading.Lock:
    with _locks_guard:
        return _key_locks.setdefault(key, threading.Lock())


@app.post("/audio", dependencies=[Depends(require_token)])
def get_audio(body: UrlBody):
    """Отдаёт трек в ogg. Первый запрос качает и перекодирует, повторные — из кэша."""
    url = _norm(body.url)
    cand = _resolve(url)
    key = hashlib.sha1(f"{cand.source}:{cand.native_id}".encode()).hexdigest()
    out = config.CACHE_DIR / f"{key}.ogg"

    with _lock_for(key):
        if not out.exists():
            started = time.monotonic()
            _download(url, cand, out)
            log.info(
                "готов %s: %.1f МБ за %.1f с",
                cand.display(), out.stat().st_size / 1e6, time.monotonic() - started,
            )
        out.touch()  # продлеваем жизнь в кэше

    duration = audio.probe_duration_ms(out)
    return FileResponse(out, media_type="audio/ogg", headers={"X-Duration-Ms": str(duration)})


def _download(url: str, cand: Candidate, out: Path) -> None:
    """Скачать и перекодировать с повторами: разовые отказы площадок лечатся второй попыткой."""
    attempts = config.DOWNLOAD_ATTEMPTS
    for attempt in range(1, attempts + 1):
        work = Path(tempfile.mkdtemp(prefix="dl-", dir=config.CACHE_DIR))
        try:
            src = source_for_url(url).download(cand, work)
            audio.to_ogg(src, out)
            return
        except SourceError as e:
            if not e.retry or attempt == attempts:
                if attempt > 1:
                    log.warning("не скачался %s за %d попыток: %s", cand.display(), attempt, e.message)
                raise
            log.warning(
                "попытка %d/%d для %s не удалась: %s; повтор через %.0f с",
                attempt, attempts, cand.display(), e.message, config.DOWNLOAD_RETRY_DELAY_SEC,
            )
        finally:
            shutil.rmtree(work, ignore_errors=True)
        time.sleep(config.DOWNLOAD_RETRY_DELAY_SEC)


@app.post("/played", dependencies=[Depends(require_token)])
def played(body: PlayedBody):
    cand = _candidates.get(body.url.strip())
    if cand and body.seconds >= 1:
        source_for_url(cand.page_url).report_played(cand, body.seconds)
    return {"ok": True}


def _janitor():
    """Удаляет старые ogg: это очередь, а не медиатека."""
    while True:
        cutoff = time.time() - config.CACHE_TTL_SEC
        for f in config.CACHE_DIR.iterdir():
            try:
                if f.stat().st_mtime < cutoff:
                    shutil.rmtree(f) if f.is_dir() else f.unlink()
            except FileNotFoundError:
                pass
        time.sleep(300)


threading.Thread(target=_janitor, daemon=True).start()
