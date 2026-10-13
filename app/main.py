from __future__ import annotations

import hashlib
import mimetypes
import os
import secrets
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from pydantic import BaseModel
from starlette.responses import StreamingResponse

MEDIA_ROOTS = {
    "movies": Path(os.getenv("MOVIES_ROOT", "/library/movies")).resolve(),
    "series": Path(os.getenv("SERIES_ROOT", "/library/series")).resolve(),
}
DATABASE_PATH = Path(os.getenv("DATABASE_PATH", "/data/zinema.sqlite3"))
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "")
CHUNK_SIZE = 1024 * 1024
VIDEO_EXTENSIONS = {
    ".avi", ".m2ts", ".m4v", ".mkv", ".mov", ".mp4", ".mpeg", ".mpg",
    ".mts", ".ts", ".vob", ".webm", ".wmv",
}

app = FastAPI(title="Zinema Server", version="0.1.0")


class LibraryItem(BaseModel):
    id: str
    library: str
    title: str
    relative_path: str
    extension: str
    size_bytes: int
    modified_at: str


class ScanResult(BaseModel):
    scanned: int
    marked_unavailable: int
    completed_at: str


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
    return LibraryItem(**dict(row))


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


@app.on_event("startup")
def initialize_database() -> None:
    with closing(database()) as connection:
        connection.commit()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/items", response_model=list[LibraryItem])
def list_items(
    q: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[LibraryItem]:
    sql = ("SELECT id, library, title, relative_path, extension, size_bytes, modified_at "
           "FROM library_items WHERE available = 1")
    parameters: list[object] = []
    if q:
        sql += " AND title LIKE ? ESCAPE '!' COLLATE NOCASE"
        escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        parameters.append(f"%{escaped}%")
    sql += " ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?"
    parameters.extend((limit, offset))
    with closing(database()) as connection:
        rows = connection.execute(sql, parameters).fetchall()
    return [item_from_row(row) for row in rows]


@app.get("/api/v1/items/{item_id}", response_model=LibraryItem)
def get_item(item_id: str) -> LibraryItem:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT id, library, title, relative_path, extension, size_bytes, modified_at "
            "FROM library_items WHERE id = ?",
            (item_id,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Item not found")
    return item_from_row(row)


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
    for library, root in MEDIA_ROOTS.items():
        if not root.is_dir():
            raise HTTPException(status_code=503, detail=f"{library.title()} library is not mounted")

    now = datetime.now(timezone.utc).isoformat()
    found: list[tuple[str, str, str, str, str, int, str, str]] = []
    for library, root in MEDIA_ROOTS.items():
        for path in root.rglob("*"):
            try:
                if path.is_symlink() or not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
                    continue
                stat = path.stat()
            except OSError:
                continue
            relative = path.relative_to(root).as_posix()
            item_id = hashlib.sha256(f"{library}:{relative}".encode()).hexdigest()[:32]
            found.append((item_id, library, display_title(path), relative, path.suffix.lower(),
                          stat.st_size, datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(), now))

    with closing(database()) as connection:
        connection.execute("BEGIN IMMEDIATE")
        connection.execute(
            "CREATE TEMP TABLE IF NOT EXISTS scan_paths (relative_path TEXT PRIMARY KEY)"
        )
        connection.execute("DELETE FROM scan_paths")
        connection.executemany(
            "INSERT INTO scan_paths (relative_path) VALUES (?)",
            [(f"{row[1]}:{row[3]}",) for row in found],
        )
        connection.executemany(
            """INSERT INTO library_items
               (id, library, title, relative_path, extension, size_bytes, modified_at, scanned_at, available)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
               ON CONFLICT(id) DO UPDATE SET
                 library=excluded.library, title=excluded.title,
                 relative_path=excluded.relative_path, extension=excluded.extension,
                 size_bytes=excluded.size_bytes, modified_at=excluded.modified_at,
                 scanned_at=excluded.scanned_at, available=1""",
            found,
        )
        unavailable = connection.execute(
            """UPDATE library_items SET available = 0
               WHERE available = 1 AND (library || ':' || relative_path) NOT IN
                 (SELECT relative_path FROM scan_paths)"""
        ).rowcount
        connection.commit()

    return ScanResult(scanned=len(found), marked_unavailable=unavailable, completed_at=now)
