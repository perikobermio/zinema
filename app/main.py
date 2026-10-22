from __future__ import annotations

import json
import hashlib
import mimetypes
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
import threading
import time
import unicodedata
import codecs
from difflib import SequenceMatcher
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
import httpx
from pydantic import BaseModel, Field
from pwdlib import PasswordHash
from starlette.responses import FileResponse, Response, StreamingResponse

from app.catalog import EPISODE_RE, VIDEO_EXTENSIONS, scan_catalog
from app.telegram import send_telegram_message
from app.tmdb import (apply_tmdb_id, enrich_pending, get_candidate_details,
                      get_movie_age_limits, get_similar_content, search_content_requests,
                      search_image_options, search_metadata_candidates,
                      _image_config, _save_image, normalize_genres)
from app.video_thumbnails import extract_episode_thumbnails

MEDIA_ROOTS = {
    "movies": Path(os.getenv("MOVIES_ROOT", "/library/movies")).resolve(),
    "series": Path(os.getenv("SERIES_ROOT", "/library/series")).resolve(),
}
INCOMING_ROOT = Path(os.getenv("INCOMING_ROOT", "/incoming")).resolve()
MEDIA_DISPLAY_ROOTS = {
    "movies": Path(os.getenv("MOVIES_DISPLAY_ROOT", "/d/NAS/Pelikulek")).resolve(),
    "series": Path(os.getenv("SERIES_DISPLAY_ROOT", "/d/NAS/Serijek")).resolve(),
}
INCOMING_DISPLAY_ROOT = Path(os.getenv("INCOMING_DISPLAY_ROOT", "/d/NAS/Incoming")).resolve()
NAS_SCRIPTS_ROOT = Path(os.getenv("NAS_SCRIPTS_ROOT", "/d/NAS/scripts")).resolve()
NAS_DOWNLOADER_SCRIPT = NAS_SCRIPTS_ROOT / "telegram_download.py"
DATABASE_PATH = Path(os.getenv("DATABASE_PATH", "/data/zinema.sqlite3"))
TMDB_ACCESS_TOKEN = os.getenv("TMDB_ACCESS_TOKEN", "")
TMDB_KEY = os.getenv("TMDB_KEY", "") if not TMDB_ACCESS_TOKEN else ""
TMDB_CONFIGURED = bool(TMDB_ACCESS_TOKEN or TMDB_KEY)
TMDB_LANGUAGE = os.getenv("TMDB_LANGUAGE", "es-ES")
TMDB_SYNC_INTERVAL_HOURS = max(1, int(os.getenv("TMDB_SYNC_INTERVAL_HOURS", "24")))
TMDB_SYNC_BATCH_SIZE = max(1, int(os.getenv("TMDB_SYNC_BATCH_SIZE", "100")))
TMDB_REQUEST_DELAY_SECONDS = max(0.0, float(os.getenv("TMDB_REQUEST_DELAY_SECONDS", "0.25")))
ARTWORK_DIR = DATABASE_PATH.parent / "artwork"
CLIENT_UPDATE_DIR = DATABASE_PATH.parent / "client-update"
CHUNK_SIZE = 1024 * 1024
SYNC_LOCK = threading.Lock()
SYNC_RETRY_LOCK = threading.Lock()
SYNC_RETRY_SCHEDULED = False
INCOMING_MOVE_LOCK = threading.Lock()
NAS_DOWNLOAD_LOCK = threading.Lock()
INCOMING_SCAN_LOCK = threading.Lock()
INCOMING_ACTIVITY_LOCK = threading.Lock()
INCOMING_MONITOR_WAKE = threading.Event()
INCOMING_ACTIVITY_SNAPSHOT: dict[str, dict[str, object]] = {}
INCOMING_ACTIVE_PATHS: set[str] = set()
INCOMING_MONITOR_STARTED = False
INCOMING_MONITOR_SETTLE_SECONDS = 18.0
INCOMING_MONITOR_IDLE_INTERVAL_SECONDS = 60.0
INCOMING_MONITOR_ACTIVE_INTERVAL_SECONDS = 1.0
NAS_DOWNLOAD_STATE: dict[str, object] = {
    "id": None, "status": "idle", "progress": "", "current_file": None, "started_at": None,
    "current_title": None, "current_percent": None, "downloaded_bytes": 0,
    "total_bytes": 0, "current_bytes": 0, "current_path": None, "source": None,
    "completed_at": None, "exit_code": None, "error": None,
}
METADATA_RETRY_LOCK = threading.Lock()
METADATA_RETRY_LAST_REQUEST: dict[str, float] = {}
METADATA_RETRY_COOLDOWN_SECONDS = 60
METADATA_RETRY_GLOBAL_LAST_REQUEST = 0.0
METADATA_RETRY_GLOBAL_COOLDOWN_SECONDS = 10
SCHEDULER_STARTED = False
PLAYBACK_TICKET_TTL_SECONDS = 8 * 60 * 60
PLAYBACK_TICKETS: dict[str, tuple[str, float, str]] = {}
PLAYBACK_TICKET_LOCK = threading.Lock()
EPISODE_THUMBNAIL_LOCK = threading.Lock()
EPISODE_THUMBNAIL_RUNNING = False
SEASON_ART_CACHE_LOCK = threading.Lock()
SEASON_ART_CACHE: dict[tuple[int, str], tuple[float, list[dict[str, object]]]] = {}
SEASON_ART_CACHE_TTL_SECONDS = 6 * 60 * 60

app = FastAPI(title="Zinema Server", version="0.1.0",
              docs_url=None, redoc_url=None, openapi_url=None)


class LibraryItem(BaseModel):
    id: str
    library: str
    title: str
    filename: str | None = None
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
    age_limit: int | None = None
    age_rating: str | None = None
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


class ManualMetadataRequest(BaseModel):
    metadata_title: str = Field(min_length=1, max_length=300)
    overview: str = Field(default="", max_length=10000)
    year: int | None = Field(default=None, ge=1800, le=2200)
    genres: list[str] = Field(default_factory=list, max_length=20)
    image_path: str | None = Field(default=None, max_length=500)
    image_type: str = Field(default="poster", pattern="^(poster|backdrop)$")
    tmdb_id: int | None = Field(default=None, gt=0)


class ContentRequestItem(BaseModel):
    content_type: str = Field(pattern="^(movie|tv)$")
    tmdb_id: int = Field(gt=0)
    season_number: int | None = Field(default=None, ge=1)


class ContentRequestCreate(BaseModel):
    request_type: str = Field(pattern="^(new_content|remaining_seasons)$")
    content_type: str | None = Field(default=None, pattern="^(movie|tv)$")
    tmdb_id: int | None = Field(default=None, gt=0)
    season_number: int | None = Field(default=None, ge=1)
    catalog_node_id: str | None = Field(default=None, max_length=100)
    items: list[ContentRequestItem] = Field(default_factory=list, max_length=20)


class IncomingGenerateRequest(BaseModel):
    destination_path: str = Field(min_length=1, max_length=2000)


class UserProfileRequest(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=80)
    avatar: str = Field(default="generic:0", max_length=1500)
    is_kid: bool = False


class ActiveProfileRequest(BaseModel):
    profile_id: str = Field(min_length=1, max_length=100)


class AccountCredentials(BaseModel):
    username: str | None = Field(default=None, min_length=3, max_length=254)
    email: str | None = Field(default=None, min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=256)
    device_name: str = Field(default="Zinema client", min_length=1, max_length=120)
    device_id: str | None = Field(default=None, min_length=1, max_length=100)


class AccountCreateRequest(AccountCredentials):
    email: str = Field(min_length=3, max_length=254)
    is_admin: bool = False


password_hash = PasswordHash.recommended()


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
        ("episode_title", "TEXT"), ("poster_file", "TEXT"),
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
            age_limit INTEGER,
            age_rating TEXT,
            age_rating_checked_at TEXT,
            cast_json TEXT,
            cast_version INTEGER NOT NULL DEFAULT 0,
            tmdb_id INTEGER,
            imdb_id TEXT,
            poster_file TEXT,
            backdrop_file TEXT,
            metadata_status TEXT NOT NULL DEFAULT 'pending',
            metadata_source TEXT,
            metadata_checked_at TEXT,
            metadata_candidates TEXT,
            metadata_error TEXT,
            episode_metadata_checked_at TEXT,
            tmdb_episodes_json TEXT,
            scanned_at TEXT NOT NULL,
            added_at TEXT NOT NULL DEFAULT '',
            available INTEGER NOT NULL DEFAULT 1,
            UNIQUE(library, kind, relative_path)
        )"""
    )
    node_columns = {row["name"] for row in connection.execute("PRAGMA table_info(catalog_nodes)")}
    if "episode_metadata_checked_at" not in node_columns:
        connection.execute("ALTER TABLE catalog_nodes ADD COLUMN episode_metadata_checked_at TEXT")
    if "tmdb_episodes_json" not in node_columns:
        connection.execute("ALTER TABLE catalog_nodes ADD COLUMN tmdb_episodes_json TEXT")
    for name, sql_type in (("age_limit", "INTEGER"), ("age_rating", "TEXT"),
                           ("age_rating_checked_at", "TEXT")):
        if name not in node_columns:
            connection.execute(f"ALTER TABLE catalog_nodes ADD COLUMN {name} {sql_type}")
    if "cast_json" not in node_columns:
        connection.execute("ALTER TABLE catalog_nodes ADD COLUMN cast_json TEXT")
    if "cast_version" not in node_columns:
        connection.execute("ALTER TABLE catalog_nodes ADD COLUMN cast_version INTEGER NOT NULL DEFAULT 0")
    if "added_at" not in node_columns:
        connection.execute("ALTER TABLE catalog_nodes ADD COLUMN added_at TEXT NOT NULL DEFAULT ''")
    connection.execute("UPDATE catalog_nodes SET added_at=scanned_at WHERE added_at='' ")
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
    connection.execute(
        """CREATE TABLE IF NOT EXISTS user_profiles (
               id TEXT PRIMARY KEY,
               user_id TEXT,
               name TEXT NOT NULL,
               avatar TEXT NOT NULL DEFAULT 'generic:0',
               is_kid INTEGER NOT NULL DEFAULT 0,
               created_at TEXT NOT NULL,
               updated_at TEXT NOT NULL,
               deleted INTEGER NOT NULL DEFAULT 0
           )"""
    )
    profile_columns = {row["name"] for row in connection.execute("PRAGMA table_info(user_profiles)")}
    if "user_id" not in profile_columns:
        connection.execute("ALTER TABLE user_profiles ADD COLUMN user_id TEXT")
    if "deleted" not in profile_columns:
        connection.execute("ALTER TABLE user_profiles ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    if "is_kid" not in profile_columns:
        connection.execute("ALTER TABLE user_profiles ADD COLUMN is_kid INTEGER NOT NULL DEFAULT 0")
    connection.execute("CREATE INDEX IF NOT EXISTS idx_profiles_user ON user_profiles(user_id, deleted)")
    connection.execute(
        """CREATE TABLE IF NOT EXISTS profile_state (
               profile_id TEXT PRIMARY KEY,
               state_json TEXT NOT NULL DEFAULT '{}',
               revision INTEGER NOT NULL DEFAULT 0,
               updated_at TEXT NOT NULL
           )"""
    )
    connection.execute(
        """CREATE TABLE IF NOT EXISTS accounts (
               id TEXT PRIMARY KEY,
               email TEXT NOT NULL UNIQUE COLLATE NOCASE,
               username TEXT,
               password_hash TEXT NOT NULL,
               is_admin INTEGER NOT NULL DEFAULT 0,
               created_at TEXT NOT NULL,
               disabled INTEGER NOT NULL DEFAULT 0,
               approved INTEGER NOT NULL DEFAULT 0
           )"""
    )
    connection.execute(
        """CREATE TABLE IF NOT EXISTS content_requests (
               id TEXT PRIMARY KEY,
               requester_account_id TEXT NOT NULL,
               requester_name TEXT NOT NULL,
               request_type TEXT NOT NULL,
               content_type TEXT NOT NULL,
               tmdb_id INTEGER,
               title TEXT NOT NULL,
               year INTEGER,
               season_number INTEGER,
               catalog_node_id TEXT,
               details_json TEXT NOT NULL DEFAULT '{}',
               status TEXT NOT NULL DEFAULT 'pending',
               created_at TEXT NOT NULL,
               reviewed_at TEXT,
               reviewed_by_account_id TEXT,
               telegram_sent_at TEXT,
               telegram_message_id INTEGER,
               telegram_error TEXT
           )"""
    )
    request_columns = {row["name"] for row in connection.execute("PRAGMA table_info(content_requests)")}
    for name in ("telegram_sent_at", "telegram_message_id", "telegram_error"):
        if name not in request_columns:
            column_type = "INTEGER" if name == "telegram_message_id" else "TEXT"
            connection.execute(f"ALTER TABLE content_requests ADD COLUMN {name} {column_type}")
    connection.execute(
        "CREATE INDEX IF NOT EXISTS idx_content_requests_status_created "
        "ON content_requests(status, created_at)"
    )
    account_columns = {row["name"] for row in connection.execute("PRAGMA table_info(accounts)")}
    if "approved" not in account_columns:
        connection.execute("ALTER TABLE accounts ADD COLUMN approved INTEGER NOT NULL DEFAULT 0")
    if "username" not in account_columns:
        connection.execute("ALTER TABLE accounts ADD COLUMN username TEXT")
    connection.execute("UPDATE accounts SET username=email WHERE username IS NULL OR username='' ")
    connection.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_username ON accounts(username COLLATE NOCASE)")
    connection.execute("CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    episode_art_migration = connection.execute(
        "SELECT value FROM app_settings WHERE key='episode_still_artwork_v1'"
    ).fetchone()
    if episode_art_migration is None:
        connection.execute(
            "UPDATE catalog_nodes SET episode_metadata_checked_at=NULL "
            "WHERE kind='series' AND tmdb_id IS NOT NULL"
        )
        connection.execute(
            "INSERT INTO app_settings(key, value) VALUES ('episode_still_artwork_v1', '1')"
        )
    episode_type_migration = connection.execute(
        "SELECT value FROM app_settings WHERE key='episode_type_completion_v1'"
    ).fetchone()
    if episode_type_migration is None:
        connection.execute(
            "UPDATE catalog_nodes SET episode_metadata_checked_at=NULL "
            "WHERE kind='series' AND tmdb_id IS NOT NULL"
        )
        connection.execute(
            "INSERT INTO app_settings(key, value) VALUES ('episode_type_completion_v1', '1')"
        )
    episode_completion_v2_migration = connection.execute(
        "SELECT value FROM app_settings WHERE key='episode_batch_completion_v1'"
    ).fetchone()
    if episode_completion_v2_migration is None:
        connection.execute(
            "UPDATE catalog_nodes SET episode_metadata_checked_at=NULL "
            "WHERE kind='series' AND tmdb_id IS NOT NULL"
        )
        connection.execute(
            "INSERT INTO app_settings(key, value) VALUES ('episode_batch_completion_v1', '1')"
        )
    approval_migration = connection.execute(
        "SELECT value FROM app_settings WHERE key='account_approval_enforced_v1'"
    ).fetchone()
    if approval_migration is None:
        # Old server versions treated every account as approved. Require the
        # administrator to review standard accounts once when enforcing approvals.
        connection.execute("UPDATE accounts SET approved=0 WHERE is_admin=0")
        connection.execute("UPDATE accounts SET approved=1 WHERE is_admin=1")
        connection.execute(
            "INSERT INTO app_settings(key, value) VALUES ('account_approval_enforced_v1', '1')"
        )
    connection.execute(
        """CREATE TABLE IF NOT EXISTS client_devices (
               id TEXT PRIMARY KEY,
               user_id TEXT,
               name TEXT NOT NULL,
               token_hash TEXT NOT NULL UNIQUE,
               created_at TEXT NOT NULL,
               last_seen_at TEXT,
               revoked INTEGER NOT NULL DEFAULT 0
           )"""
    )
    device_columns = {row["name"] for row in connection.execute("PRAGMA table_info(client_devices)")}
    if "user_id" not in device_columns:
        connection.execute("ALTER TABLE client_devices ADD COLUMN user_id TEXT")
    if "active_profile_id" not in device_columns:
        connection.execute("ALTER TABLE client_devices ADD COLUMN active_profile_id TEXT")
    if "active_profile_updated_at" not in device_columns:
        connection.execute("ALTER TABLE client_devices ADD COLUMN active_profile_updated_at TEXT")
    connection.commit()
    return connection


def require_client(authorization: Annotated[str | None, Header()] = None) -> str:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(status_code=401, detail="Invalid device token")
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with closing(database()) as connection:
        row = connection.execute(
            """SELECT d.id FROM client_devices d JOIN accounts a ON a.id=d.user_id
               WHERE d.token_hash=? AND d.revoked=0 AND a.disabled=0
                 AND (a.approved=1 OR a.is_admin=1)""",
            (token_hash,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=401, detail="Invalid or revoked device token")
        connection.execute("UPDATE client_devices SET last_seen_at=? WHERE id=?",
                           (datetime.now(timezone.utc).isoformat(), row["id"]))
        connection.commit()
        return row["id"]


def require_admin(device_id: Annotated[str, Depends(require_client)]) -> str:
    with closing(database()) as connection:
        row = connection.execute(
            """SELECT a.id FROM client_devices d JOIN accounts a ON a.id=d.user_id
               WHERE d.id=? AND d.revoked=0 AND a.disabled=0 AND a.is_admin=1""",
            (device_id,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=403, detail="Administrator account required")
    return device_id


def account_for_device(device_id: str) -> str:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT user_id FROM client_devices WHERE id=? AND revoked=0", (device_id,),
        ).fetchone()
    if row is None or not row["user_id"]:
        raise HTTPException(status_code=401, detail="Device is not linked to an account")
    return row["user_id"]


def issue_device_token(connection: sqlite3.Connection, user_id: str,
                       name: str, device_id: str | None) -> dict[str, str]:
    device_id = device_id or uuid4().hex
    token = secrets.token_urlsafe(48)
    now = datetime.now(timezone.utc).isoformat()
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    connection.execute(
        """INSERT INTO client_devices(id, user_id, name, token_hash, created_at, last_seen_at, revoked)
           VALUES (?, ?, ?, ?, ?, ?, 0)
           ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id, name=excluded.name,
               token_hash=excluded.token_hash, created_at=excluded.created_at,
               last_seen_at=excluded.last_seen_at, revoked=0""",
        (device_id, user_id, name.strip(), token_hash, now, now),
    )
    return {"device_id": device_id, "token": token}


def display_title(path: Path) -> str:
    return path.stem.replace(".", " ").replace("_", " ").strip()


def _artwork_url(node_id: str, image_type: str, filename: str | None = None,
                 fallback_version: str = "") -> str:
    version = fallback_version or "0"
    if filename:
        image_path = ARTWORK_DIR / filename
        try:
            stat = image_path.stat()
            version = f"{stat.st_mtime_ns:x}-{stat.st_size:x}"
        except OSError:
            version = hashlib.sha256(filename.encode("utf-8")).hexdigest()[:16]
    return f"/api/v1/catalog/{node_id}/images/{image_type}?v={version}"


def _episode_poster_url(item_id: str, poster_file: str, modified_at: str = "") -> str:
    version = hashlib.sha256(f"{poster_file}:{modified_at}".encode("utf-8")).hexdigest()[:12]
    return f"/api/v1/items/{item_id}/poster?v={version}"


def _catalog_request_placeholder(request: sqlite3.Row | dict, kind: str,
                                 season_number: int | None = None,
                                 parent_id: str | None = None) -> dict[str, object]:
    details = json.loads(request["details_json"] or "{}")
    title = str(request["title"] or "").strip()
    if kind == "season":
        title = f"Temporada {season_number:02d}" if season_number else "Capítulos pendientes"
    poster_path = details.get("poster_path")
    poster_url = details.get("poster_url")
    if not poster_url and isinstance(poster_path, str) and poster_path.startswith("/"):
        poster_url = f"https://image.tmdb.org/t/p/w500{poster_path}"
    return {
        "id": f"request:{request['id']}:{season_number or ''}",
        "kind": kind,
        "library": "movies" if request["content_type"] == "movie" else "series",
        "parent_id": parent_id,
        "title": title,
        "display_title": title,
        "year": request["year"],
        "tmdb_id": request["tmdb_id"],
        "season_number": season_number,
        "request_type": request["request_type"],
        "request_status": request["status"],
        "is_placeholder": True,
        "poster_url": poster_url,
        "poster_available": bool(poster_url),
        "genres": [],
        "children": [],
        "files": [],
    }


def _pending_season_placeholders(connection: sqlite3.Connection,
                                 series_id: str) -> list[dict[str, object]]:
    series = connection.execute(
        "SELECT tmdb_id FROM catalog_nodes WHERE id=? AND kind='series' AND available=1",
        (series_id,),
    ).fetchone()
    if series is None:
        return []
    requests = connection.execute(
        """SELECT * FROM content_requests WHERE status='approved' AND (
               (request_type='remaining_seasons' AND catalog_node_id=?) OR
               (request_type='new_content' AND content_type='tv' AND tmdb_id=?
                AND season_number IS NOT NULL))
           ORDER BY created_at, id""",
        (series_id, series["tmdb_id"]),
    ).fetchall()
    if not requests:
        return []
    existing = {row["season_number"] for row in connection.execute(
        "SELECT season_number FROM catalog_nodes WHERE parent_id=? AND kind='season' AND available=1",
        (series_id,),
    ).fetchall()}
    placeholders: dict[int | None, dict[str, object]] = {}
    for request in requests:
        details = json.loads(request["details_json"] or "{}")
        if request["request_type"] == "remaining_seasons":
            missing = details.get("missing_seasons") or []
            requested_seasons = [int(value) for value in missing]
        else:
            requested_seasons = [int(request["season_number"])]
        for number in requested_seasons:
            if number is not None and number in existing:
                continue
            placeholders.setdefault(number, _catalog_request_placeholder(
                request, "season", number, series_id,
            ))
    return sorted(placeholders.values(), key=lambda item: (
        item.get("season_number") is None, item.get("season_number") or 0,
        str(item["title"]).casefold(),
    ))


def item_from_row(row: sqlite3.Row, connection: sqlite3.Connection) -> LibraryItem:
    data = dict(row)
    data["genres"] = normalize_genres(json.loads(data.pop("genres_json", "[]") or "[]"))
    metadata_node_id = data.pop("metadata_node_id", None)
    metadata_parent_id = data.pop("metadata_parent_id", None)
    poster_file = data.pop("poster_file", None)
    episode_poster_file = data.pop("episode_poster_file", None)
    if not data["genres"]:
        inherited_genres = _inherited_collection_value(connection, metadata_parent_id, "genres")
        if inherited_genres:
            data["genres"] = normalize_genres(json.loads(inherited_genres[1] or "[]"))
    if episode_poster_file:
        data["poster_url"] = _episode_poster_url(
            data["id"], episode_poster_file, data.get("modified_at", ""),
        )
    elif metadata_node_id and poster_file:
        data["poster_url"] = _artwork_url(metadata_node_id, "poster", poster_file)
    return LibraryItem(**data)


def _inherited_collection_value(connection: sqlite3.Connection, parent_id: str | None,
                                column: str) -> tuple[str, str] | None:
    if column != "genres":
        return None
    while parent_id:
        parent = connection.execute(
            f"SELECT id, kind, parent_id, {column} AS value FROM catalog_nodes "
            "WHERE id=? AND available=1", (parent_id,),
        ).fetchone()
        if parent is None:
            return None
        if parent["kind"] == "collection" and parent["value"]:
            if column != "genres" or parent["value"] != "[]":
                return parent["id"], parent["value"]
        parent_id = parent["parent_id"]
    return None


def _series_fallback_poster(connection: sqlite3.Connection, series_id: str) -> tuple[str, str | None, str] | None:
    season = connection.execute(
        """SELECT id, season_number, poster_file FROM catalog_nodes
           WHERE parent_id=? AND kind='season' AND available=1
           ORDER BY season_number LIMIT 1""",
        (series_id,),
    ).fetchone()
    if season is None:
        return None
    return (season["id"], season["poster_file"], f"season-{season['season_number']}")


def _collection_artwork(connection: sqlite3.Connection, collection_id: str) -> dict:
    items = connection.execute(
        """WITH RECURSIVE descendants(id, parent_id, kind, poster_file, title) AS (
               SELECT id, parent_id, kind, poster_file, title
               FROM catalog_nodes WHERE parent_id=? AND available=1
               UNION ALL
               SELECT child.id, child.parent_id, child.kind, child.poster_file, child.title
               FROM catalog_nodes child
               JOIN descendants parent ON child.parent_id=parent.id
               WHERE child.available=1
           )
           SELECT id, parent_id, kind, poster_file, title FROM descendants
           WHERE kind IN ('movie', 'series') ORDER BY kind, title COLLATE NOCASE""",
        (collection_id,),
    ).fetchall()
    mosaic: list[str] = []
    for item in items:
        if item["poster_file"]:
            mosaic.append(_artwork_url(item["id"], "poster", item["poster_file"]))
        elif item["kind"] == "series":
            # A series may only have artwork attached to its season nodes.
            # Use the first season's poster (or its numbered fallback tile) so
            # collections of series fill the mosaic just like movie collections.
            season = _series_fallback_poster(connection, item["id"])
            if season:
                mosaic.append(_artwork_url(
                    season[0], "poster", season[1], fallback_version=season[2],
                ))
        if len(mosaic) == 4:
            break
    if len(mosaic) == 3:
        collection = connection.execute(
            "SELECT poster_file FROM catalog_nodes WHERE id=?", (collection_id,),
        ).fetchone()
        if collection and collection["poster_file"]:
            mosaic.append(_artwork_url(collection_id, "poster", collection["poster_file"]))
    single_poster_url = None
    if len(items) == 1:
        item = items[0]
        poster_node_id = item["id"] if item["poster_file"] else None
        poster_filename = item["poster_file"]
        fallback_version = ""
        if poster_node_id is None and item["kind"] == "series":
            season = _series_fallback_poster(connection, item["id"])
            if season:
                poster_node_id, poster_filename, fallback_version = season
        if poster_node_id:
            if poster_node_id == item["id"]:
                poster_filename = item["poster_file"]
            single_poster_url = _artwork_url(
                poster_node_id, "poster", poster_filename,
                fallback_version=fallback_version,
            )
    return {"item_count": len(items), "mosaic_images": mosaic,
            "single_item_poster_url": single_poster_url}


def _series_completion(connection: sqlite3.Connection, series_id: str,
                       expected_raw: str | None) -> dict[str, object]:
    known = expected_raw is not None
    missing_episodes: list[dict[str, object]] = []
    missing_seasons: list[int] = []
    if known:
        expected = json.loads(expected_raw or "[]")
        present_rows = connection.execute(
            """SELECT DISTINCT season_number, episode_number FROM library_items
               WHERE series_node_id=? AND available=1
                 AND season_number IS NOT NULL AND episode_number IS NOT NULL""",
            (series_id,),
        ).fetchall()
        present = {(int(item["season_number"]), int(item["episode_number"]))
                   for item in present_rows}
        expected_by_season: dict[int, list[dict]] = {}
        for episode in expected:
            if str(episode.get("episode_type") or "").strip().lower() in {"special", "reunion"}:
                continue
            season_number = int(episode["season_number"])
            episode_number = int(episode["episode_number"])
            expected_by_season.setdefault(season_number, []).append({
                "episode_number": episode_number,
                "air_date": str(episode.get("air_date") or "")[:10],
            })

        # Some services add a one-off bonus after releasing a complete season as
        # a batch (e.g. The Sandman S1E11). Do not make a fully present season
        # incomplete solely because of that delayed, trailing episode.
        trailing_bonus_episodes: set[tuple[int, int]] = set()
        for season_number, episodes in expected_by_season.items():
            episodes.sort(key=lambda value: value["episode_number"])
            numbers = [value["episode_number"] for value in episodes]
            if len(numbers) < 6 or numbers != list(range(1, numbers[-1] + 1)):
                continue
            final_number = numbers[-1]
            if (season_number, final_number) in present or any(
                    (season_number, number) not in present for number in numbers[:-1]):
                continue
            previous_dates = [value["air_date"] for value in episodes[:-1]
                              if value["air_date"]]
            final_date = episodes[-1]["air_date"]
            if not final_date or len(previous_dates) < 5:
                continue
            common_date = max(set(previous_dates), key=previous_dates.count)
            if previous_dates.count(common_date) < len(previous_dates) * 0.8:
                continue
            try:
                delayed_days = (datetime.strptime(final_date, "%Y-%m-%d")
                                - datetime.strptime(common_date, "%Y-%m-%d")).days
            except ValueError:
                continue
            if delayed_days >= 7:
                trailing_bonus_episodes.add((season_number, final_number))

        missing_by_season: dict[int, list[int]] = {}
        for season_number, episodes in expected_by_season.items():
            for episode in episodes:
                episode_number = episode["episode_number"]
                if (season_number, episode_number) in trailing_bonus_episodes:
                    continue
                if (season_number, episode_number) not in present:
                    missing_by_season.setdefault(season_number, []).append(episode_number)
        for season_number, episode_numbers in sorted(missing_by_season.items()):
            has_local_season = connection.execute(
                """SELECT 1 FROM catalog_nodes WHERE parent_id=? AND kind='season'
                   AND season_number=? AND available=1 LIMIT 1""",
                (series_id, season_number),
            ).fetchone()
            if not has_local_season:
                missing_seasons.append(season_number)
            missing_episodes.append({"season_number": season_number,
                                     "episode_numbers": sorted(episode_numbers)})
    missing_count = sum(len(item["episode_numbers"]) for item in missing_episodes)
    return {"completion_known": known,
            "missing_seasons": missing_seasons,
            "missing_episodes": missing_episodes,
            "missing_episode_count": missing_count,
            "incomplete": bool(missing_episodes) if known else False}


def _node_payload(row: sqlite3.Row, connection: sqlite3.Connection) -> dict:
    node = dict(row)
    genres = normalize_genres(json.loads(node.pop("genres", "[]") or "[]"))
    node["cast"] = json.loads(node.pop("cast_json", None) or "[]")
    node.pop("cast_version", None)
    node["display_title"] = node.pop("metadata_title", None) or node["title"]
    node["year"] = node.get("year") or node.get("source_year")
    inherited_genres = _inherited_collection_value(connection, node.get("parent_id"), "genres")
    if not genres and inherited_genres:
        genres = normalize_genres(json.loads(inherited_genres[1] or "[]"))
    node["genres"] = genres
    if node["kind"] == "series":
        node.update(_series_completion(connection, node["id"], node.get("tmdb_episodes_json")))
    poster_file = node.get("poster_file")
    collection_artwork = _collection_artwork(connection, node["id"]) if node["kind"] == "collection" else None
    single_item_poster = collection_artwork["single_item_poster_url"] if collection_artwork else None
    series_poster = _series_fallback_poster(connection, node["id"]) \
        if not poster_file and node["kind"] == "series" else None
    node["poster_available"] = bool(poster_file or single_item_poster or series_poster) or node["kind"] == "season"
    node["poster_is_assigned"] = bool(poster_file)
    if poster_file:
        node["poster_url"] = _artwork_url(node["id"], "poster", poster_file)
    elif single_item_poster:
        node["poster_url"] = single_item_poster
    elif series_poster:
        node["poster_url"] = _artwork_url(
            series_poster[0], "poster", series_poster[1], fallback_version=series_poster[2],
        )
    else:
        node["poster_url"] = None
    node["backdrop_url"] = (
        _artwork_url(node["id"], "backdrop", node.get("backdrop_file"))
        if node.get("backdrop_file") else None
    )
    node.pop("metadata_candidates", None)
    node.pop("poster_file", None)
    node.pop("backdrop_file", None)
    node.pop("metadata_error", None)
    node.pop("age_rating_checked_at", None)
    if node["kind"] == "collection" and not genres:
        genre_row = connection.execute(
            """WITH RECURSIVE descendants(id) AS (
                   SELECT id FROM catalog_nodes WHERE parent_id=? AND available=1
                   UNION ALL
                   SELECT child.id FROM catalog_nodes child
                   JOIN descendants parent ON child.parent_id=parent.id
                   WHERE child.available=1
               )
               SELECT genres FROM catalog_nodes
               WHERE id IN (SELECT id FROM descendants)
                 AND kind IN ('movie', 'series') AND genres!='[]'
               ORDER BY title COLLATE NOCASE LIMIT 1""",
            (node["id"],),
        ).fetchone()
        node["genres"] = normalize_genres(
            json.loads(genre_row["genres"] or "[]") if genre_row else []
        )

    season_request_statuses: dict[int, str] = {}
    if node["kind"] == "series":
        request_rows = connection.execute(
            """SELECT request_type, season_number, status, details_json
               FROM content_requests
               WHERE (request_type='remaining_seasons' AND catalog_node_id=?)
                  OR (request_type='new_content' AND content_type='tv' AND tmdb_id=? AND season_number IS NOT NULL)
               ORDER BY created_at, id""",
            (node["id"], node.get("tmdb_id")),
        ).fetchall()
        for request_row in request_rows:
            details = json.loads(request_row["details_json"] or "{}")
            targeted: set[int] = set()
            if request_row["request_type"] == "new_content":
                targeted.add(int(request_row["season_number"]))
            else:
                explicit = details.get("season_number") or request_row["season_number"]
                # Legacy series-wide requests do not reliably describe which
                # individual seasons were intended. They may contain stale
                # completion data, so only season-scoped requests set a badge.
                if explicit is None:
                    continue
                current_missing = {
                    int(value["season_number"]): {int(number) for number in value["episode_numbers"]}
                    for value in node.get("missing_episodes", [])
                }
                current_missing_seasons = {int(value) for value in node.get("missing_seasons", [])}
                requested_numbers = {int(number) for number in
                                     details.get("missing_episode_numbers") or []}
                if requested_numbers & current_missing.get(int(explicit), set()):
                    targeted.add(int(explicit))
                if int(explicit) in current_missing_seasons:
                    targeted.add(int(explicit))
            for number in targeted:
                season_request_statuses[number] = request_row["status"]
        node["season_request_statuses"] = {
            str(number): status for number, status in season_request_statuses.items()
        }

    child_rows = connection.execute(
        """SELECT id, kind, title, metadata_title, poster_file, season_number, parent_id,
                  tmdb_episodes_json
           FROM catalog_nodes WHERE parent_id=? AND available=1
           ORDER BY CASE kind WHEN 'collection' THEN 0 WHEN 'series' THEN 1
                              WHEN 'season' THEN 2 ELSE 3 END,
                    title COLLATE NOCASE""",
        (node["id"],),
    ).fetchall()
    node["children"] = []
    for child in child_rows:
        child_series_poster = _series_fallback_poster(connection, child["id"]) \
            if not child["poster_file"] and child["kind"] == "series" else None
        available = bool(child["poster_file"] or child_series_poster) or child["kind"] == "season"
        poster_node_id = child["id"] if child["poster_file"] else (
            child_series_poster[0] if child_series_poster else child["id"])
        poster_filename = child["poster_file"] or (
            child_series_poster[1] if child_series_poster else None)
        child_payload = {"id": child["id"], "kind": child["kind"],
             "title": child["metadata_title"] or child["title"],
             "season_number": child["season_number"],
             "poster_url": (_artwork_url(poster_node_id, "poster",
                                          poster_filename,
                                          fallback_version=(child_series_poster[2]
                                              if child_series_poster else f"season-{child['season_number']}"))
                            if available else None),
             "poster_available": available,
             "poster_is_assigned": bool(child["poster_file"])}
        if child["kind"] == "season":
            for missing in node.get("missing_episodes", []):
                if int(missing["season_number"]) == int(child["season_number"] or 0):
                    numbers = missing["episode_numbers"]
                    child_payload.update({"incomplete": bool(numbers),
                                          "missing_episode_numbers": numbers,
                                          "missing_episode_count": len(numbers)})
                    break
            request_status = season_request_statuses.get(int(child["season_number"] or 0))
            if request_status:
                child_payload["request_status"] = request_status
        if child["kind"] == "series":
            child_payload.update(_series_completion(
                connection, child["id"], child["tmdb_episodes_json"],
            ))
        if child["kind"] == "collection":
            child_artwork = _collection_artwork(connection, child["id"])
            if not child["poster_file"] and child_artwork["single_item_poster_url"]:
                child_payload["poster_url"] = child_artwork["single_item_poster_url"]
                child_payload["poster_available"] = True
            child_payload.update({"item_count": child_artwork["item_count"],
                                  "mosaic_images": child_artwork["mosaic_images"]})
        node["children"].append(child_payload)
    if node["kind"] == "series":
        node["children"].extend(_pending_season_placeholders(connection, node["id"]))
    file_rows = connection.execute(
        """SELECT id, COALESCE(episode_title, title) AS title, title AS filename,
                  relative_path, extension, size_bytes, modified_at, season_number,
                  episode_number, poster_file
           FROM library_items WHERE node_id=? AND available=1
           ORDER BY season_number, episode_number, title COLLATE NOCASE""",
        (node["id"],),
    ).fetchall()
    node["files"] = []
    for media in file_rows:
        poster = (_episode_poster_url(media["id"], media["poster_file"], media["modified_at"])
                  if media["poster_file"] else node["poster_url"])
        media_payload = dict(media)
        media_payload.pop("poster_file", None)
        node["files"].append({**media_payload,
                              "stream_url": f"/api/v1/items/{media['id']}/stream",
                              "poster_url": poster})
    if node["kind"] == "season":
        parent = connection.execute(
            "SELECT title, metadata_title FROM catalog_nodes WHERE id=? AND available=1",
            (node.get("parent_id"),),
        ).fetchone()
        if parent is not None:
            series_title = str(parent["metadata_title"] or parent["title"])
            for entry in _incoming_entries(connection):
                if (entry["library"] != "series" or entry["content_kind"] != "episode"
                        or int(entry["season_number"] or 0) != int(node.get("season_number") or 0)
                        or not _media_titles_similar(series_title, str(entry["title"] or ""))):
                    continue
                episode_number = int(entry["episode_number"] or 0)
                episode_title = str(entry["episode_title"] or "").strip()
                title = episode_title or f"S{int(node.get('season_number') or 0):02d}E{episode_number:02d}"
                node["files"].append({
                    "id": f"incoming:{entry['file_id']}", "title": title,
                    "filename": entry["filename"], "extension": Path(str(entry["filename"])).suffix,
                    "size_bytes": entry["size_bytes"], "modified_at": entry["modified_at"],
                    "season_number": node.get("season_number"), "episode_number": episode_number,
                    "poster_url": entry["poster_url"] or node["poster_url"],
                    "is_placeholder": True, "is_incoming_pending": True,
                    "incoming_filename": entry["filename"],
                })
            node["files"].sort(key=lambda item: (
                int(item.get("episode_number") or 0),
                str(item.get("title") or "").casefold(),
            ))
    node["mosaic_images"] = collection_artwork["mosaic_images"] if collection_artwork else []
    if collection_artwork:
        node["item_count"] = collection_artwork["item_count"]
    return node


def _item_select() -> str:
    return """SELECT li.id, COALESCE(li.episode_title, li.title) AS title,
                     li.title AS filename, li.library, li.relative_path, li.extension,
                     li.size_bytes, li.modified_at, li.node_id,
                     li.series_node_id AS series_id, li.season_number, li.episode_number,
                     n.metadata_title, n.overview, COALESCE(n.year, n.source_year) AS year,
                     n.genres AS genres_json,
                     n.age_limit, n.age_rating,
                     (SELECT COUNT(*) FROM catalog_nodes s
                      WHERE s.parent_id=n.id AND s.kind='season' AND s.available=1) AS season_count,
                     n.id AS metadata_node_id, n.parent_id AS metadata_parent_id, n.poster_file,
                     li.poster_file AS episode_poster_file
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


def _start_episode_thumbnail_job() -> None:
    global EPISODE_THUMBNAIL_RUNNING
    with EPISODE_THUMBNAIL_LOCK:
        if EPISODE_THUMBNAIL_RUNNING:
            return
        EPISODE_THUMBNAIL_RUNNING = True

    def run() -> None:
        global EPISODE_THUMBNAIL_RUNNING
        try:
            with closing(database()) as connection:
                extract_episode_thumbnails(connection, MEDIA_ROOTS, ARTWORK_DIR)
        finally:
            with EPISODE_THUMBNAIL_LOCK:
                EPISODE_THUMBNAIL_RUNNING = False

    threading.Thread(target=run, name="episode-thumbnails", daemon=True).start()


def _clean_media_title(value: str) -> str:
    value = re.sub(r"[._]+", " ", value.replace("/", " ").replace("\\", " "))
    value = re.sub(r"[\x00-\x1f\x7f]", "", value)
    value = re.sub(r"\s+", " ", value).strip(" -_.")
    noise = re.search(
        r"(?i)(?:\b(?:480|576|720|1080|2160)p\b|\b4k\b|\bweb[ ._-]?(?:dl|rip)\b|"
        r"\bblu[ ._-]?ray\b|\b(?:x264|x265|h264|h265|hevc|avc|hdr10?)\b|\bproper\b|\brepack\b)",
        value,
    )
    if noise:
        value = value[:noise.start()].strip(" -_.")
    return value.strip(" -_.")


def _safe_path_component(value: str) -> str:
    value = re.sub(r"[\\/\x00-\x1f\x7f]", " ", value)
    value = re.sub(r"\s+", " ", value).strip(" .")
    return value or "Unknown title"


def _normalize_media_title(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    normalized = "".join(char for char in normalized if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]", "", normalized.casefold())


def _media_titles_similar(left: str, right: str) -> bool:
    if _normalize_media_title(left) == _normalize_media_title(right):
        return True
    ignored = {"and", "con", "de", "del", "el", "en", "la", "las", "los",
               "of", "the", "un", "una", "y", "e"}
    left_words = [word for word in re.findall(r"[a-z0-9]+", unicodedata.normalize(
        "NFKD", left).encode("ascii", "ignore").decode("ascii").casefold())
                  if len(word) >= 3 and word not in ignored]
    right_words = [word for word in re.findall(r"[a-z0-9]+", unicodedata.normalize(
        "NFKD", right).encode("ascii", "ignore").decode("ascii").casefold())
                   if len(word) >= 3 and word not in ignored]
    if len(left_words) < 2 or len(right_words) < 2:
        return False
    matched: set[int] = set()
    count = 0
    distinctive = False
    for word in left_words:
        for index, candidate in enumerate(right_words):
            if index in matched:
                continue
            same = word == candidate or (
                min(len(word), len(candidate)) >= 5
                and SequenceMatcher(None, word, candidate).ratio() >= 0.8
            )
            if same:
                matched.add(index)
                count += 1
                distinctive = distinctive or min(len(word), len(candidate)) >= 5
                break
    left_coverage = count / len(left_words)
    right_coverage = count / len(right_words)
    return (distinctive and count >= 2 and min(left_coverage, right_coverage) >= 0.5
            and max(left_coverage, right_coverage) >= 0.8)


def _incoming_guess(path: Path) -> dict[str, object]:
    stem = path.stem
    episode = EPISODE_RE.search(stem)
    if episode:
        series_title = _clean_media_title(stem[:episode.start()]) or _clean_media_title(stem)
        series_title = re.sub(r"\s*\(?\b(?:19|20)\d{2}\)?$", "", series_title).strip(" -_.")
        if episode.group(1) is not None:
            season_number, episode_number = int(episode.group(1)), int(episode.group(2))
        else:
            season_number, episode_number = int(episode.group(3)), int(episode.group(4))
        episode_title = _clean_media_title(stem[episode.end():])
        return {
            "library": "series", "kind": "episode", "title": series_title,
            "year": None, "season_number": season_number,
            "episode_number": episode_number, "episode_title": episode_title,
        }

    year_matches = list(re.finditer(r"(?<!\d)((?:19|20)\d{2})(?!\d)", stem))
    year_match = year_matches[-1] if year_matches else None
    year = int(year_match.group(1)) if year_match else None
    title_source = stem[:year_match.start()] if year_match else stem
    title = _clean_media_title(title_source) or _clean_media_title(stem) or "Unknown title"
    title = re.sub(r"[([{]+\s*$", "", title).strip(" -_.") or "Unknown title"
    return {"library": "movies", "kind": "movie", "title": title,
            "year": year, "season_number": None, "episode_number": None,
            "episode_title": ""}


def _match_incoming_request(connection: sqlite3.Connection,
                            guess: dict[str, object]) -> sqlite3.Row | None:
    normalized_title = _normalize_media_title(str(guess["title"]))
    if not normalized_title:
        return None
    request_type = "movie" if guess["library"] == "movies" else "tv"
    rows = connection.execute(
        """SELECT * FROM content_requests WHERE status='approved'
           ORDER BY created_at DESC, id"""
    ).fetchall()
    matches = []
    for request in rows:
        if request["request_type"] == "new_content":
            if request["content_type"] != request_type:
                continue
            if request_type == "tv" and request["season_number"] is not None \
                    and request["season_number"] != guess["season_number"]:
                continue
        elif request["request_type"] == "remaining_seasons":
            if request_type != "tv":
                continue
            details = json.loads(request["details_json"] or "{}")
            requested_seasons = {int(value) for value in details.get("missing_seasons") or []}
            requested_season = details.get("season_number") or request["season_number"]
            if requested_season is not None and int(requested_season) != int(guess["season_number"]):
                continue
            if requested_seasons and guess["season_number"] not in requested_seasons:
                continue
            requested_episodes = {int(value) for value in details.get("missing_episode_numbers") or []}
            if requested_episodes and int(guess["episode_number"] or 0) not in requested_episodes:
                continue
        else:
            continue
        if not _media_titles_similar(request["title"], str(guess["title"])):
            continue
        request_year = request["year"]
        guess_year = guess["year"]
        if request_year is not None and guess_year is not None and request_year != guess_year:
            continue
        matches.append(request)
    return matches[0] if matches else None


def _incoming_poster_url(connection: sqlite3.Connection, guess: dict[str, object],
                         request: sqlite3.Row | None) -> str | None:
    if request is not None:
        details = json.loads(request["details_json"] or "{}")
        poster_url = details.get("poster_url")
        if isinstance(poster_url, str) and poster_url.startswith(("http://", "https://", "/")):
            return poster_url
        poster_path = details.get("poster_path")
        if isinstance(poster_path, str) and poster_path.startswith("/"):
            return f"https://image.tmdb.org/t/p/w342{poster_path}"
        node_id = request["catalog_node_id"]
        if node_id:
            node = connection.execute(
                "SELECT id, kind, poster_file FROM catalog_nodes WHERE id=? AND available=1",
                (node_id,),
            ).fetchone()
            if node is not None and node["poster_file"]:
                return _artwork_url(node["id"], "poster", node["poster_file"])
            if node is not None and node["kind"] == "series":
                fallback = _series_fallback_poster(connection, node["id"])
                if fallback:
                    return _artwork_url(node["id"], "poster", fallback[1])

    library = "series" if guess["library"] == "series" else "movies"
    kind = "series" if library == "series" else "movie"
    title = _normalize_media_title(str(guess["title"]))
    if not title:
        return None
    for node in connection.execute(
        """SELECT id, title, metadata_title, original_title, poster_file
           FROM catalog_nodes WHERE library=? AND kind=? AND available=1
             AND poster_file IS NOT NULL""",
        (library, kind),
    ):
        if any(_media_titles_similar(str(guess["title"]), str(candidate or ""))
               for candidate in (node["title"], node["metadata_title"], node["original_title"])):
            return _artwork_url(node["id"], "poster", node["poster_file"])
    return None


def _incoming_entries(connection: sqlite3.Connection) -> list[dict[str, object]]:
    if not INCOMING_ROOT.is_dir():
        raise HTTPException(status_code=503, detail="Incoming folder is not mounted")
    with INCOMING_ACTIVITY_LOCK:
        active_paths = set(INCOMING_ACTIVE_PATHS)
    entries: list[dict[str, object]] = []
    for path in sorted(INCOMING_ROOT.rglob("*"), key=lambda item: item.as_posix().casefold()):
        try:
            if path.is_symlink() or not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
                continue
            resolved = path.resolve(strict=True)
            relative = resolved.relative_to(INCOMING_ROOT)
            if relative.as_posix() in active_paths:
                continue
            stat = resolved.stat()
        except (OSError, ValueError):
            continue
        guess = _incoming_guess(resolved)
        request = _match_incoming_request(connection, guess)
        library = str(guess["library"])
        request_title = request["title"] if request is not None else str(guess["title"])
        path_title = _safe_path_component(str(request_title))
        request_year = request["year"] if request is not None and request["year"] else guess["year"]
        if library == "movies":
            if request_year:
                year_suffix = re.compile(
                    rf"(?:\s*\(\s*{int(request_year)}\s*\)|"
                    rf"\s*\[\s*{int(request_year)}\s*\]|\s+{int(request_year)})$"
                )
                path_title = year_suffix.sub("", path_title).strip(" .") or path_title
            folder = path_title + (f" ({request_year})" if request_year else "")
            output_name = f"{path_title}{resolved.suffix}"
            default_target = MEDIA_DISPLAY_ROOTS[library] / folder / output_name
        else:
            series_title = path_title
            season_number = int(guess["season_number"])
            episode_number = int(guess["episode_number"])
            episode_title = _safe_path_component(str(guess["episode_title"])) \
                if guess["episode_title"] else ""
            title_prefix = f"{episode_title} - " if episode_title else ""
            output_name = f"{title_prefix}s{season_number:02d}e{episode_number:02d}{resolved.suffix}"
            default_target = (MEDIA_DISPLAY_ROOTS[library] / series_title
                              / f"Season {season_number:02d}" / output_name)
        file_id = hashlib.sha256(
            f"{relative.as_posix()}:{stat.st_size}:{stat.st_mtime_ns}".encode("utf-8")
        ).hexdigest()
        entries.append({
            "file_id": file_id, "filename": resolved.name,
            "relative_path": relative.as_posix(),
            "source_path": str(INCOMING_DISPLAY_ROOT / relative),
            "size_bytes": stat.st_size,
            "modified_at": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
            "library": library,
            "content_kind": str(guess["kind"]), "title": request_title,
            "year": request_year, "season_number": guess["season_number"],
            "episode_number": guess["episode_number"],
            "episode_title": guess["episode_title"],
            "destination_path": str(default_target),
            "request_matched": request is not None,
            "request_id": request["id"] if request is not None else None,
            "request_status": request["status"] if request is not None else None,
            "poster_url": _incoming_poster_url(connection, guess, request),
        })
    return entries


def _incoming_catalog_placeholders(connection: sqlite3.Connection,
                                   library: str) -> list[dict[str, object]]:
    kind = "movie" if library == "movies" else "series"
    items: list[dict[str, object]] = []
    seen_titles: set[str] = set()
    for entry in _incoming_entries(connection):
        if entry["library"] != library:
            continue
        title = str(entry["title"] or "").strip()
        is_episode = library == "series" and entry["content_kind"] == "episode"
        item_kind = "episode" if is_episode else kind
        normalized = _normalize_media_title(title)
        dedupe_key = (f"{entry['file_id']}" if is_episode else
                      normalized if kind == "series" else f"{normalized}:{entry['year'] or ''}")
        if not normalized or (not is_episode and dedupe_key in seen_titles):
            continue
        if not is_episode:
            seen_titles.add(dedupe_key)
        catalog_match = None
        for candidate in connection.execute(
            """SELECT id, title, metadata_title, original_title, genres, age_limit, age_rating
               FROM catalog_nodes WHERE library=? AND kind=? AND available=1""",
            (library, kind),
        ):
            if any(_media_titles_similar(title, str(value or "")) for value in (
                    candidate["title"], candidate["metadata_title"], candidate["original_title"])):
                catalog_match = candidate
                break
        episode_title = str(entry["episode_title"] or "").strip()
        episode_number = int(entry["episode_number"] or 0)
        season_number = int(entry["season_number"] or 0)
        display_title = (episode_title or f"S{season_number:02d}E{episode_number:02d}") \
            if is_episode else title
        item: dict[str, object] = {
            "id": f"incoming:{entry['file_id']}", "kind": item_kind, "library": library,
            "title": display_title, "display_title": display_title, "year": entry["year"],
            "added_at": entry["modified_at"],
            "poster_url": entry["poster_url"], "poster_available": bool(entry["poster_url"]),
            "genres": json.loads(catalog_match["genres"] or "[]") if catalog_match else [],
            "age_limit": catalog_match["age_limit"] if catalog_match else 0,
            "age_rating": catalog_match["age_rating"] if catalog_match else None,
            "is_placeholder": True, "is_incoming_pending": True,
            "incoming_file_id": entry["file_id"], "incoming_filename": entry["filename"],
            "children": [], "files": [],
        }
        if is_episode:
            item.update({"series_title": title, "season_number": season_number,
                         "episode_number": episode_number})
            if catalog_match is not None:
                item["series_id"] = catalog_match["id"]
        request_id = entry.get("request_id")
        if request_id:
            request = connection.execute(
                "SELECT tmdb_id FROM content_requests WHERE id=?", (request_id,),
            ).fetchone()
            if request is not None:
                item["tmdb_id"] = request["tmdb_id"]
        items.append(item)
    return items


def _incoming_catalog_signature() -> list[tuple[str, int, int]]:
    signature: list[tuple[str, int, int]] = []
    if not INCOMING_ROOT.is_dir():
        return signature
    try:
        for path in INCOMING_ROOT.rglob("*"):
            try:
                if path.is_symlink() or not path.is_file() \
                        or path.suffix.lower() not in VIDEO_EXTENSIONS:
                    continue
                stat = path.stat()
                signature.append((path.relative_to(INCOMING_ROOT).as_posix(),
                                  stat.st_size, stat.st_mtime_ns))
            except (OSError, ValueError):
                continue
    except OSError:
        return []
    return sorted(signature)


def _remove_fulfilled_content_requests(connection: sqlite3.Connection) -> int:
    requests = connection.execute(
        "SELECT * FROM content_requests WHERE status='approved' ORDER BY id"
    ).fetchall()
    removed = 0
    for request in requests:
        request_type = request["request_type"]
        fulfilled = False
        if request_type == "new_content" and request["tmdb_id"] is not None:
            if request["content_type"] == "movie":
                fulfilled = connection.execute(
                    """SELECT 1 FROM catalog_nodes WHERE available=1 AND library='movies'
                       AND kind='movie' AND tmdb_id=? LIMIT 1""",
                    (request["tmdb_id"],),
                ).fetchone() is not None
            elif request["content_type"] == "tv":
                series = connection.execute(
                    """SELECT id FROM catalog_nodes WHERE available=1 AND library='series'
                       AND kind='series' AND tmdb_id=? LIMIT 1""",
                    (request["tmdb_id"],),
                ).fetchone()
                if series is not None:
                    season_number = request["season_number"]
                    if season_number is None:
                        fulfilled = True
                    else:
                        fulfilled = connection.execute(
                            """SELECT 1 FROM catalog_nodes WHERE available=1 AND kind='season'
                               AND parent_id=? AND season_number=? LIMIT 1""",
                            (series["id"], season_number),
                        ).fetchone() is not None
        elif request_type == "remaining_seasons" and request["catalog_node_id"]:
            series = connection.execute(
                """SELECT id, tmdb_episodes_json FROM catalog_nodes
                   WHERE id=? AND available=1 AND kind='series'""",
                (request["catalog_node_id"],),
            ).fetchone()
            if series is not None:
                details = json.loads(request["details_json"] or "{}")
                missing_seasons = details.get("missing_seasons") or []
                if missing_seasons:
                    fulfilled = all(connection.execute(
                        """SELECT 1 FROM catalog_nodes WHERE available=1 AND kind='season'
                           AND parent_id=? AND season_number=? LIMIT 1""",
                        (series["id"], int(number)),
                    ).fetchone() is not None for number in missing_seasons)
                elif details.get("season_number") is not None:
                    season_number = int(details["season_number"])
                    if connection.execute(
                            """SELECT 1 FROM catalog_nodes WHERE available=1 AND kind='season'
                               AND parent_id=? AND season_number=? LIMIT 1""",
                            (series["id"], season_number),
                    ).fetchone() is None:
                        fulfilled = False
                    else:
                        completion = _series_completion(
                            connection, series["id"], series["tmdb_episodes_json"],
                        )
                        remaining = next((item["episode_numbers"]
                                          for item in completion["missing_episodes"]
                                          if int(item["season_number"]) == season_number), [])
                        requested_episodes = details.get("missing_episode_numbers") or []
                        fulfilled = not any(int(number) in set(remaining)
                                            for number in requested_episodes)
                elif int(details.get("missing_episode_count") or 0) > 0:
                    completion = _series_completion(
                        connection, series["id"], series["tmdb_episodes_json"],
                    )
                    fulfilled = completion["completion_known"] and not completion["incomplete"]

        if fulfilled:
            cursor = connection.execute(
                "DELETE FROM content_requests WHERE id=? AND status='approved'",
                (request["id"],),
            )
            removed += cursor.rowcount
    if removed:
        connection.commit()
    return removed


def _run_sync_job(job_id: str) -> None:
    try:
        started = datetime.now(timezone.utc).isoformat()
        _update_sync_job(job_id, status="running", stage="scan", started_at=started)
        with closing(database()) as connection:
            scan_result = scan_catalog(connection, MEDIA_ROOTS)
        _start_episode_thumbnail_job()
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
        with closing(database()) as connection:
            fulfilled_requests_removed = _remove_fulfilled_content_requests(connection)
        result = {"catalog": scan_result, "metadata": metadata_result,
                  "fulfilled_requests_removed": fulfilled_requests_removed}
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


def _schedule_sync_when_available() -> bool:
    global SYNC_RETRY_SCHEDULED
    if not TMDB_CONFIGURED:
        return False
    with SYNC_RETRY_LOCK:
        if SYNC_RETRY_SCHEDULED:
            return True
        SYNC_RETRY_SCHEDULED = True

    def retry() -> None:
        global SYNC_RETRY_SCHEDULED
        try:
            deadline = time.monotonic() + 3600
            while TMDB_CONFIGURED and time.monotonic() < deadline:
                try:
                    if _start_sync_job() is not None:
                        return
                except Exception:
                    pass
                time.sleep(2)
        finally:
            with SYNC_RETRY_LOCK:
                SYNC_RETRY_SCHEDULED = False

    threading.Thread(target=retry, daemon=True, name="zinema-incoming-sync-retry").start()
    return True


def _update_nas_download_state(**values: object) -> None:
    with NAS_DOWNLOAD_LOCK:
        NAS_DOWNLOAD_STATE.update(values)


def _download_filename_from_output(line: str) -> str | None:
    extensions = "|".join(re.escape(ext.lstrip(".")) for ext in VIDEO_EXTENSIONS)
    suffix_pattern = rf"\.(?:{extensions})(?=$|[\s\"'`),;\]])"
    # Downloader output usually prefixes the path with a verb or a field label.
    labelled = re.search(
        rf"(?i)(?:downloading|descargando|archivo|file|downloaded|saving|saved)\b"
        rf"(?:\s*[:=]\s*|\s+)([^\r\n]+?{suffix_pattern})",
        line,
    )
    candidate = labelled.group(1) if labelled else ""
    if not candidate:
        quoted = re.findall(r"[\"']([^\"']+" + suffix_pattern + r")[\"']", line, re.IGNORECASE)
        candidate = quoted[-1] if quoted else ""
    if not candidate:
        path_candidates = re.findall(rf"(?:^|[\s=:])([^\r\n]+?{suffix_pattern})", line, re.IGNORECASE)
        candidate = path_candidates[-1] if path_candidates else ""
    if not candidate:
        return None
    filename = Path(candidate.strip().strip("\"'`.,;()[]{} ")).name
    return filename if Path(filename).suffix.lower() in VIDEO_EXTENSIONS else None


def _incoming_download_candidate(path: Path) -> tuple[str, str] | None:
    suffix = path.suffix.lower()
    if suffix in VIDEO_EXTENSIONS:
        return path.name, suffix
    if suffix in {".part", ".tmp", ".download"} and Path(path.stem).suffix.lower() in VIDEO_EXTENSIONS:
        return Path(path.stem).name, Path(path.stem).suffix.lower()
    return None


def _scan_incoming_download_activity() -> dict[str, object] | None:
    with INCOMING_SCAN_LOCK:
        return _scan_incoming_download_activity_impl()


def _scan_incoming_download_activity_impl() -> dict[str, object] | None:
    if not INCOMING_ROOT.is_dir():
        with NAS_DOWNLOAD_LOCK:
            return dict(NAS_DOWNLOAD_STATE)
    now = time.monotonic()
    wall_now = time.time()
    with INCOMING_ACTIVITY_LOCK:
        previous_snapshot = dict(INCOMING_ACTIVITY_SNAPSHOT)
    current: dict[str, dict[str, object]] = {}
    active: list[dict[str, object]] = []
    try:
        files = INCOMING_ROOT.rglob("*") if INCOMING_ROOT.is_dir() else ()
        for path in files:
            if path.is_symlink():
                continue
            candidate = _incoming_download_candidate(path)
            if candidate is None or not path.is_file():
                continue
            try:
                stat = path.stat()
                relative = path.relative_to(INCOMING_ROOT).as_posix()
            except (OSError, ValueError):
                continue
            display_name, _ = candidate
            previous = previous_snapshot.get(relative)
            changed = previous is not None and (
                previous.get("size") != stat.st_size or previous.get("mtime_ns") != stat.st_mtime_ns
            )
            first_seen = float(previous["first_seen"]) if previous else now
            last_change = now if changed else (float(previous["last_change"]) if previous else now)
            has_changed = bool(previous and (previous.get("has_changed") or changed))
            snapshot = {
                "size": stat.st_size, "mtime_ns": stat.st_mtime_ns,
                "first_seen": first_seen, "last_change": last_change,
                "has_changed": has_changed, "filename": display_name,
            }
            current[relative] = snapshot
            file_age = max(0.0, wall_now - stat.st_mtime)
            provisional = (previous is None and now - first_seen < 5 and file_age < 12)
            is_active = ((has_changed and now - last_change < INCOMING_MONITOR_SETTLE_SECONDS)
                         or provisional)
            if is_active:
                active.append({
                    "path": relative, "filename": display_name,
                    "size": stat.st_size, "last_change": last_change,
                })
    except OSError:
        active = []

    active_path_names = {str(item["path"]) for item in active}
    with INCOMING_ACTIVITY_LOCK:
        INCOMING_ACTIVITY_SNAPSHOT.clear()
        INCOMING_ACTIVITY_SNAPSHOT.update(current)
        INCOMING_ACTIVE_PATHS.clear()
        INCOMING_ACTIVE_PATHS.update(active_path_names)

    current_file = max(active, key=lambda item: float(item["last_change"])) if active else None
    with NAS_DOWNLOAD_LOCK:
        state = NAS_DOWNLOAD_STATE
        app_download_active = (state["source"] == "app"
                               and state["status"] in {"starting", "running"})
        app_download_recently_finished = (state["source"] == "app"
                                          and state["status"] in {"completed", "failed"}
                                          and state.get("current_path") in active_path_names)
        if current_file and (app_download_active or app_download_recently_finished):
            if app_download_recently_finished:
                state.update({
                    "status": "running", "progress": "Descarga detectada en Incoming…",
                    "completed_at": None, "_awaiting_file_settle": True,
                })
            state.update({
                "current_file": current_file["filename"], "current_bytes": current_file["size"],
                "current_path": current_file["path"],
            })
            state["_last_activity_monotonic"] = max(
                float(state.get("_last_activity_monotonic", 0.0)),
                float(current_file["last_change"]),
            )
        elif current_file:
            if state["source"] != "incoming" or state["status"] != "running":
                state.update({
                    "id": f"incoming-{uuid4().hex}", "status": "running",
                    "progress": "Descarga detectada en Incoming…",
                    "current_title": None, "current_percent": None,
                    "downloaded_bytes": 0, "total_bytes": 0,
                    "started_at": datetime.now(timezone.utc).isoformat(),
                    "completed_at": None, "exit_code": None, "error": None,
                    "source": "incoming",
                })
            state.update({
                "current_file": current_file["filename"], "current_bytes": current_file["size"],
                "current_path": current_file["path"],
            })
            last_file_change = float(current_file["last_change"])
            state["_last_activity_monotonic"] = max(
                float(state.get("_last_activity_monotonic", 0.0)), last_file_change,
            )
        elif (state["status"] == "running"
              and (state["source"] == "incoming" or state.get("_awaiting_file_settle"))):
            last_activity = float(state.get("_last_activity_monotonic", now))
            if now - last_activity >= INCOMING_MONITOR_SETTLE_SECONDS:
                state.update({
                    "status": "completed", "progress": "Descarga finalizada.",
                    "completed_at": datetime.now(timezone.utc).isoformat(), "exit_code": 0,
                    "_awaiting_file_settle": False,
                })
        return dict(state)


def _incoming_download_monitor_loop() -> None:
    while True:
        # Clear before scanning so a download started during the scan wakes the
        # following wait instead of being lost between wait() and clear().
        INCOMING_MONITOR_WAKE.clear()
        try:
            state = _scan_incoming_download_activity()
        except Exception:
            state = None
        with INCOMING_ACTIVITY_LOCK:
            has_active_file = bool(INCOMING_ACTIVE_PATHS)
        state = state or {}
        download_running = state.get("status") in {"starting", "running"}
        interval = (INCOMING_MONITOR_ACTIVE_INTERVAL_SECONDS
                    if has_active_file or download_running
                    else INCOMING_MONITOR_IDLE_INTERVAL_SECONDS)
        INCOMING_MONITOR_WAKE.wait(interval)


def _settle_app_download_files() -> None:
    with INCOMING_SCAN_LOCK:
        with INCOMING_ACTIVITY_LOCK:
            now = time.monotonic()
            for path in INCOMING_ROOT.rglob("*") if INCOMING_ROOT.is_dir() else ():
                if path.is_symlink() or not path.is_file() or _incoming_download_candidate(path) is None:
                    continue
                try:
                    stat = path.stat()
                    relative = path.relative_to(INCOMING_ROOT).as_posix()
                except (OSError, ValueError):
                    continue
                snapshot = INCOMING_ACTIVITY_SNAPSHOT.get(relative)
                if snapshot is None:
                    snapshot = {"first_seen": now, "filename": path.name}
                    INCOMING_ACTIVITY_SNAPSHOT[relative] = snapshot
                snapshot.update({
                    "size": stat.st_size, "mtime_ns": stat.st_mtime_ns,
                    "has_changed": False,
                    "last_change": now - INCOMING_MONITOR_SETTLE_SECONDS,
                })
            INCOMING_ACTIVE_PATHS.clear()


def _record_nas_download_output(line: str) -> None:
    text = line.strip()
    if not text:
        return
    values: dict[str, object] = {"progress": text[-300:]}
    filename = _download_filename_from_output(text)
    if filename:
        values["current_file"] = filename
        values["current_title"] = Path(filename).stem
    title_match = re.search(
        r"(?i)últim[oa]\s+[^:]+:\s*(.+?)\s+\(([\d.]+)\s*MB\)", text,
    )
    if title_match:
        values["current_title"] = title_match.group(1).strip()
        values["total_bytes"] = int(float(title_match.group(2)) * 1024 * 1024)
    size_match = re.search(r"([\d.]+)\s*/\s*([\d.]+)\s*MB", text, re.IGNORECASE)
    if size_match:
        values["downloaded_bytes"] = int(float(size_match.group(1)) * 1024 * 1024)
        values["total_bytes"] = int(float(size_match.group(2)) * 1024 * 1024)
    percent_match = re.search(r"(?<![\d.])(\d{1,3}(?:\.\d+)?)\s*%", text)
    if percent_match:
        values["current_percent"] = min(100.0, max(0.0, float(percent_match.group(1))))
    elif values.get("total_bytes") and values.get("downloaded_bytes"):
        total = int(values["total_bytes"])
        if total > 0:
            values["current_percent"] = min(
                100.0, max(0.0, int(values["downloaded_bytes"]) * 100.0 / total),
            )
    _update_nas_download_state(**values)


def _run_nas_download(job_id: str) -> None:
    try:
        process = subprocess.Popen(
            ["python", "-u", str(NAS_DOWNLOADER_SCRIPT), "--force"],
            cwd=str(NAS_SCRIPTS_ROOT),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        _update_nas_download_state(status="running", started_at=datetime.now(timezone.utc).isoformat())
        if process.stdout is not None:
            decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
            pending = ""
            while True:
                chunk = os.read(process.stdout.fileno(), 4096)
                if not chunk:
                    pending += decoder.decode(b"", final=True)
                    if pending:
                        _record_nas_download_output(pending)
                    break
                pending += decoder.decode(chunk)
                lines = re.split(r"[\r\n]", pending)
                pending = lines.pop()
                for line in lines:
                    _record_nas_download_output(line)
        exit_code = process.wait()
        _settle_app_download_files()
        completed_at = datetime.now(timezone.utc).isoformat()
        if exit_code == 0:
            _update_nas_download_state(
                status="completed", completed_at=completed_at, exit_code=exit_code,
                error=None, progress="Descarga finalizada.", current_percent=100.0,
            )
        else:
            _update_nas_download_state(
                status="failed", completed_at=completed_at, exit_code=exit_code,
                error=f"El script terminó con código {exit_code}.",
            )
    except Exception as exc:
        _update_nas_download_state(
            status="failed", completed_at=datetime.now(timezone.utc).isoformat(),
            exit_code=None, error=str(exc)[:500],
        )


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
    global SCHEDULER_STARTED, INCOMING_MONITOR_STARTED
    age_rating_backfill_pending = False
    with closing(database()) as connection:
        for row in connection.execute("SELECT id, genres FROM catalog_nodes WHERE genres!='[]'").fetchall():
            try:
                cleaned = normalize_genres(json.loads(row["genres"] or "[]"))
            except (TypeError, json.JSONDecodeError):
                continue
            encoded = json.dumps(cleaned, ensure_ascii=False)
            if encoded != row["genres"]:
                connection.execute("UPDATE catalog_nodes SET genres=? WHERE id=?", (encoded, row["id"]))
        connection.commit()
        if TMDB_CONFIGURED:
            age_rating_backfill_pending = connection.execute(
                "SELECT 1 FROM catalog_nodes WHERE available=1 AND kind IN ('movie','series') "
                "AND tmdb_id IS NOT NULL AND age_rating_checked_at IS NULL LIMIT 1"
            ).fetchone() is not None
    _start_episode_thumbnail_job()
    if age_rating_backfill_pending:
        threading.Thread(target=_start_sync_job, daemon=True,
                         name="zinema-age-rating-backfill").start()
    if not SCHEDULER_STARTED:
        threading.Thread(target=_daily_sync_loop, daemon=True, name="zinema-daily-sync").start()
        SCHEDULER_STARTED = True
    if not INCOMING_MONITOR_STARTED:
        threading.Thread(target=_incoming_download_monitor_loop, daemon=True,
                         name="zinema-incoming-download-monitor").start()
        INCOMING_MONITOR_STARTED = True


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/items", response_model=list[LibraryItem], dependencies=[Depends(require_client)])
def list_items(
    q: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[LibraryItem]:
    sql = _item_select()
    parameters: list[object] = []
    if q:
        sql += " AND (li.title LIKE ? ESCAPE '!' COLLATE NOCASE OR li.episode_title LIKE ? ESCAPE '!' COLLATE NOCASE " \
               "OR n.title LIKE ? ESCAPE '!' COLLATE NOCASE " \
               "OR n.metadata_title LIKE ? ESCAPE '!' COLLATE NOCASE)"
        escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        parameters.extend((f"%{escaped}%", f"%{escaped}%", f"%{escaped}%", f"%{escaped}%"))
    sql += " ORDER BY COALESCE(n.metadata_title, n.title, li.title) COLLATE NOCASE LIMIT ? OFFSET ?"
    parameters.extend((limit, offset))
    with closing(database()) as connection:
        rows = connection.execute(sql, parameters).fetchall()
        return [item_from_row(row, connection) for row in rows]


@app.get("/api/v1/items/{item_id}", response_model=LibraryItem, dependencies=[Depends(require_client)])
def get_item(item_id: str) -> LibraryItem:
    with closing(database()) as connection:
        row = connection.execute(
            _item_select().replace("WHERE li.available=1", "WHERE li.available=1 AND li.id=?"),
            (item_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Item not found")
        return item_from_row(row, connection)


@app.get("/api/v1/catalog/version", dependencies=[Depends(require_client)])
def get_catalog_version() -> dict[str, str]:
    with closing(database()) as connection:
        nodes = connection.execute(
            """SELECT id, library, kind, relative_path, parent_id, title, season_number,
                      source_year, metadata_title, original_title, overview, year, genres,
                      age_limit, age_rating,
                      cast_json, cast_version, tmdb_id, imdb_id, poster_file, backdrop_file,
                      metadata_status, metadata_source, metadata_checked_at,
                      episode_metadata_checked_at, tmdb_episodes_json, scanned_at, added_at
               FROM catalog_nodes WHERE available=1 ORDER BY id"""
        ).fetchall()
        files = connection.execute(
            """SELECT id, library, title, relative_path, extension, size_bytes,
                      modified_at, scanned_at, node_id, series_node_id, season_number,
                      episode_number, episode_title, poster_file
               FROM library_items WHERE available=1 ORDER BY id"""
        ).fetchall()
        requests = connection.execute(
            """SELECT id, request_type, content_type, tmdb_id, title, year,
                      season_number, catalog_node_id, details_json, status
               FROM content_requests WHERE status='approved' ORDER BY id"""
        ).fetchall()
    state = json.dumps(
        [[tuple(row) for row in nodes], [tuple(row) for row in files],
         [tuple(row) for row in requests], _incoming_catalog_signature()],
        ensure_ascii=False, separators=(",", ":"),
    )
    return {"version": hashlib.sha256(state.encode("utf-8")).hexdigest()}


def _account_login(credentials: AccountCredentials, register: bool) -> dict[str, object]:
    username = (credentials.username or credentials.email or "").strip()
    if len(username) < 3 or len(username) > 254 or re.search(r"\s", username):
        raise HTTPException(status_code=422, detail="A valid username is required")
    username = username.lower()
    now = datetime.now(timezone.utc).isoformat()
    with closing(database()) as connection:
        row = connection.execute(
            """SELECT id, password_hash, disabled, approved, is_admin FROM accounts
               WHERE username=? COLLATE NOCASE OR email=? COLLATE NOCASE""",
            (username, username),
        ).fetchone()
        if register:
            if row is not None:
                raise HTTPException(status_code=409, detail="An account with this username already exists")
            user_id = uuid4().hex
            connection.execute(
                """INSERT INTO accounts(id, email, username, password_hash, is_admin, created_at, approved)
                   VALUES (?, ?, ?, ?, 0, ?, 0)""",
                (user_id, username, username, password_hash.hash(credentials.password), now),
            )
            connection.commit()
            return {"status": "pending_approval", "username": username, "email": username}
        else:
            if row is None or row["disabled"] or not password_hash.verify(
                    credentials.password, row["password_hash"]):
                raise HTTPException(status_code=401, detail="Invalid email or password")
            if not row["approved"] and not row["is_admin"]:
                raise HTTPException(status_code=403,
                                    detail="La cuenta está pendiente de aprobación por un administrador.")
            user_id = row["id"]
        result = issue_device_token(connection, user_id, credentials.device_name,
                                    credentials.device_id)
        connection.commit()
    with PLAYBACK_TICKET_LOCK:
        for ticket, grant in list(PLAYBACK_TICKETS.items()):
            if grant[2] == result["device_id"]:
                PLAYBACK_TICKETS.pop(ticket, None)
    return result


@app.post("/api/v1/auth/register", status_code=202)
def register_account(credentials: AccountCredentials) -> dict[str, object]:
    """Create a standard account that must be approved before it can sign in."""
    return _account_login(credentials, register=True)


@app.post("/api/v1/auth/login")
def login_account(credentials: AccountCredentials) -> dict[str, object]:
    """Authenticate an account and issue a revocable token for this installation."""
    return _account_login(credentials, register=False)


@app.post("/api/v1/auth/logout")
def logout_account(device_id: Annotated[str, Depends(require_client)]) -> dict[str, bool]:
    with closing(database()) as connection:
        cursor = connection.execute("UPDATE client_devices SET revoked=1 WHERE id=?", (device_id,))
        connection.commit()
    with PLAYBACK_TICKET_LOCK:
        for ticket, grant in list(PLAYBACK_TICKETS.items()):
            if grant[2] == device_id:
                PLAYBACK_TICKETS.pop(ticket, None)
    return {"revoked": cursor.rowcount > 0}


@app.get("/api/v1/auth/me")
def account_info(device_id: Annotated[str, Depends(require_client)]) -> dict[str, object]:
    with closing(database()) as connection:
        row = connection.execute(
            """SELECT a.id, a.email, a.username, a.is_admin, a.approved, a.created_at FROM accounts a
               JOIN client_devices d ON d.user_id=a.id WHERE d.id=?""", (device_id,),
        ).fetchone()
        count = connection.execute(
            "SELECT COUNT(*) AS n FROM user_profiles WHERE user_id=? AND deleted=0",
            (row["id"],),
        ).fetchone()["n"] if row else 0
    if row is None:
        raise HTTPException(status_code=401, detail="Account not found")
    return {"id": row["id"], "email": row["email"],
            "username": row["username"] or row["email"], "is_admin": bool(row["is_admin"]),
            "approved": bool(row["approved"]),
            "profile_count": count, "created_at": row["created_at"]}


@app.get("/api/v1/client/update")
def client_update_info() -> dict[str, object]:
    """Return public metadata for the latest production Android client, if published."""
    manifest_path = CLIENT_UPDATE_DIR / "manifest.json"
    if not manifest_path.is_file():
        return {"version_code": 0, "version_name": "", "sha256": "", "size_bytes": 0}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        version_code = int(manifest["version_code"])
        version_name = str(manifest.get("version_name", ""))[:80]
        sha256 = str(manifest["sha256"]).lower()
        size_bytes = int(manifest["size_bytes"])
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
        raise HTTPException(status_code=503, detail="Client update is not available")
    if version_code < 1 or not re.fullmatch(r"[0-9a-f]{64}", sha256) or size_bytes < 1:
        raise HTTPException(status_code=503, detail="Client update is not available")
    apk_path = CLIENT_UPDATE_DIR / "zinema-prod.apk"
    if not apk_path.is_file() or apk_path.stat().st_size != size_bytes:
        raise HTTPException(status_code=503, detail="Client update is not available")
    return {"version_code": version_code, "version_name": version_name,
            "sha256": sha256, "size_bytes": size_bytes}


@app.get("/api/v1/client/update/apk")
def download_client_update() -> FileResponse:
    """Serve the published production Android package."""
    manifest = client_update_info()
    if int(manifest["version_code"]) < 1:
        raise HTTPException(status_code=404, detail="No client update is published")
    return FileResponse(CLIENT_UPDATE_DIR / "zinema-prod.apk",
                        media_type="application/vnd.android.package-archive",
                        filename="zinema-prod.apk",
                        headers={"Cache-Control": "no-store"})


@app.post("/api/v1/admin/accounts", dependencies=[Depends(require_admin)])
def create_account(request: AccountCreateRequest) -> dict[str, object]:
    email = request.email.strip().lower()
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
        raise HTTPException(status_code=422, detail="A valid email is required")
    now = datetime.now(timezone.utc).isoformat()
    user_id = uuid4().hex
    with closing(database()) as connection:
        try:
            connection.execute(
                """INSERT INTO accounts(id, email, username, password_hash, is_admin, created_at, approved)
                   VALUES (?, ?, ?, ?, ?, ?, 1)""",
                (user_id, email, email, password_hash.hash(request.password), int(request.is_admin), now),
            )
            connection.commit()
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="An account with this email already exists") from exc
    return {"id": user_id, "email": email, "username": email,
            "is_admin": request.is_admin, "created_at": now}


@app.get("/api/v1/admin/accounts/pending", dependencies=[Depends(require_admin)])
def list_pending_accounts() -> list[dict[str, str]]:
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT id, COALESCE(NULLIF(username, ''), email) AS username,
                      COALESCE(NULLIF(username, ''), email) AS email, created_at FROM accounts
               WHERE approved=0 AND disabled=0 ORDER BY created_at, id"""
        ).fetchall()
    return [dict(row) for row in rows]


@app.get("/api/v1/admin/accounts", dependencies=[Depends(require_admin)])
def list_accounts() -> list[dict[str, object]]:
    """List account status for the administrator account management screen."""
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT a.id, COALESCE(NULLIF(a.username, ''), a.email) AS username,
                      a.created_at, a.is_admin, a.approved, a.disabled,
                      (SELECT COUNT(*) FROM client_devices d WHERE d.user_id=a.id AND d.revoked=0) AS devices,
                      COALESCE(
                        (SELECT p.avatar FROM client_devices d
                         JOIN user_profiles p ON p.id=d.active_profile_id AND p.user_id=d.user_id
                         WHERE d.user_id=a.id AND d.revoked=0 AND p.deleted=0
                         ORDER BY d.active_profile_updated_at DESC, d.created_at DESC LIMIT 1),
                        (SELECT p.avatar FROM user_profiles p WHERE p.user_id=a.id AND p.deleted=0
                         ORDER BY CASE WHEN p.avatar IS NULL OR p.avatar='' OR p.avatar LIKE 'generic:%'
                                       THEN 1 ELSE 0 END, p.created_at, p.id LIMIT 1)
                      ) AS avatar
               FROM accounts a ORDER BY a.created_at DESC, a.id"""
        ).fetchall()
    return [
        {**dict(row), "is_admin": bool(row["is_admin"]),
         "approved": bool(row["approved"]), "disabled": bool(row["disabled"])}
        for row in rows
    ]


@app.post("/api/v1/admin/accounts/{account_id}/approve",
          dependencies=[Depends(require_admin)])
def approve_account(account_id: str) -> dict[str, bool]:
    with closing(database()) as connection:
        cursor = connection.execute(
            "UPDATE accounts SET approved=1, disabled=0 WHERE id=? AND (approved=0 OR disabled=1)",
            (account_id,),
        )
        connection.commit()
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Pending account not found")
    return {"approved": True}


@app.post("/api/v1/admin/accounts/{account_id}/disable",
          dependencies=[Depends(require_admin)])
def disable_account(account_id: str, admin_device_id: Annotated[str, Depends(require_admin)]) -> dict[str, bool]:
    admin_id = account_for_device(admin_device_id)
    if account_id == admin_id:
        raise HTTPException(status_code=400, detail="You cannot disable your own administrator account")
    with closing(database()) as connection:
        device_ids = [row["id"] for row in connection.execute(
            "SELECT id FROM client_devices WHERE user_id=?", (account_id,),
        ).fetchall()]
        cursor = connection.execute("UPDATE accounts SET disabled=1 WHERE id=? AND disabled=0", (account_id,))
        connection.commit()
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Account not found")
    device_id_set = set(device_ids)
    with PLAYBACK_TICKET_LOCK:
        for ticket, grant in list(PLAYBACK_TICKETS.items()):
            if grant[2] in device_id_set:
                PLAYBACK_TICKETS.pop(ticket, None)
    return {"disabled": True}


def _delete_account_data(account_id: str) -> bool:
    with closing(database()) as connection:
        row = connection.execute("SELECT id FROM accounts WHERE id=?", (account_id,)).fetchone()
        if row is None:
            return False
        profile_ids = [item["id"] for item in connection.execute(
            "SELECT id FROM user_profiles WHERE user_id=?", (account_id,),
        ).fetchall()]
        device_ids = [item["id"] for item in connection.execute(
            "SELECT id FROM client_devices WHERE user_id=?", (account_id,),
        ).fetchall()]
        if profile_ids:
            connection.executemany("DELETE FROM profile_state WHERE profile_id=?",
                                   [(profile_id,) for profile_id in profile_ids])
        connection.execute("DELETE FROM user_profiles WHERE user_id=?", (account_id,))
        connection.execute("DELETE FROM client_devices WHERE user_id=?", (account_id,))
        connection.execute("DELETE FROM accounts WHERE id=?", (account_id,))
        connection.commit()
    device_id_set = set(device_ids)
    with PLAYBACK_TICKET_LOCK:
        for ticket, grant in list(PLAYBACK_TICKETS.items()):
            if grant[2] in device_id_set:
                PLAYBACK_TICKETS.pop(ticket, None)
    return True


@app.delete("/api/v1/admin/accounts/{account_id}")
def delete_account(account_id: str, admin_device_id: Annotated[str, Depends(require_admin)]) -> dict[str, bool]:
    admin_id = account_for_device(admin_device_id)
    if account_id == admin_id:
        raise HTTPException(status_code=400, detail="You cannot delete your own administrator account")
    if not _delete_account_data(account_id):
        raise HTTPException(status_code=404, detail="Account not found")
    return {"deleted": True}


@app.post("/api/v1/admin/accounts/{account_id}/reject",
          dependencies=[Depends(require_admin)])
def reject_account(account_id: str) -> dict[str, bool]:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT id FROM accounts WHERE id=? AND approved=0 AND disabled=0", (account_id,),
        ).fetchone()
    if row is None or not _delete_account_data(account_id):
        raise HTTPException(status_code=404, detail="Pending account not found")
    return {"deleted": True}


@app.get("/api/v1/admin/devices", dependencies=[Depends(require_admin)])
def list_client_devices() -> list[dict[str, object]]:
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked, a.email
               FROM client_devices d LEFT JOIN accounts a ON a.id=d.user_id ORDER BY d.created_at"""
        ).fetchall()
    return [dict(row) for row in rows]


@app.delete("/api/v1/admin/devices/{device_id}", dependencies=[Depends(require_admin)])
def revoke_client_device(device_id: str) -> dict[str, bool]:
    with closing(database()) as connection:
        cursor = connection.execute("UPDATE client_devices SET revoked=1 WHERE id=?", (device_id,))
        connection.commit()
    with PLAYBACK_TICKET_LOCK:
        for ticket, grant in list(PLAYBACK_TICKETS.items()):
            if grant[2] == device_id:
                PLAYBACK_TICKETS.pop(ticket, None)
    return {"revoked": cursor.rowcount > 0}


@app.get("/api/v1/profiles")
def list_profiles(device_id: Annotated[str, Depends(require_client)]) -> list[dict]:
    user_id = account_for_device(device_id)
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT id, name, avatar, is_kid, created_at, updated_at, deleted FROM user_profiles
               WHERE user_id=? ORDER BY created_at, id""", (user_id,),
        ).fetchall()
    result = []
    for row in rows:
        profile = dict(row)
        profile["deleted"] = bool(profile["deleted"])
        profile["is_kid"] = bool(profile["is_kid"])
        result.append(profile)
    return result


@app.put("/api/v1/profiles/active")
def set_active_profile(request: ActiveProfileRequest,
                       device_id: Annotated[str, Depends(require_client)]) -> dict[str, str]:
    user_id = account_for_device(device_id)
    with closing(database()) as connection:
        owned = connection.execute(
            "SELECT 1 FROM user_profiles WHERE id=? AND user_id=? AND deleted=0",
            (request.profile_id, user_id),
        ).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="Profile not found")
        connection.execute(
            "UPDATE client_devices SET active_profile_id=?, active_profile_updated_at=? "
            "WHERE id=? AND user_id=? AND revoked=0",
            (request.profile_id, datetime.now(timezone.utc).isoformat(), device_id, user_id),
        )
        connection.commit()
    return {"profile_id": request.profile_id}


@app.post("/api/v1/profiles")
def save_profile(profile: UserProfileRequest,
                 device_id: Annotated[str, Depends(require_client)]) -> dict:
    user_id = account_for_device(device_id)
    now = datetime.now(timezone.utc).isoformat()
    with closing(database()) as connection:
        connection.execute(
            """INSERT INTO user_profiles(id, user_id, name, avatar, is_kid, created_at, updated_at, deleted)
               VALUES (?, ?, ?, ?, ?, ?, ?, 0)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, avatar=excluded.avatar,
                   is_kid=excluded.is_kid, updated_at=excluded.updated_at, deleted=0
                   WHERE user_profiles.user_id=excluded.user_id""",
            (profile.id, user_id, profile.name.strip(), profile.avatar, int(profile.is_kid), now, now),
        )
        connection.commit()
        row = connection.execute(
            "SELECT id, name, avatar, is_kid, created_at, updated_at, deleted FROM user_profiles WHERE id=? AND user_id=?",
            (profile.id, user_id),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=409, detail="Profile belongs to another account")
    result = dict(row)
    result["is_kid"] = bool(result["is_kid"])
    result["deleted"] = bool(result["deleted"])
    return result


@app.delete("/api/v1/profiles/{profile_id}")
def delete_profile(profile_id: str, device_id: Annotated[str, Depends(require_client)]) -> dict[str, bool]:
    user_id = account_for_device(device_id)
    with closing(database()) as connection:
        owned = connection.execute(
            "SELECT 1 FROM user_profiles WHERE id=? AND user_id=?", (profile_id, user_id),
        ).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="Profile not found")
        connection.execute("DELETE FROM profile_state WHERE profile_id=?", (profile_id,))
        cursor = connection.execute(
            "UPDATE user_profiles SET deleted=1, updated_at=? WHERE id=? AND user_id=?",
            (datetime.now(timezone.utc).isoformat(), profile_id, user_id),
        )
        connection.commit()
    return {"deleted": cursor.rowcount > 0}


@app.get("/api/v1/profiles/{profile_id}/state")
def get_profile_state(profile_id: str, device_id: Annotated[str, Depends(require_client)]) -> dict:
    user_id = account_for_device(device_id)
    with closing(database()) as connection:
        exists = connection.execute(
            "SELECT 1 FROM user_profiles WHERE id=? AND user_id=? AND deleted=0", (profile_id, user_id),
        ).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="Profile not found")
        row = connection.execute(
            "SELECT state_json, revision, updated_at FROM profile_state WHERE profile_id=?",
            (profile_id,),
        ).fetchone()
    if row is None:
        return {"state": {}, "revision": 0, "updated_at": None}
    return {"state": json.loads(row["state_json"] or "{}"),
            "revision": row["revision"], "updated_at": row["updated_at"]}


@app.put("/api/v1/profiles/{profile_id}/state")
def save_profile_state(profile_id: str, state: dict[str, object],
                       device_id: Annotated[str, Depends(require_client)]) -> dict:
    user_id = account_for_device(device_id)
    now = datetime.now(timezone.utc).isoformat()
    state_json = json.dumps(state, ensure_ascii=False, separators=(",", ":"))
    with closing(database()) as connection:
        exists = connection.execute(
            "SELECT 1 FROM user_profiles WHERE id=? AND user_id=? AND deleted=0", (profile_id, user_id),
        ).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="Profile not found")
        connection.execute(
            """INSERT INTO profile_state(profile_id, state_json, revision, updated_at)
               VALUES (?, ?, 1, ?)
               ON CONFLICT(profile_id) DO UPDATE SET state_json=excluded.state_json,
                   revision=profile_state.revision + 1, updated_at=excluded.updated_at""",
            (profile_id, state_json, now),
        )
        connection.commit()
        row = connection.execute(
            "SELECT revision, updated_at FROM profile_state WHERE profile_id=?", (profile_id,),
        ).fetchone()
    return {"revision": row["revision"], "updated_at": row["updated_at"]}


@app.get("/api/v1/catalog", dependencies=[Depends(require_client)])
def list_catalog(
    library: str = Query(pattern="^(movies|series)$"),
    parent_id: str | None = None,
) -> list[dict]:
    if parent_id is None:
        _scan_incoming_download_activity()
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
        result = [_node_payload(row, connection) for row in rows]
        if parent_id is not None:
            parent = connection.execute(
                "SELECT kind FROM catalog_nodes WHERE id=? AND available=1", (parent_id,),
            ).fetchone()
            if parent and parent["kind"] == "series":
                result.extend(_pending_season_placeholders(connection, parent_id))
        else:
            approved = connection.execute(
                """SELECT * FROM content_requests WHERE status='approved'
                   AND request_type='new_content' AND content_type=?
                   ORDER BY created_at, id""",
                ("movie" if library == "movies" else "tv",),
            ).fetchall()
            seen: set[int] = set()
            for request in approved:
                tmdb_id = request["tmdb_id"]
                if tmdb_id in seen:
                    continue
                seen.add(tmdb_id)
                fixed = connection.execute(
                    """SELECT 1 FROM catalog_nodes WHERE library=? AND kind=?
                       AND tmdb_id=? AND available=1 LIMIT 1""",
                    (library, "movie" if library == "movies" else "series", tmdb_id),
                ).fetchone()
                if fixed is None:
                    result.append(_catalog_request_placeholder(
                        request, "movie" if library == "movies" else "series",
                    ))
            incoming_items = _incoming_catalog_placeholders(connection, library)
            for incoming in incoming_items:
                tmdb_id = incoming.get("tmdb_id")
                result = [item for item in result if not (
                    item.get("is_placeholder")
                    and ((tmdb_id is not None and item.get("tmdb_id") == tmdb_id)
                         or (tmdb_id is None and _media_titles_similar(
                             str(item.get("display_title") or item.get("title") or ""),
                             str(incoming.get("display_title") or "")))))]
            result.extend(incoming_items)
        result.sort(key=lambda item: (
            {"collection": 0, "movie": 1, "series": 2, "season": 3}.get(item.get("kind"), 9),
            str(item.get("display_title") or item.get("title") or "").casefold(),
        ))
        return result


@app.get("/api/v1/genres", dependencies=[Depends(require_client)])
def list_genres() -> list[str]:
    with closing(database()) as connection:
        rows = connection.execute(
            "SELECT genres FROM catalog_nodes WHERE available=1 AND genres!='[]'"
        ).fetchall()
    genres: list[str] = []
    for row in rows:
        try:
            values = json.loads(row["genres"] or "[]")
        except (TypeError, json.JSONDecodeError):
            continue
        genres.extend(normalize_genres(values))
    return sorted(normalize_genres(genres), key=str.casefold)


@app.get("/api/v1/profile-avatars", dependencies=[Depends(require_client)])
def list_catalog_avatars() -> list[dict[str, str]]:
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT * FROM catalog_nodes
               WHERE available=1 AND kind IN ('movie', 'series', 'collection')
               ORDER BY COALESCE(metadata_title, title) COLLATE NOCASE, id"""
        ).fetchall()
        result = []
        for row in rows:
            node = _node_payload(row, connection)
            poster = node.get("poster_url")
            if poster:
                result.append({"id": node["id"], "title": node.get("display_title") or node["title"],
                               "avatar": f"catalog:{node['id']}", "poster_url": poster})
        return result


@app.get("/api/v1/search", dependencies=[Depends(require_client)])
def search_catalog(
    q: str = Query(min_length=1, max_length=200),
    limit: Annotated[int, Query(ge=1, le=200)] = 100,
) -> list[dict]:
    """Search titles across catalog entries and individual series episodes."""
    query = q.strip()
    if not query:
        return []
    escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    pattern = f"%{escaped}%"
    with closing(database()) as connection:
        nodes = connection.execute(
            """SELECT * FROM catalog_nodes
               WHERE available=1 AND (title LIKE ? ESCAPE '!' COLLATE NOCASE
                                      OR metadata_title LIKE ? ESCAPE '!' COLLATE NOCASE)
               ORDER BY CASE WHEN title=? COLLATE NOCASE OR metadata_title=? COLLATE NOCASE THEN 0
                             WHEN title LIKE ? ESCAPE '!' COLLATE NOCASE
                               OR metadata_title LIKE ? ESCAPE '!' COLLATE NOCASE THEN 1 ELSE 2 END,
                        COALESCE(metadata_title, title) COLLATE NOCASE
               LIMIT ?""",
            (pattern, pattern, query, query, f"{escaped}%", f"{escaped}%", limit),
        ).fetchall()
        results = [_node_payload(row, connection) for row in nodes]

        episodes = connection.execute(
            """SELECT li.id, COALESCE(li.episode_title, li.title) AS title,
                      li.title AS filename, li.library, li.relative_path, li.extension,
                      li.size_bytes, li.modified_at, li.node_id, li.series_node_id AS series_id,
                      li.season_number, li.episode_number, li.poster_file, li.modified_at, n.title AS series_title,
                      n.metadata_title AS series_metadata_title
               FROM library_items li
               JOIN catalog_nodes n ON n.id=li.series_node_id AND n.available=1
               WHERE li.available=1 AND li.series_node_id IS NOT NULL
                 AND COALESCE(li.episode_title, li.title) LIKE ? ESCAPE '!' COLLATE NOCASE
               ORDER BY CASE WHEN COALESCE(li.episode_title, li.title)=? COLLATE NOCASE THEN 0
                             WHEN COALESCE(li.episode_title, li.title) LIKE ? ESCAPE '!' COLLATE NOCASE
                             THEN 1 ELSE 2 END,
                        COALESCE(li.episode_title, li.title) COLLATE NOCASE
               LIMIT ?""",
            (pattern, query, f"{escaped}%", limit),
        ).fetchall()
        for episode in episodes:
            season = connection.execute(
                "SELECT * FROM catalog_nodes WHERE id=? AND available=1",
                (episode["node_id"],),
            ).fetchone()
            season_payload = _node_payload(season, connection) if season else {}
            episode_poster_url = (_episode_poster_url(
                                      episode["id"], episode["poster_file"], episode["modified_at"])
                                  if episode["poster_file"] else season_payload.get("poster_url"))
            results.append({
                "id": episode["id"],
                "kind": "episode",
                "title": episode["title"],
                "display_title": episode["title"],
                "filename": episode["filename"],
                "library": episode["library"],
                "relative_path": episode["relative_path"],
                "extension": episode["extension"],
                "size_bytes": episode["size_bytes"],
                "modified_at": episode["modified_at"],
                "node_id": episode["node_id"],
                "series_id": episode["series_id"],
                "season_number": episode["season_number"],
                "episode_number": episode["episode_number"],
                "series_title": episode["series_metadata_title"] or episode["series_title"],
                "poster_url": episode_poster_url,
                "stream_url": f"/api/v1/items/{episode['id']}/stream",
            })

    query_folded = query.casefold()

    def result_order(item: dict) -> tuple[int, int, str]:
        title = str(item.get("display_title") or item.get("title") or "")
        folded = title.casefold()
        match_rank = 0 if folded == query_folded else 1 if folded.startswith(query_folded) else 2
        kind_rank = {"collection": 0, "movie": 1, "series": 2, "season": 3, "episode": 4}
        return match_rank, kind_rank.get(item.get("kind"), 9), folded

    results.sort(key=result_order)
    return results[:limit]


@app.get("/api/v1/catalog/{node_id}", dependencies=[Depends(require_client)])
def get_catalog_node(node_id: str) -> dict:
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND available=1", (node_id,)
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        return _node_payload(row, connection)


@app.get("/api/v1/catalog/{node_id}/missing-season-artwork",
         dependencies=[Depends(require_client)])
def get_missing_season_artwork(node_id: str) -> dict[str, object]:
    with closing(database()) as connection:
        row = connection.execute(
            """SELECT id, kind, tmdb_id, tmdb_episodes_json FROM catalog_nodes
               WHERE id=? AND available=1""",
            (node_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        if row["kind"] != "series":
            raise HTTPException(status_code=422, detail="Season artwork requires a series")
        missing = _series_completion(connection, row["id"], row["tmdb_episodes_json"])
        missing_numbers = set(missing["missing_seasons"])
        tmdb_id = row["tmdb_id"]
    if not missing_numbers or not tmdb_id or not TMDB_CONFIGURED:
        return {"seasons": []}

    cache_key = (int(tmdb_id), TMDB_LANGUAGE)
    now = time.monotonic()
    with SEASON_ART_CACHE_LOCK:
        cached = SEASON_ART_CACHE.get(cache_key)
        season_data = cached[1] if cached and cached[0] > now else []
    if not season_data:
        try:
            details = get_candidate_details(
                "tv", int(tmdb_id), TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY,
            )
            season_data = [dict(season) for season in details.get("seasons", [])]
            with SEASON_ART_CACHE_LOCK:
                SEASON_ART_CACHE[cache_key] = (now + SEASON_ART_CACHE_TTL_SECONDS, season_data)
        except Exception as exc:
            raise HTTPException(status_code=502,
                                detail=f"Could not load season artwork: {str(exc)[:300]}") from None
    return {"seasons": [
        {"season_number": int(season["season_number"]),
         "title": str(season.get("name") or f"Temporada {int(season['season_number']):02d}"),
         "poster_url": (f"https://image.tmdb.org/t/p/w342{season['poster_path']}"
                        if season.get("poster_path") else None)}
        for season in season_data
        if int(season.get("season_number") or 0) in missing_numbers
    ]}


TMDB_GENRE_NAMES = {
    12: "Aventura", 14: "Fantasía", 16: "Animación", 18: "Drama", 27: "Terror",
    28: "Acción", 35: "Comedia", 36: "Historia", 37: "Western", 53: "Suspense",
    80: "Crimen", 99: "Documental", 878: "Ciencia ficción", 9648: "Misterio",
    10402: "Música", 10749: "Romance", 10751: "Familia", 10752: "Bélica",
    10759: "Acción y aventura", 10762: "Infantil", 10763: "Noticias",
    10764: "Reality", 10765: "Ciencia ficción y fantasía", 10766: "Telenovela",
    10767: "Talk show", 10768: "Guerra y política", 10770: "Película de TV",
}


@app.get("/api/v1/catalog/{node_id}/recommendations",
         dependencies=[Depends(require_client)])
def get_catalog_recommendations(
        node_id: str,
        scope: str = Query(default="library", pattern="^(library|external)$"),
        page: int = Query(default=1, ge=1, le=500)) -> dict[str, object]:
    if not TMDB_CONFIGURED:
        return {"scope": scope, "items": [], "page": page, "next_page": None}
    with closing(database()) as connection:
        source = connection.execute(
            "SELECT id, kind, tmdb_id FROM catalog_nodes WHERE id=? AND available=1",
            (node_id,),
        ).fetchone()
        if source is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        if source["kind"] not in {"movie", "series"} or not source["tmdb_id"]:
            return {"scope": scope, "items": [], "page": page, "next_page": None}
        kind = str(source["kind"])
        tmdb_kind = "movie" if kind == "movie" else "tv"
        try:
            similar = get_similar_content(
                tmdb_kind, int(source["tmdb_id"]), TMDB_ACCESS_TOKEN,
                TMDB_LANGUAGE, page, TMDB_KEY,
            )
        except Exception:
            raise HTTPException(status_code=502,
                                detail="Could not load recommendations from TMDb") from None

        results = similar["results"]
        tmdb_ids = [int(item["id"]) for item in results
                    if isinstance(item, dict) and item.get("id") is not None]
        placeholders = ",".join("?" for _ in tmdb_ids)
        catalog_by_tmdb: dict[int, sqlite3.Row] = {}
        if tmdb_ids:
            rows = connection.execute(
                f"""SELECT id, kind, tmdb_id, title, metadata_title,
                           COALESCE(year, source_year) AS year, genres AS genres_json,
                           age_limit, poster_file
                    FROM catalog_nodes WHERE available=1 AND kind=? AND tmdb_id IN ({placeholders})""",
                (kind, *tmdb_ids),
            ).fetchall()
            for row in rows:
                catalog_by_tmdb.setdefault(int(row["tmdb_id"]), row)
        catalog_rows = connection.execute(
            "SELECT tmdb_id FROM catalog_nodes WHERE available=1 AND kind=? AND tmdb_id IS NOT NULL",
            (kind,),
        ).fetchall()
        catalog_ids = {int(row["tmdb_id"]) for row in catalog_rows}
        requested_content_type = "movie" if kind == "movie" else "tv"
        requested_rows = connection.execute(
            f"""SELECT DISTINCT tmdb_id FROM content_requests
                WHERE request_type='new_content' AND content_type=?
                  AND tmdb_id IN ({placeholders})""",
            (requested_content_type, *tmdb_ids),
        ).fetchall() if tmdb_ids else []
        requested_ids = {int(row["tmdb_id"]) for row in requested_rows}
        if kind == "movie":
            age_lookup_ids = (tmdb_ids if scope == "external" else [
                tmdb_id for tmdb_id, row in catalog_by_tmdb.items()
                if row["age_limit"] is None
            ])
            age_limits = get_movie_age_limits(
                age_lookup_ids, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY,
            )
        else:
            age_limits = {}

        items: list[dict[str, object]] = []
        for result in results:
            if not isinstance(result, dict) or result.get("id") is None:
                continue
            result_id = int(result["id"])
            if result_id == int(source["tmdb_id"]):
                continue
            local = catalog_by_tmdb.get(result_id)
            if scope == "library":
                if local is None:
                    continue
                genres = normalize_genres(json.loads(local["genres_json"] or "[]"))
                items.append({
                    "id": local["id"], "tmdb_id": result_id, "kind": kind,
                    "title": local["metadata_title"] or local["title"],
                    "year": local["year"], "genres": genres,
                    "age_limit": (local["age_limit"] if local["age_limit"] is not None
                                  else age_limits.get(result_id)),
                    "poster_url": (_artwork_url(local["id"], "poster", local["poster_file"])
                                   if local["poster_file"] else None),
                })
                continue
            if result_id in catalog_ids or result.get("adult") is True:
                continue
            title = result.get("title") if kind == "movie" else result.get("name")
            date = result.get("release_date") if kind == "movie" else result.get("first_air_date")
            year = int(date[:4]) if isinstance(date, str) and len(date) >= 4 and date[:4].isdigit() else None
            poster_path = result.get("poster_path")
            genre_ids = result.get("genre_ids") or []
            age_limit = age_limits.get(result_id)
            if result.get("adult") is True and (age_limit is None or age_limit < 18):
                age_limit = 18
            items.append({
                "id": f"tmdb-{kind}-{result_id}", "tmdb_id": result_id, "kind": kind,
                "title": str(title or "").strip(), "year": year,
                "genres": [TMDB_GENRE_NAMES[int(genre_id)] for genre_id in genre_ids
                           if str(genre_id).isdigit() and int(genre_id) in TMDB_GENRE_NAMES],
                "age_limit": age_limit,
                "requested": result_id in requested_ids,
                "poster_url": (f"https://image.tmdb.org/t/p/w342{poster_path}"
                               if isinstance(poster_path, str) and poster_path.startswith("/") else None),
            })
        current_page = int(similar.get("page") or page)
        total_pages = int(similar.get("total_pages") or 0)
        return {
            "scope": scope, "items": items, "page": current_page,
            "next_page": current_page + 1 if current_page < total_pages else None,
        }


@app.post("/api/v1/catalog/{node_id}/metadata/retry", status_code=202,
          dependencies=[Depends(require_admin)])
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


@app.get("/api/v1/catalog/metadata/retry/{job_id}", dependencies=[Depends(require_client)])
def get_metadata_retry_status(job_id: str) -> SyncJob:
    with closing(database()) as connection:
        row = connection.execute("SELECT * FROM sync_runs WHERE id=?", (job_id,)).fetchone()
    if row is None or not row["stage"].startswith("retry:"):
        raise HTTPException(status_code=404, detail="Metadata retry job not found")
    return _sync_job_payload(row)


@app.post("/api/v1/catalog/{node_id}/metadata/candidates", dependencies=[Depends(require_client)])
def catalog_metadata_candidates(node_id: str) -> dict[str, object]:
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
    with closing(database()) as connection:
        node = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND available=1", (node_id,),
        ).fetchone()
        if node is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        if node["kind"] not in {"movie", "series"}:
            raise HTTPException(status_code=422, detail="TMDb matching supports movies and series")

    now = time.monotonic()
    global METADATA_RETRY_GLOBAL_LAST_REQUEST
    with METADATA_RETRY_LOCK:
        global_wait = METADATA_RETRY_GLOBAL_COOLDOWN_SECONDS - (
            now - METADATA_RETRY_GLOBAL_LAST_REQUEST
        )
        previous = METADATA_RETRY_LAST_REQUEST.get(node_id)
        item_wait = (METADATA_RETRY_COOLDOWN_SECONDS - (now - previous)
                     if previous is not None else 0)
        retry_after = max(global_wait, item_wait)
        if retry_after > 0:
            raise HTTPException(
                status_code=429, detail="Metadata was recently requested for this item",
                headers={"Retry-After": str(max(1, int(retry_after)))},
            )
        METADATA_RETRY_LAST_REQUEST[node_id] = now
        METADATA_RETRY_GLOBAL_LAST_REQUEST = now

    try:
        candidates = search_metadata_candidates(
            node, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"TMDb search failed: {str(exc)[:300]}") from None
    return {"node_id": node_id, "candidates": candidates}


@app.get("/api/v1/catalog/{node_id}/metadata/candidate/{tmdb_id}",
         dependencies=[Depends(require_client)])
def catalog_metadata_candidate(node_id: str, tmdb_id: int) -> dict[str, object]:
    with closing(database()) as connection:
        node = connection.execute(
            "SELECT kind FROM catalog_nodes WHERE id=? AND available=1", (node_id,),
        ).fetchone()
    if node is None:
        raise HTTPException(status_code=404, detail="Catalog node not found")
    if node["kind"] not in {"movie", "series"}:
        raise HTTPException(status_code=422, detail="TMDb matching supports movies and series")
    try:
        return get_candidate_details(
            node["kind"], tmdb_id, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Could not load TMDb candidate: {str(exc)[:300]}") from None


@app.get("/api/v1/catalog/{node_id}/metadata/images", dependencies=[Depends(require_client)])
def catalog_metadata_images(node_id: str, q: str | None = None,
                            tmdb_id: int | None = Query(default=None, gt=0)) -> dict[str, object]:
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
    with closing(database()) as connection:
        node = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND available=1", (node_id,),
        ).fetchone()
        if node is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        search_node = node
        if node["kind"] == "season":
            search_node = connection.execute(
                "SELECT s.* FROM catalog_nodes s JOIN catalog_nodes season ON season.parent_id=s.id "
                "WHERE season.id=? AND s.kind='series' AND s.available=1",
                (node_id,),
            ).fetchone()
            if search_node is None:
                raise HTTPException(status_code=422, detail="Season has no available series")
    image_id = tmdb_id or search_node["tmdb_id"]
    try:
        images = search_image_options(
            "series" if search_node["kind"] == "series" else "movie",
            q or search_node["metadata_title"] or search_node["title"],
            TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY, image_id,
            int(node["season_number"] or 0) if node["kind"] == "season" else None,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Image search failed: {str(exc)[:300]}") from None
    return {"images": images[:10]}


@app.get("/api/v1/content-requests/search", dependencies=[Depends(require_client)])
def search_content_request_titles(q: str = Query(min_length=2, max_length=100)) -> dict[str, object]:
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
    try:
        results = search_content_requests(q, TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"TMDb search failed: {str(exc)[:300]}") from None
    return {"results": results}


@app.get("/api/v1/content-requests/tmdb/{content_type}/{tmdb_id}",
         dependencies=[Depends(require_client)])
def get_content_request_title(content_type: str, tmdb_id: int) -> dict[str, object]:
    if content_type not in {"movie", "tv"}:
        raise HTTPException(status_code=422, detail="Content type must be movie or tv")
    if not TMDB_CONFIGURED:
        raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
    try:
        details = get_candidate_details(content_type, tmdb_id, TMDB_ACCESS_TOKEN,
                                        TMDB_LANGUAGE, TMDB_KEY)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Could not load TMDb title: {str(exc)[:300]}") from None
    title = str(details.get("metadata_title") or "").strip()
    poster_path = details.get("poster_path")
    backdrop_path = details.get("backdrop_path")
    with closing(database()) as connection:
        requested = connection.execute(
            """SELECT 1 FROM content_requests
               WHERE request_type='new_content' AND content_type=? AND tmdb_id=? LIMIT 1""",
            (content_type, tmdb_id),
        ).fetchone() is not None
    return {
        **details,
        "id": f"tmdb-{content_type}-{tmdb_id}",
        "kind": "movie" if content_type == "movie" else "series",
        "title": title,
        "display_title": title,
        "library": "movies" if content_type == "movie" else "series",
        "poster_url": (f"https://image.tmdb.org/t/p/w500{poster_path}"
                       if isinstance(poster_path, str) and poster_path.startswith("/") else None),
        "backdrop_url": (f"https://image.tmdb.org/t/p/w1280{backdrop_path}"
                         if isinstance(backdrop_path, str) and backdrop_path.startswith("/") else None),
        "children": [],
        "files": [],
        "is_external_recommendation": True,
        "requested": requested,
    }


@app.post("/api/v1/content-requests", status_code=201,
          dependencies=[Depends(require_client)])
def create_content_request(request: ContentRequestCreate,
                           device_id: Annotated[str, Depends(require_client)]) -> dict[str, object]:
    requester_id = account_for_device(device_id)
    now = datetime.now(timezone.utc).isoformat()
    records: list[dict[str, object]] = []
    with closing(database()) as connection:
        account = connection.execute(
            "SELECT COALESCE(NULLIF(username, ''), email) AS name FROM accounts WHERE id=?",
            (requester_id,),
        ).fetchone()
        if account is None:
            raise HTTPException(status_code=401, detail="Account not found")

        if request.request_type == "remaining_seasons":
            if (not request.catalog_node_id or request.tmdb_id is not None
                    or request.items or request.content_type is not None):
                raise HTTPException(status_code=422, detail="A series catalog item is required")
            series = connection.execute(
                """SELECT id, kind, title, metadata_title, year, tmdb_id, tmdb_episodes_json,
                          poster_file
                   FROM catalog_nodes WHERE id=? AND available=1""",
                (request.catalog_node_id,),
            ).fetchone()
            if series is None or series["kind"] != "series":
                raise HTTPException(status_code=404, detail="Series not found")
            completion = _series_completion(connection, series["id"], series["tmdb_episodes_json"])
            if not completion["incomplete"]:
                raise HTTPException(status_code=409, detail="This series has no known missing seasons or episodes")
            missing_seasons = completion["missing_seasons"]
            missing_episode_count = completion["missing_episode_count"]
            missing_episode_numbers: list[int] = []
            if request.season_number is not None:
                season_number = request.season_number
                season_missing = next((item for item in completion["missing_episodes"]
                                       if int(item["season_number"]) == season_number), None)
                if season_missing is None:
                    raise HTTPException(status_code=409,
                                        detail="This season has no known missing episodes")
                missing_seasons = [season_number] if season_number in missing_seasons else []
                missing_episode_numbers = list(season_missing["episode_numbers"])
                missing_episode_count = len(missing_episode_numbers)
                exists = connection.execute(
                    """SELECT 1 FROM content_requests
                       WHERE request_type='remaining_seasons' AND catalog_node_id=?
                         AND season_number=? AND status IN ('pending', 'sending', 'approved')
                       LIMIT 1""",
                    (series["id"], season_number),
                ).fetchone()
            else:
                exists = connection.execute(
                    """SELECT 1 FROM content_requests
                       WHERE request_type='remaining_seasons' AND catalog_node_id=?
                         AND season_number IS NULL AND status IN ('pending', 'sending', 'approved')
                       LIMIT 1""",
                    (series["id"],),
                ).fetchone()
            if exists is not None:
                raise HTTPException(status_code=409, detail="This season is already requested")
            poster_url = (_artwork_url(series["id"], "poster", series["poster_file"])
                          if series["poster_file"] else None)
            if not poster_url:
                fallback_poster = _series_fallback_poster(connection, series["id"])
                if fallback_poster:
                    poster_url = _artwork_url(
                        fallback_poster[0], "poster", fallback_poster[1],
                        fallback_version=fallback_poster[2],
                    )
            details = {
                "missing_seasons": missing_seasons,
                "missing_episode_count": missing_episode_count,
                "poster_url": poster_url,
            }
            if request.season_number is not None:
                details["season_number"] = request.season_number
                details["missing_episode_numbers"] = missing_episode_numbers
            records.append({
                "request_type": request.request_type,
                "content_type": "tv",
                "tmdb_id": series["tmdb_id"],
                "title": series["metadata_title"] or series["title"],
                "year": series["year"],
                "season_number": request.season_number,
                "catalog_node_id": series["id"],
                "details": details,
            })
        else:
            if request.items:
                if request.content_type is not None or request.tmdb_id is not None or request.season_number is not None:
                    raise HTTPException(status_code=422, detail="Use either items or a single content selection")
                selected_items = request.items
            else:
                if request.content_type not in {"movie", "tv"} or request.tmdb_id is None:
                    raise HTTPException(status_code=422, detail="Select a movie or series from TMDb")
                selected_items = [ContentRequestItem(
                    content_type=request.content_type,
                    tmdb_id=request.tmdb_id,
                    season_number=request.season_number,
                )]
            if not TMDB_CONFIGURED:
                raise HTTPException(status_code=503, detail="TMDb credentials are not configured")
            selected_keys: list[tuple[str, int, int | None]] = []
            for item in selected_items:
                if item.content_type == "movie" and item.season_number is not None:
                    raise HTTPException(status_code=422, detail="Movies do not have seasons")
                if any(content_type == item.content_type and tmdb_id == item.tmdb_id
                       and (season is None or item.season_number is None or season == item.season_number)
                       for content_type, tmdb_id, season in selected_keys):
                    raise HTTPException(status_code=409, detail="This content has already been requested")
                selected_keys.append((item.content_type, item.tmdb_id, item.season_number))
                already_requested = connection.execute(
                    """SELECT 1 FROM content_requests
                       WHERE request_type='new_content' AND content_type=? AND tmdb_id=?
                         AND (season_number IS NULL OR ? IS NULL OR season_number=?)
                       LIMIT 1""",
                    (item.content_type, item.tmdb_id, item.season_number, item.season_number),
                ).fetchone()
                if already_requested:
                    raise HTTPException(status_code=409, detail="This content has already been requested")
                try:
                    candidate = get_candidate_details(
                        item.content_type, item.tmdb_id,
                        TMDB_ACCESS_TOKEN, TMDB_LANGUAGE, TMDB_KEY,
                    )
                except Exception as exc:
                    raise HTTPException(status_code=502,
                                        detail=f"Could not load TMDb title: {str(exc)[:300]}") from None
                if item.season_number is not None and item.season_number not in {
                        int(season["season_number"]) for season in candidate.get("seasons", [])}:
                    raise HTTPException(status_code=422, detail="Selected season was not found on TMDb")
                details = {
                    "overview": candidate.get("overview", ""),
                    "season_count": len(candidate.get("seasons", [])),
                    "poster_path": candidate.get("poster_path"),
                }
                records.append({
                    "request_type": request.request_type,
                    "content_type": item.content_type,
                    "tmdb_id": item.tmdb_id,
                    "title": str(candidate.get("metadata_title") or "").strip(),
                    "year": candidate.get("year"),
                    "season_number": item.season_number,
                    "catalog_node_id": None,
                    "details": details,
                })

        request_ids = []
        for record in records:
            request_id = uuid4().hex
            request_ids.append(request_id)
            connection.execute(
                """INSERT INTO content_requests(
                       id, requester_account_id, requester_name, request_type, content_type,
                       tmdb_id, title, year, season_number, catalog_node_id, details_json,
                       status, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)""",
                (request_id, requester_id, account["name"], record["request_type"],
                 record["content_type"], record["tmdb_id"], record["title"], record["year"],
                 record["season_number"], record["catalog_node_id"],
                 json.dumps(record["details"], ensure_ascii=False), now),
            )
        connection.commit()
    return {"id": request_ids[0], "ids": request_ids, "count": len(request_ids),
            "status": "pending", "created_at": now}


@app.get("/api/v1/admin/content-requests", dependencies=[Depends(require_admin)])
def list_content_requests() -> list[dict[str, object]]:
    with closing(database()) as connection:
        rows = connection.execute(
            """SELECT * FROM content_requests
               ORDER BY CASE status WHEN 'pending' THEN 0 WHEN 'sending' THEN 1 ELSE 2 END,
                        created_at DESC"""
        ).fetchall()
        incoming_request_ids: set[str] = set()
        try:
            incoming_request_ids = {
                str(item["request_id"]) for item in _incoming_entries(connection)
                if item.get("request_id")
            }
        except HTTPException:
            pass
    results = []
    for row in rows:
        if row["status"] == "approved" and row["id"] in incoming_request_ids:
            continue
        details = json.loads(row["details_json"] or "{}")
        results.append({**{key: row[key] for key in (
        "id", "requester_name", "request_type", "content_type", "tmdb_id",
        "title", "year", "season_number", "catalog_node_id", "status", "created_at",
        "telegram_sent_at",
        )}, "details": details,
        "poster_path": details.get("poster_path"),
        "poster_url": details.get("poster_url")})
    return results


def content_request_thumbnail(connection: sqlite3.Connection, request: sqlite3.Row,
                               details: dict[str, object]) -> bytes | None:
    poster_path = details.get("poster_path")
    if isinstance(poster_path, str) and poster_path.startswith("/"):
        response = httpx.get(f"https://image.tmdb.org/t/p/w500{poster_path}", timeout=15.0)
        response.raise_for_status()
        if len(response.content) > 10 * 1024 * 1024:
            raise RuntimeError("La miniatura supera el tamaño permitido para Telegram")
        return response.content

    node_id = request["catalog_node_id"]
    if node_id:
        node = connection.execute(
            "SELECT id, poster_file FROM catalog_nodes WHERE id=?", (node_id,),
        ).fetchone()
        poster_file = node["poster_file"] if node else None
        if not poster_file and node:
            fallback = _series_fallback_poster(connection, node["id"])
            if fallback:
                poster_file = fallback[1]
        if poster_file:
            artwork_root = ARTWORK_DIR.resolve()
            artwork_path = (artwork_root / poster_file).resolve()
            if artwork_root not in artwork_path.parents:
                raise RuntimeError("La ruta de la miniatura no es válida")
            image = artwork_path.read_bytes()
            if len(image) > 10 * 1024 * 1024:
                raise RuntimeError("La miniatura supera el tamaño permitido para Telegram")
            return image
    return None


def content_request_telegram_caption(request: sqlite3.Row,
                                     details: dict[str, object]) -> str:
    title = str(request["title"] or "(sin título)").strip()
    if request["request_type"] == "remaining_seasons":
        lines = ["Solicitud de contenido para Zinema", f"Serie: {title}"]
        lines.append(f"Año: {request['year']}" if request["year"] else "Año: desconocido")
        missing = details.get("missing_seasons") or []
        season_number = details.get("season_number") or request["season_number"]
        requested_episodes = details.get("missing_episode_numbers") or []
        if season_number is not None and int(season_number) in {int(value) for value in missing}:
            lines.append(f"Solicita temporada completa: T{int(season_number):02d}")
        elif season_number is not None and requested_episodes:
            numbers = ", ".join(f"E{int(number):02d}" for number in requested_episodes)
            lines.append(f"Solicita capítulos restantes de T{int(season_number):02d}: {numbers}")
        elif missing:
            lines.append("Solicita temporadas restantes: " + ", ".join(f"T{int(number)}" for number in missing))
        elif details.get("missing_episode_count"):
            lines.append(f"Solicita capítulos restantes: {int(details['missing_episode_count'])}")
    else:
        content_type = "Película" if request["content_type"] == "movie" else "Serie"
        lines = ["Solicitud de contenido para Zinema", f"Título: {title}", f"Tipo: {content_type}"]
        lines.append(f"Año: {request['year']}" if request["year"] else "Año: desconocido")
        if request["content_type"] == "tv":
            season_number = request["season_number"]
            lines.append(f"Temporada solicitada: {season_number}" if season_number
                         else "Solicitud: serie completa")
    lines.append(f"Solicitante: {request['requester_name']}")
    lines.append(f"Solicitud ID: {request['id']}")
    return "\n".join(lines)[:950]


@app.post("/api/v1/admin/content-requests/{request_id}/approve",
          dependencies=[Depends(require_admin)])
def approve_content_request(request_id: str,
                            device_id: Annotated[str, Depends(require_admin)]) -> dict[str, bool]:
    reviewer_id = account_for_device(device_id)
    with closing(database()) as connection:
        request = connection.execute(
            "SELECT * FROM content_requests WHERE id=?", (request_id,),
        ).fetchone()
        if request is None:
            raise HTTPException(status_code=404, detail="Content request not found")
        if request["status"] != "pending":
            raise HTTPException(status_code=409, detail="Content request is not pending")
        now = datetime.now(timezone.utc).isoformat()
        cursor = connection.execute(
            """UPDATE content_requests SET status='sending', reviewed_at=?,
                   reviewed_by_account_id=?, telegram_error=NULL
               WHERE id=? AND status='pending'""",
            (now, reviewer_id, request_id),
        )
        connection.commit()
        if cursor.rowcount == 0:
            raise HTTPException(status_code=409, detail="Content request is already being reviewed")
        request_data = dict(request)
        details = json.loads(request_data["details_json"] or "{}")

    try:
        with closing(database()) as connection:
            thumbnail = content_request_thumbnail(connection, request_data, details)
        message_id = send_telegram_message(content_request_telegram_caption(request_data, details), thumbnail)
    except Exception as exc:
        error = str(exc)[:500]
        with closing(database()) as connection:
            connection.execute(
                """UPDATE content_requests SET status='pending', reviewed_at=NULL,
                       reviewed_by_account_id=NULL, telegram_error=?
                   WHERE id=? AND status='sending'""",
                (error, request_id),
            )
            connection.commit()
        raise HTTPException(status_code=502, detail=f"Telegram send failed: {error}") from None

    sent_at = datetime.now(timezone.utc).isoformat()
    with closing(database()) as connection:
        connection.execute(
            """UPDATE content_requests SET status='approved', reviewed_at=?,
                   reviewed_by_account_id=?, telegram_sent_at=?, telegram_message_id=?,
                   telegram_error=NULL WHERE id=? AND status='sending'""",
            (sent_at, reviewer_id, sent_at, message_id, request_id),
        )
        connection.commit()
    return {"approved": True}


@app.post("/api/v1/admin/content-requests/{request_id}/resend",
          dependencies=[Depends(require_admin)])
def resend_content_request(request_id: str) -> dict[str, bool]:
    with closing(database()) as connection:
        request = connection.execute(
            "SELECT * FROM content_requests WHERE id=? AND status='approved'", (request_id,),
        ).fetchone()
        if request is None:
            raise HTTPException(status_code=404, detail="Validated content request not found")
        request_data = dict(request)
        details = json.loads(request_data["details_json"] or "{}")

    try:
        with closing(database()) as connection:
            thumbnail = content_request_thumbnail(connection, request_data, details)
        message_id = send_telegram_message(content_request_telegram_caption(request_data, details), thumbnail)
    except Exception as exc:
        error = str(exc)[:500]
        with closing(database()) as connection:
            connection.execute("UPDATE content_requests SET telegram_error=? WHERE id=?",
                               (error, request_id))
            connection.commit()
        raise HTTPException(status_code=502, detail=f"Telegram resend failed: {error}") from None

    sent_at = datetime.now(timezone.utc).isoformat()
    with closing(database()) as connection:
        connection.execute(
            """UPDATE content_requests SET telegram_sent_at=?, telegram_message_id=?,
                   telegram_error=NULL WHERE id=? AND status='approved'""",
            (sent_at, message_id, request_id),
        )
        connection.commit()
    return {"resent": True}


@app.delete("/api/v1/admin/content-requests/{request_id}",
            dependencies=[Depends(require_admin)])
def delete_content_request(request_id: str) -> dict[str, bool]:
    with closing(database()) as connection:
        cursor = connection.execute("DELETE FROM content_requests WHERE id=?", (request_id,))
        connection.commit()
    if cursor.rowcount == 0:
        raise HTTPException(status_code=404, detail="Content request not found")
    return {"deleted": True}


@app.post("/api/v1/catalog/{node_id}/metadata/manual", dependencies=[Depends(require_admin)])
def save_manual_metadata(node_id: str, request: ManualMetadataRequest) -> dict[str, object]:
    with closing(database()) as connection:
        node = connection.execute(
            "SELECT * FROM catalog_nodes WHERE id=? AND available=1", (node_id,),
        ).fetchone()
        if node is None:
            raise HTTPException(status_code=404, detail="Catalog node not found")
        if request.tmdb_id is not None and node["kind"] in {"movie", "series"}:
            try:
                apply_tmdb_id(
                    connection, node, request.tmdb_id, TMDB_ACCESS_TOKEN,
                    TMDB_LANGUAGE, ARTWORK_DIR, api_key=TMDB_KEY,
                )
            except Exception as exc:
                raise HTTPException(status_code=502, detail=f"TMDb selection failed: {str(exc)[:300]}") from None

        poster_file = None
        if request.image_path:
            if (not request.image_path.startswith("/") or ".." in request.image_path
                    or not re.fullmatch(r"/[A-Za-z0-9._/-]+", request.image_path)):
                raise HTTPException(status_code=422, detail="Invalid TMDb image path")
            try:
                with httpx.Client(timeout=httpx.Timeout(30.0)) as client:
                    image_base, poster_size, backdrop_size = _image_config(client, TMDB_KEY)
                    size = backdrop_size if request.image_type == "backdrop" else poster_size
                    poster_file = _save_image(
                        client, image_base, size, request.image_path,
                        ARTWORK_DIR / f"{node_id}-poster.jpg",
                    )
            except Exception as exc:
                raise HTTPException(status_code=502, detail=f"Could not save selected image: {str(exc)[:300]}") from None

        genres = normalize_genres(request.genres)
        connection.execute(
            """UPDATE catalog_nodes SET metadata_title=?, overview=?, year=?, genres=?,
               tmdb_id=COALESCE(?, tmdb_id), poster_file=COALESCE(?, poster_file),
               metadata_status='matched', metadata_source='manual', metadata_checked_at=?,
               metadata_candidates=NULL, metadata_error=NULL WHERE id=?""",
            (request.metadata_title.strip(), request.overview.strip(), request.year,
             json.dumps(genres, ensure_ascii=False), request.tmdb_id, poster_file,
             datetime.now(timezone.utc).isoformat(), node_id),
        )
        updated = connection.execute("SELECT * FROM catalog_nodes WHERE id=?", (node_id,)).fetchone()
        connection.commit()
        return _node_payload(updated, connection)


@app.get("/api/v1/catalog/{node_id}/images/{image_type}", dependencies=[Depends(require_client)])
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


@app.get("/api/v1/items/{item_id}/poster", dependencies=[Depends(require_client)])
def get_episode_poster(item_id: str):
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT poster_file FROM library_items WHERE id=? AND available=1", (item_id,)
        ).fetchone()
    if row is None or not row["poster_file"]:
        raise HTTPException(status_code=404, detail="Episode image not found")
    image_path = (ARTWORK_DIR / row["poster_file"]).resolve()
    try:
        image_path.relative_to(ARTWORK_DIR.resolve())
    except ValueError:
        raise HTTPException(status_code=404, detail="Episode image not found") from None
    if not image_path.is_file():
        raise HTTPException(status_code=404, detail="Episode image not found")
    return FileResponse(image_path)


@app.get("/api/v1/admin/incoming", dependencies=[Depends(require_admin)])
def list_incoming_videos() -> list[dict[str, object]]:
    _scan_incoming_download_activity()
    with closing(database()) as connection:
        return _incoming_entries(connection)


@app.post("/api/v1/admin/incoming-download", status_code=202,
          dependencies=[Depends(require_admin)])
def start_nas_catalog_download() -> dict[str, object]:
    if not NAS_DOWNLOADER_SCRIPT.is_file():
        raise HTTPException(status_code=503, detail="NAS downloader script is not mounted")
    _scan_incoming_download_activity()
    with NAS_DOWNLOAD_LOCK:
        if NAS_DOWNLOAD_STATE["status"] in {"starting", "running"}:
            raise HTTPException(status_code=409, detail="A NAS download is already running")
        job_id = uuid4().hex
        NAS_DOWNLOAD_STATE.update({
            "id": job_id, "status": "starting", "progress": "Iniciando descarga…",
            "current_file": None, "current_title": None, "current_percent": None,
            "downloaded_bytes": 0, "total_bytes": 0,
            "current_bytes": 0, "current_path": None, "source": "app",
            "started_at": datetime.now(timezone.utc).isoformat(), "completed_at": None,
            "exit_code": None, "error": None, "_awaiting_file_settle": False,
        })
    INCOMING_MONITOR_WAKE.set()
    threading.Thread(target=_run_nas_download, args=(job_id,), daemon=True,
                     name=f"nas-download-{job_id[:8]}").start()
    return {"id": job_id, "status": "starting"}


@app.get("/api/v1/admin/incoming-download", dependencies=[Depends(require_admin)])
def get_nas_catalog_download(refresh: bool = False) -> dict[str, object]:
    if refresh:
        _scan_incoming_download_activity()
    with NAS_DOWNLOAD_LOCK:
        payload = {key: value for key, value in NAS_DOWNLOAD_STATE.items()
                   if not key.startswith("_")}
        return payload


@app.get("/api/v1/download-status", dependencies=[Depends(require_client)])
def get_catalog_download_status() -> dict[str, object]:
    """Expose only the active download indicator to signed-in catalog clients."""
    with NAS_DOWNLOAD_LOCK:
        state = NAS_DOWNLOAD_STATE
        return {
            "status": state.get("status", "idle"),
            "current_file": state.get("current_file"),
            "current_title": state.get("current_title"),
            "current_percent": state.get("current_percent"),
        }


@app.post("/api/v1/admin/incoming/{file_id}/generate", status_code=200,
          dependencies=[Depends(require_admin)])
def generate_incoming_destination(file_id: str,
                                  request: IncomingGenerateRequest) -> dict[str, object]:
    _scan_incoming_download_activity()
    if not INCOMING_MOVE_LOCK.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="Another incoming file is being moved")
    try:
        with closing(database()) as connection:
            entry = next((item for item in _incoming_entries(connection)
                          if item["file_id"] == file_id), None)
        if entry is None:
            raise HTTPException(status_code=404, detail="Incoming video not found; detect files again")

        source = (INCOMING_ROOT / str(entry["relative_path"])).resolve(strict=True)
        try:
            source.relative_to(INCOMING_ROOT)
        except ValueError:
            raise HTTPException(status_code=400, detail="Incoming source path is invalid") from None
        if source.is_symlink() or not source.is_file() or source.suffix.lower() not in VIDEO_EXTENSIONS:
            raise HTTPException(status_code=422, detail="Incoming source is not a supported video")

        submitted_destination = Path(request.destination_path).resolve(strict=False)
        library = None
        relative_destination = None
        for candidate_library, display_root in MEDIA_DISPLAY_ROOTS.items():
            try:
                relative_destination = submitted_destination.relative_to(display_root)
                library = candidate_library
                break
            except ValueError:
                continue
        if library is None or relative_destination is None or not relative_destination.parts \
                or ".." in relative_destination.parts:
            raise HTTPException(status_code=422, detail="Choose a destination inside a media library")
        if submitted_destination.suffix.lower() != source.suffix.lower():
            raise HTTPException(status_code=422, detail="Keep the source video's file extension")

        media_root = MEDIA_ROOTS[library]
        destination = (media_root / relative_destination).resolve(strict=False)
        try:
            destination.relative_to(media_root)
        except ValueError:
            raise HTTPException(status_code=422, detail="Destination escapes the media library") from None
        try:
            destination.relative_to(INCOMING_ROOT)
            raise HTTPException(status_code=422, detail="Destination cannot be inside Incoming")
        except ValueError:
            pass

        destination.parent.mkdir(parents=True, exist_ok=True)
        destination = destination.resolve(strict=False)
        try:
            destination.relative_to(media_root)
        except ValueError:
            raise HTTPException(status_code=422, detail="Destination escapes the media library") from None
        if destination.exists() or destination.is_symlink():
            raise HTTPException(status_code=409, detail="A file already exists at the destination")

        try:
            shutil.move(str(source), str(destination))
        except OSError as exc:
            raise HTTPException(status_code=500, detail=f"Could not move incoming video: {exc}") from None

        request_matched = bool(entry["request_matched"])
        sync_job_id = _start_sync_job()
        sync_queued = False
        previous_sync_job_id = ""
        if sync_job_id is None:
            sync_queued = _schedule_sync_when_available()
            if sync_queued:
                with closing(database()) as connection:
                    latest = connection.execute(
                        "SELECT id FROM sync_runs ORDER BY started_at DESC LIMIT 1"
                    ).fetchone()
                previous_sync_job_id = latest["id"] if latest else ""
        return {
            "moved": True,
            "destination_path": str(MEDIA_DISPLAY_ROOTS[library]
                                    / relative_destination),
            "request_matched": request_matched,
            "catalog_sync_job_id": sync_job_id,
            "catalog_sync_queued": sync_queued,
            "catalog_sync_previous_job_id": previous_sync_job_id,
        }
    finally:
        INCOMING_MOVE_LOCK.release()


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


@app.post("/api/v1/items/{item_id}/playback-ticket")
def create_playback_ticket(item_id: str, device_id: str = Depends(require_client)) -> dict[str, object]:
    get_media_file(item_id)
    now = time.time()
    ticket = secrets.token_urlsafe(32)
    with PLAYBACK_TICKET_LOCK:
        expired = [key for key, (_, expiry, _) in PLAYBACK_TICKETS.items() if expiry <= now]
        for key in expired:
            PLAYBACK_TICKETS.pop(key, None)
        PLAYBACK_TICKETS[ticket] = (item_id, now + PLAYBACK_TICKET_TTL_SECONDS, device_id)
    return {
        "url": f"/api/v1/playback/{ticket}",
        "expires_in": PLAYBACK_TICKET_TTL_SECONDS,
    }


@app.get("/api/v1/playback/{ticket}")
def stream_with_playback_ticket(ticket: str, request: Request):
    now = time.time()
    with PLAYBACK_TICKET_LOCK:
        grant = PLAYBACK_TICKETS.get(ticket)
        if grant is None or grant[1] <= now:
            PLAYBACK_TICKETS.pop(ticket, None)
            raise HTTPException(status_code=401, detail="Playback ticket expired or invalid")
        item_id = grant[0]
    return _stream_item(item_id, request)


@app.get("/api/v1/items/{item_id}/stream", dependencies=[Depends(require_client)])
def stream_item(item_id: str, request: Request):
    return _stream_item(item_id, request)


def _stream_item(item_id: str, request: Request):
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
    _start_episode_thumbnail_job()
    return ScanResult(**result)
