from __future__ import annotations

import json
import mimetypes
import os
import secrets
import sqlite3
import threading
import time
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field
from starlette.responses import FileResponse, Response, StreamingResponse

from app.catalog import scan_catalog
from app.tmdb import apply_tmdb_id, enrich_pending

MEDIA_ROOTS = {
    "movies": Path(os.getenv("MOVIES_ROOT", "/library/movies")).resolve(),
    "series": Path(os.getenv("SERIES_ROOT", "/library/series")).resolve(),
}
DATABASE_PATH = Path(os.getenv("DATABASE_PATH", "/data/zinema.sqlite3"))
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "")
TMDB_ACCESS_TOKEN = os.getenv("TMDB_ACCESS_TOKEN", "")
TMDB_KEY = os.getenv("TMDB_KEY", "") if not TMDB_ACCESS_TOKEN else ""
TMDB_CONFIGURED = bool(TMDB_ACCESS_TOKEN or TMDB_KEY)
TMDB_LANGUAGE = os.getenv("TMDB_LANGUAGE", "es-ES")
TMDB_SYNC_INTERVAL_HOURS = max(1, int(os.getenv("TMDB_SYNC_INTERVAL_HOURS", "24")))
TMDB_SYNC_BATCH_SIZE = max(1, int(os.getenv("TMDB_SYNC_BATCH_SIZE", "100")))
TMDB_REQUEST_DELAY_SECONDS = max(0.0, float(os.getenv("TMDB_REQUEST_DELAY_SECONDS", "0.25")))
ARTWORK_DIR = DATABASE_PATH.parent / "artwork"
CHUNK_SIZE = 1024 * 1024
SYNC_LOCK = threading.Lock()
METADATA_RETRY_LOCK = threading.Lock()
METADATA_RETRY_LAST_REQUEST: dict[str, float] = {}
METADATA_RETRY_COOLDOWN_SECONDS = 600
METADATA_RETRY_GLOBAL_LAST_REQUEST = 0.0
METADATA_RETRY_GLOBAL_COOLDOWN_SECONDS = 10
SCHEDULER_STARTED = False

app = FastAPI(title="Zinema Server", version="0.1.0")


class LibraryItem(BaseModel):
    id: str
    library: str
    title: str
    relative_path: str
    extension: str
    size_bytes: int
    modified_at: str
    node_id: str | None = None
    series_id: str | None = None
    season_number: int | None = None
    episode_number: int | None = None
    season_count: int = 0
    metadata_title: str | None = None
    overview: str | None = None
    year: int | None = None
    genres: list[str] = Field(default_factory=list)
    poster_url: str | None = None


class ScanResult(BaseModel):
    scanned: int
    nodes: int = 0
    marked_unavailable: int
    completed_at: str


class SyncJob(BaseModel):
    id: str
    status: str
    stage: str
    started_at: str | None = None
    completed_at: str | None = None
    result: dict | None = None
    error: str | None = None


def database() -> sqlite3.Connection:
    DATABASE_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DATABASE_PATH, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS library_items (
            id TEXT PRIMARY KEY,
            library TEXT NOT NULL DEFAULT 'movies',
            title TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            extension TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            modified_at TEXT NOT NULL,
            scanned_at TEXT NOT NULL,
            available INTEGER NOT NULL DEFAULT 1,
            UNIQUE(library, relative_path)
        )
        """
    )
    columns = {row["name"] for row in connection.execute("PRAGMA table_info(library_items)")}
    if "library" not in columns:
        connection.execute(
            "ALTER TABLE library_items ADD COLUMN library TEXT NOT NULL DEFAULT 'movies'"
        )
    for name, sql_type in (
        ("node_id", "TEXT"), ("series_node_id", "TEXT"),
        ("season_number", "INTEGER"), ("episode_number", "INTEGER"),
    ):
        if name not in columns:
            connection.execute(f"ALTER TABLE library_items ADD COLUMN {name} {sql_type}")
    connection.execute(
        """CREATE TABLE IF NOT EXISTS catalog_nodes (
            id TEXT PRIMARY KEY,
            library TEXT NOT NULL,
            kind TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            parent_id TEXT,
            title TEXT NOT NULL,
            season_number INTEGER,
            source_year INTEGER,
            metadata_title TEXT,
            original_title TEXT,
            overview TEXT,
            year INTEGER,
            genres TEXT NOT NULL DEFAULT '[]',
            tmdb_id INTEGER,
            imdb_id TEXT,
            poster_file TEXT,
            backdrop_file TEXT,
            metadata_status TEXT NOT NULL DEFAULT 'pending',
            metadata_source TEXT,
            metadata_checked_at TEXT,
            metadata_candidates TEXT,
            metadata_error TEXT,
            scanned_at TEXT NOT NULL,
            available INTEGER NOT NULL DEFAULT 1,
            UNIQUE(library, kind, relative_path)
        )"""
    )
    connection.execute(
        "CREATE INDEX IF NOT EXISTS idx_catalog_parent ON catalog_nodes(library, parent_id, kind, title COLLATE NOCASE)"
    )
    connection.execute(
        """CREATE TABLE IF NOT EXISTS sync_runs (
            id TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            stage TEXT NOT NULL,
            started_at TEXT NOT NULL,
            completed_at TEXT,
            result TEXT,
            error TEXT
        )"""
    )
    connection.execute(
        "CREATE INDEX IF NOT EXISTS idx_library_title ON library_items(title COLLATE NOCASE)"
    )
    return connection


def require_admin(authorization: Annotated[str | None, Header()] = None) -> None:
    if not ADMIN_TOKEN:
        raise HTTPException(status_code=503, detail="Admin token is not configured")
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(token, ADMIN_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid bearer token")


def display_title(path: Path) -> str:
    return path.stem.replace(".", " ").replace("_", " ").strip()


def item_from_row(row: sqlite3.Row) -> LibraryItem:
    data = dict(row)
    data["genres"] = json.loads(data.pop("genres_json", "[]") or "[]")
    metadata_node_id = data.pop("metadata_node_id", None)
    poster_file = data.pop("poster_file", None)
    if metadata_node_id and poster_file:
        data["poster_url"] = f"/api/v1/catalog/{metadata_node_id}/images/poster"
    return LibraryItem(**data)


def _node_payload(row: sqlite3.Row, connection: sqlite3.Connection) -> dict:
    node = dict(row)
    genres = json.loads(node.pop("genres", "[]") or "[]")
    node["display_title"] = node.pop("metadata_title", None) or node["title"]
    node["genres"] = genres
    node["poster_available"] = bool(node.get("poster_file")) or node["kind"] == "season"
    node["poster_url"] = (
        f"/api/v1/catalog/{node['id']}/images/poster" if node["poster_available"] else None
    )
    node["backdrop_url"] = (
        f"/api/v1/catalog/{node['id']}/images/backdrop" if node.get("backdrop_file") else None
    )
    node.pop("metadata_candidates", None)
    node.pop("poster_file", None)
    node.pop("backdrop_file", None)
    node.pop("metadata_error", None)
    if node["kind"] == "collection":
        genre_rows = connection.execute(
            """WITH RECURSIVE descendants(id) AS (
                   SELECT id FROM catalog_nodes WHERE parent_id=? AND available=1
                   UNION ALL
                   SELECT child.id FROM catalog_nodes child
                   JOIN descendants parent ON child.parent_id=parent.id
                   WHERE child.available=1
               )
               SELECT genres FROM catalog_nodes
               WHERE id IN (SELECT id FROM descendants) AND genres!='[]'
               ORDER BY title COLLATE NOCASE""",
            (node["id"],),
        ).fetchall()
        aggregated_genres = []
        for genre_row in genre_rows:
            for genre in json.loads(genre_row["genres"] or "[]"):
                if genre not in aggregated_genres:
                    aggregated_genres.append(genre)
        node["genres"] = aggregated_genres

    child_rows = connection.execute(
        """SELECT id, kind, title, metadata_title, poster_file, season_number
           FROM catalog_nodes WHERE parent_id=? AND available=1
           ORDER BY CASE kind WHEN 'collection' THEN 0 WHEN 'series' THEN 1
                              WHEN 'season' THEN 2 ELSE 3 END,
                    title COLLATE NOCASE""",
        (node["id"],),
    ).fetchall()
    node["children"] = [
        {"id": child["id"], "kind": child["kind"],
         "title": child["metadata_title"] or child["title"],
         "season_number": child["season_number"],
         "poster_url": (f"/api/v1/catalog/{child['id']}/images/poster"
                        if child["poster_file"] or child["kind"] == "season" else None),
         "poster_available": bool(child["poster_file"]) or child["kind"] == "season"}
        for child in child_rows
    ]
    file_rows = connection.execute(
        """SELECT id, title, relative_path, extension, size_bytes, season_number,
                  episode_number
           FROM library_items WHERE node_id=? AND available=1
           ORDER BY season_number, episode_number, title COLLATE NOCASE""",
        (node["id"],),
    ).fetchall()
    node["files"] = [
        {**dict(media), "stream_url": f"/api/v1/items/{media['id']}/stream"}
        for media in file_rows
    ]
    node["mosaic_images"] = []
    if node["kind"] == "collection":
        artwork_rows = connection.execute(
            """WITH RECURSIVE descendants(id) AS (
                   SELECT id FROM catalog_nodes WHERE parent_id=? AND available=1
                   UNION ALL
                   SELECT child.id FROM catalog_nodes child
                   JOIN descendants parent ON child.parent_id=parent.id
                   WHERE child.available=1
               )
               SELECT id FROM catalog_nodes
               WHERE id IN (SELECT id FROM descendants) AND poster_file IS NOT NULL
               ORDER BY kind, title COLLATE NOCASE LIMIT 4""",
            (node["id"],),
        ).fetchall()
        node["mosaic_images"] = [
            f"/api/v1/catalog/{art['id']}/images/poster" for art in artwork_rows
        ]
    return node


def _item_select() -> str:
    return """SELECT li.id, li.library, li.title, li.relative_path, li.extension,
                     li.size_bytes, li.modified_at, li.node_id,
                     li.series_node_id AS series_id, li.season_number, li.episode_number,
                     n.metadata_title, n.overview, n.year, n.genres AS genres_json,
                     (SELECT COUNT(*) FROM catalog_nodes s
                      WHERE s.parent_id=n.id AND s.kind='season' AND s.available=1) AS season_count,
                     n.id AS metadata_node_id, n.poster_file
              FROM library_items li
              LEFT JOIN catalog_nodes n ON n.id=COALESCE(li.series_node_id, li.node_id)
              WHERE li.available=1"""


def get_media_file(item_id: str) -> tuple[Path, sqlite3.Row]:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT id, library, title, relative_path, extension, size_bytes, modified_at, available "
            "FROM library_items WHERE id = ?",
            (item_id,),
        ).fetchone()
    if row is None or not row["available"]:
        raise HTTPException(status_code=404, detail="Media file not found")
    root = MEDIA_ROOTS.get(row["library"])
    if root is None:
        raise HTTPException(status_code=404, detail="Media library not found")
    media_path = (root / row["relative_path"]).resolve()
    try:
        media_path.relative_to(root)
    except ValueError:
        raise HTTPException(status_code=404, detail="Media file not found") from None
    if not media_path.is_file():
        raise HTTPException(status_code=404, detail="Media file not found")
    return media_path, row


def file_chunks(path: Path, start: int, length: int):
    remaining = length
    with path.open("rb") as media:
        media.seek(start)
        while remaining:
            chunk = media.read(min(CHUNK_SIZE, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk


def _update_sync_job(job_id: str, **values: object) -> None:
    if not values:
        return
    assignments = ", ".join(f"{key}=?" for key in values)
    with closing(database()) as connection:
        connection.execute(
            f"UPDATE sync_runs SET {assignments} WHERE id=?",
            (*values.values(), job_id),
        )
        connection.commit()


def _run_sync_job(job_id: str) -> None:
    try:
        started = datetime.now(timezone.utc).isoformat()
        _update_sync_job(job_id, status="running", stage="scan", started_at=started)
        with closing(database()) as connection:
            scan_result = scan_catalog(connection, MEDIA_ROOTS)
        _update_sync_job(job_id, stage="metadata")
        metadata_result = {"matched": 0, "needs_review": 0, "not_found": 0,
                           "errors": 0, "processed": 0}
        while True:
            with closing(database()) as connection:
                batch = enrich_pending(
                    connection, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, ARTWORK_DIR,
                    batch_size=TMDB_SYNC_BATCH_SIZE,
                    minimum_delay=TMDB_REQUEST_DELAY_SECONDS,
                    api_key=TMDB_KEY,
                )
            for key, value in batch.items():
                metadata_result[key] += value
            _update_sync_job(job_id, stage=f"metadata:{metadata_result['processed']}")
            if batch["processed"] < TMDB_SYNC_BATCH_SIZE:
                break
        result = {"catalog": scan_result, "metadata": metadata_result}
        _update_sync_job(
            job_id, status="completed", stage="complete",
            completed_at=datetime.now(timezone.utc).isoformat(),
            result=json.dumps(result, ensure_ascii=False), error=None,
        )
    except Exception as exc:
        _update_sync_job(
            job_id, status="failed", stage="failed",
            completed_at=datetime.now(timezone.utc).isoformat(), error=str(exc)[:1000],
        )
    finally:
        SYNC_LOCK.release()


def _start_sync_job() -> str | None:
    if not TMDB_CONFIGURED or not SYNC_LOCK.acquire(blocking=False):
        return None
    job_id = uuid4().hex
    try:
        with closing(database()) as connection:
            connection.execute(
                "INSERT INTO sync_runs (id, status, stage, started_at) VALUES (?, 'queued', 'queued', ?)",
                (job_id, datetime.now(timezone.utc).isoformat()),
            )
            connection.commit()
        threading.Thread(target=_run_sync_job, args=(job_id,), daemon=True,
                         name=f"zinema-sync-{job_id[:8]}").start()
    except Exception:
        SYNC_LOCK.release()
        raise
    return job_id


def _metadata_retry_targets(connection: sqlite3.Connection, node_id: str) -> list[str]:
    node = connection.execute(
        "SELECT id, kind, parent_id FROM catalog_nodes WHERE id=? AND available=1",
        (node_id,),
    ).fetchone()
    if node is None:
        raise HTTPException(status_code=404, detail="Catalog node not found")
    if node["kind"] in {"movie", "series"}:
        return [node["id"]]
    if node["kind"] == "season":
        parent = connection.execute(
            "SELECT id FROM catalog_nodes WHERE id=? AND kind='series' AND available=1",
            (node["parent_id"],),
        ).fetchone()
        if parent is None:
            raise HTTPException(status_code=422, detail="Season has no available series parent")
        return [parent["id"]]
    if node["kind"] == "collection":
        rows = connection.execute(
            """WITH RECURSIVE descendants(id, kind) AS (
                   SELECT id, kind FROM catalog_nodes WHERE parent_id=? AND available=1
                   UNION ALL
                   SELECT child.id, child.kind FROM catalog_nodes child
                   JOIN descendants parent ON child.parent_id=parent.id
                   WHERE child.available=1
               )
               SELECT id FROM descendants WHERE kind IN ('movie', 'series')
               ORDER BY id""",
            (node_id,),
        ).fetchall()
        if rows:
            return [row["id"] for row in rows]
        raise HTTPException(status_code=422, detail="Collection has no movies or series to refresh")
    raise HTTPException(status_code=422, detail="This catalog item has no TMDb metadata")


def _run_metadata_retry_job(job_id: str, target_ids: list[str]) -> None:
    try:
        _update_sync_job(
            job_id, status="running", stage="retry:metadata:0",
            started_at=datetime.now(timezone.utc).isoformat(),
        )
        totals = {"matched": 0, "needs_review": 0, "not_found": 0,
                  "errors": 0, "processed": 0}
        for index, target_id in enumerate(target_ids, start=1):
            with closing(database()) as connection:
                connection.execute(
                    """UPDATE catalog_nodes SET metadata_status='pending',
                       metadata_checked_at=NULL, metadata_error=NULL,
                       metadata_candidates=NULL WHERE id=? AND available=1""",
                    (target_id,),
                )
                connection.commit()
                result = enrich_pending(
                    connection, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, ARTWORK_DIR,
                    batch_size=1, minimum_delay=TMDB_REQUEST_DELAY_SECONDS,
                    api_key=TMDB_KEY, node_id=target_id,
                )
            for key, count in result.items():
                totals[key] += count
            _update_sync_job(job_id, stage=f"retry:metadata:{index}/{len(target_ids)}")
        result_payload = {"metadata": totals, "requested_nodes": len(target_ids)}
        _update_sync_job(
            job_id, status="completed", stage="retry:complete",
            completed_at=datetime.now(timezone.utc).isoformat(),
            result=json.dumps(result_payload, ensure_ascii=False), error=None,
        )
    except Exception as exc:
        _update_sync_job(
            job_id, status="failed", stage="retry:failed",
            completed_at=datetime.now(timezone.utc).isoformat(), error=str(exc)[:1000],
        )
    finally:
        SYNC_LOCK.release()


def _start_metadata_retry_job(target_ids: list[str]) -> str | None:
    if not TMDB_CONFIGURED or not SYNC_LOCK.acquire(blocking=False):
        return None
    job_id = uuid4().hex
    try:
        with closing(database()) as connection:
            connection.execute(
                "INSERT INTO sync_runs (id, status, stage, started_at) VALUES (?, 'queued', 'retry:queued', ?)",
                (job_id, datetime.now(timezone.utc).isoformat()),
            )
            connection.commit()
        threading.Thread(
            target=_run_metadata_retry_job, args=(job_id, target_ids),
            daemon=True, name=f"zinema-metadata-retry-{job_id[:8]}",
        ).start()
    except Exception:
        SYNC_LOCK.release()
        raise
    return job_id


def _daily_sync_loop() -> None:
    interval = TMDB_SYNC_INTERVAL_HOURS * 3600
    while True:
        time.sleep(min(interval, 3600))
        if not TMDB_CONFIGURED or SYNC_LOCK.locked():
            continue
        with closing(database()) as connection:
            row = connection.execute(
                "SELECT completed_at FROM sync_runs WHERE status='completed' ORDER BY completed_at DESC LIMIT 1"
            ).fetchone()
        if row is None or not row["completed_at"]:
            continue
        try:
            last_sync = datetime.fromisoformat(row["completed_at"])
        except ValueError:
            continue
        if datetime.now(timezone.utc) - last_sync >= timedelta(seconds=interval):
            _start_sync_job()


def _sync_job_payload(row: sqlite3.Row) -> SyncJob:
    values = dict(row)
    values["result"] = json.loads(values["result"]) if values.get("result") else None
    return SyncJob(**values)


@app.on_event("startup")
def initialize_database() -> None:
    global SCHEDULER_STARTED
    with closing(database()) as connection:
        connection.commit()
    if not SCHEDULER_STARTED:
        threading.Thread(target=_daily_sync_loop, daemon=True, name="zinema-daily-sync").start()
        SCHEDULER_STARTED = True


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/items", response_model=list[LibraryItem])
def list_items(
    q: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[LibraryItem]:
    sql = _item_select()
    parameters: list[object] = []
    if q:
        sql += " AND (li.title LIKE ? ESCAPE '!' COLLATE NOCASE OR n.title LIKE ? ESCAPE '!' COLLATE NOCASE " \
               "OR n.metadata_title LIKE ? ESCAPE '!' COLLATE NOCASE)"
        escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        parameters.extend((f"%{escaped}%", f"%{escaped}%", f"%{escaped}%"))
    sql += " ORDER BY COALESCE(n.metadata_title, n.title, li.title) COLLATE NOCASE LIMIT ? OFFSET ?"
    parameters.extend((limit, offset))
    with closing(database()) as connection:
        rows = connection.execute(sql, parameters).fetchall()
    return [item_from_row(row) for row in rows]


@app.get("/api/v1/items/{item_id}", response_model=LibraryItem)
def get_item(item_id: str) -> LibraryItem:
    with closing(database()) as connection:
        row = connection.execute(
            _item_select().replace("WHERE li.available=1", "WHERE li.available=1 AND li.id=?"),
            (item_id,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Item not found")
    return item_from_row(row)


@app.get("/api/v1/catalog")
def list_catalog(
    library: str = Query(pattern="^(movies|series)$"),
    parent_id: str | None = None,
) -> list[dict]:
    with closing(database()) as connection:
        if parent_id is not None:
            parent = connection.execute(
                "SELECT id FROM catalog_nodes WHERE id=? AND library=? AND available=1",
                (parent_id, library),
            ).fetchone()
            if parent is None:
                raise HTTPException(status_code=404, detail="Catalog node not found")
            rows = connection.execute(
                "SELECT * FROM catalog_nodes WHERE library=? AND parent_id=? AND available=1 "
                "ORDER BY CASE kind WHEN 'collection' THEN 0 WHEN 'series' THEN 1 "
                "WHEN 'season' THEN 2 ELSE 3 END, title COLLATE NOCASE",
                (library, parent_id),
            ).fetchall()
        else:
            rows = connection.execute(
                "SELECT * FROM catalog_nodes WHERE library=? AND parent_id IS NULL AND available=1 "
                "ORDER BY CASE kind WHEN 'collection' THEN 0 WHEN 'series' THEN 1 "
                "WHEN 'season' THEN 2 ELSE 3 END, title COLLATE NOCASE",
                (library,),
            ).fetchall()
        return [_node_payload(row, connection) for row in rows]


@app.get("/api/v1/catalog/{node_id}")
def get_catalog_node(node_id: str) -> dict:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND available=1", (node_id,)
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        return _node_payload(row, connection)


@app.post("/api/v1/catalog/{node_id}/metadata/retry", status_code=202)
def retry_catalog_metadata(node_id: str) -> dict[str, object]:
    global METADATA_RETRY_GLOBAL_LAST_REQUEST
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
    with closing(database()) as connection:
        target_ids = _metadata_retry_targets(connection, node_id)
    now = time.monotonic()
    with METADATA_RETRY_LOCK:
        global_wait = METADATA_RETRY_GLOBAL_COOLDOWN_SECONDS - (
            now - METADATA_RETRY_GLOBAL_LAST_REQUEST
        )
        if global_wait > 0:
            raise HTTPException(
                status_code=429, detail="Metadata retry requests are temporarily rate limited",
                headers={"Retry-After": str(max(1, int(global_wait)))},
            )
        previous = METADATA_RETRY_LAST_REQUEST.get(node_id)
        if previous is not None and now - previous < METADATA_RETRY_COOLDOWN_SECONDS:
            retry_after = int(METADATA_RETRY_COOLDOWN_SECONDS - (now - previous))
            raise HTTPException(
                status_code=429, detail="Metadata was recently requested for this item",
                headers={"Retry-After": str(max(1, retry_after))},
            )
        job_id = _start_metadata_retry_job(target_ids)
        if job_id is None:
            raise HTTPException(status_code=409, detail="A catalog sync is already running")
        METADATA_RETRY_LAST_REQUEST[node_id] = now
        METADATA_RETRY_GLOBAL_LAST_REQUEST = now
    return {
        "id": job_id, "status": "queued", "items": len(target_ids),
        "status_url": f"/api/v1/catalog/metadata/retry/{job_id}",
    }


@app.get("/api/v1/catalog/metadata/retry/{job_id}")
def get_metadata_retry_status(job_id: str) -> SyncJob:
    with closing(database()) as connection:
        row = connection.execute("SELECT * FROM sync_runs WHERE id=?", (job_id,)).fetchone()
    if row is None or not row["stage"].startswith("retry:"):
        raise HTTPException(status_code=404, detail="Metadata retry job not found")
    return _sync_job_payload(row)


@app.get("/api/v1/catalog/{node_id}/images/{image_type}")
def get_catalog_image(node_id: str, image_type: str):
    if image_type not in {"poster", "backdrop"}:
        raise HTTPException(status_code=404, detail="Image type not found")
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT kind, season_number, poster_file, backdrop_file FROM catalog_nodes "
            "WHERE id=? AND available=1", (node_id,)
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Catalog node not found")
    filename = row["poster_file"] if image_type == "poster" else row["backdrop_file"]
    if filename:
        image_path = (ARTWORK_DIR / filename).resolve()
        try:
            image_path.relative_to(ARTWORK_DIR.resolve())
        except ValueError:
            raise HTTPException(status_code=404, detail="Image not found") from None
        if image_path.is_file():
            return FileResponse(image_path)
    if image_type == "poster" and row["kind"] == "season":
        season = int(row["season_number"] or 0)
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900">'
            '<defs><linearGradient id="g" x2="0" y2="1">'
            '<stop stop-color="#29334b"/><stop offset="1" stop-color="#111827"/>'
            '</linearGradient></defs><rect width="600" height="900" fill="url(#g)"/>'
            '<text x="300" y="390" text-anchor="middle" fill="#9ca3af" '
            'font-family="sans-serif" font-size="36">TEMPORADA</text>'
            f'<text x="300" y="550" text-anchor="middle" fill="white" '
            f'font-family="sans-serif" font-weight="700" font-size="180">{season:02d}</text>'
            '</svg>'
        )
        return Response(content=svg, media_type="image/svg+xml")
    raise HTTPException(status_code=404, detail="Image not found")


@app.post("/api/v1/admin/sync", status_code=202, dependencies=[Depends(require_admin)])
def start_catalog_sync() -> dict[str, str]:
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="Set TMDB_KEY or TMDB_ACCESS_TOKEN in .env first")
    job_id = _start_sync_job()
    if job_id is None:
        raise HTTPException(status_code=409, detail="A catalog sync is already running")
    return {"id": job_id, "status": "queued", "status_url": f"/api/v1/admin/sync/{job_id}"}


@app.get("/api/v1/admin/sync", dependencies=[Depends(require_admin)])
def latest_catalog_sync() -> SyncJob | dict[str, str]:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT * FROM sync_runs ORDER BY started_at DESC LIMIT 1"
        ).fetchone()
    return _sync_job_payload(row) if row else {"status": "never_run"}


@app.get("/api/v1/admin/sync/{job_id}", dependencies=[Depends(require_admin)])
def get_catalog_sync(job_id: str) -> SyncJob:
    with closing(database()) as connection:
        row = connection.execute("SELECT * FROM sync_runs WHERE id=?", (job_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Sync job not found")
    return _sync_job_payload(row)


@app.post("/api/v1/admin/catalog/{node_id}/match", dependencies=[Depends(require_admin)])
def set_catalog_match(node_id: str, tmdb_id: Annotated[int, Query(gt=0)]) -> dict[str, object]:
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="Set TMDB_KEY or TMDB_ACCESS_TOKEN in .env first")
    with closing(database()) as connection:
        node = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND kind IN ('movie', 'series') AND available=1",
            (node_id,),
        ).fetchone()
        if node is None:
            raise HTTPException(status_code=404, detail="Movie or series not found")
        try:
            apply_tmdb_id(connection, node, tmdb_id, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE,
                           ARTWORK_DIR, api_key=TMDB_KEY)
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"TMDb match failed: {exc}") from None
        updated = connection.execute("SELECT * FROM catalog_nodes WHERE id=?", (node_id,)).fetchone()
        return _node_payload(updated, connection)


@app.get("/api/v1/admin/metadata/review", dependencies=[Depends(require_admin)])
def metadata_review_queue() -> list[dict[str, object]]:
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT id, library, kind, title, source_year, metadata_candidates, metadata_error
               FROM catalog_nodes WHERE available=1 AND metadata_status='needs_review'
               ORDER BY library, title COLLATE NOCASE"""
        ).fetchall()
    return [
        {**{key: row[key] for key in ("id", "library", "kind", "title", "source_year", "metadata_error")},
         "candidates": json.loads(row["metadata_candidates"] or "[]")}
        for row in rows
    ]


@app.get("/api/v1/items/{item_id}/stream")
def stream_item(item_id: str, request: Request):
    path, row = get_media_file(item_id)
    file_size = path.stat().st_size
    range_header = request.headers.get("range")
    safe_filename = path.name.replace('"', "").replace("\r", "").replace("\n", "")
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Disposition": f'inline; filename="{safe_filename}"',
        "Cache-Control": "private, no-store",
    }
    media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"

    if range_header:
        unit, separator, value = range_header.partition("=")
        if separator != "=" or unit.strip().lower() != "bytes" or "," in value:
            raise HTTPException(status_code=416, detail="Only one byte range is supported",
                                headers={"Content-Range": f"bytes */{file_size}"})
        first, dash, last = value.strip().partition("-")
        if not dash:
            raise HTTPException(status_code=416, detail="Invalid byte range",
                                headers={"Content-Range": f"bytes */{file_size}"})
        try:
            if not first:
                suffix_length = int(last)
                if suffix_length <= 0 or file_size == 0:
                    raise ValueError
                start = max(0, file_size - suffix_length)
                end = file_size - 1
            else:
                start = int(first)
                end = min(int(last), file_size - 1) if last else file_size - 1
                if start < 0 or end < start or start >= file_size:
                    raise ValueError
        except ValueError:
            raise HTTPException(status_code=416, detail="Invalid byte range",
                                headers={"Content-Range": f"bytes */{file_size}"}) from None
        length = end - start + 1
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
        headers["Content-Length"] = str(length)
        return StreamingResponse(file_chunks(path, start, length), status_code=206,
                                 media_type=media_type, headers=headers)

    headers["Content-Length"] = str(file_size)
    return StreamingResponse(file_chunks(path, 0, file_size), media_type=media_type, headers=headers)


@app.post("/api/v1/admin/scan", response_model=ScanResult, dependencies=[Depends(require_admin)])
def scan_library() -> ScanResult:
    try:
        with closing(database()) as connection:
            result = scan_catalog(connection, MEDIA_ROOTS)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from None
    return ScanResult(**result)
