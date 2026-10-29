from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import sqlite3

VIDEO_EXTENSIONS = {
    ".avi", ".m2ts", ".m4v", ".mkv", ".mov", ".mp4", ".mpeg", ".mpg",
    ".mts", ".ts", ".vob", ".webm", ".wmv",
}
SEASON_RE = re.compile(r"^(?:season|temporada)\s*0*(\d+)(?:\b|\s|$)", re.IGNORECASE)
EPISODE_RE = re.compile(
    r"(?:(?<![A-Za-z0-9])(?:s|t)\s*(\d{1,2})[\s,._-]*e\s*(\d{1,3})"
    r"|(?<![A-Za-z0-9])(\d{1,2})[x×](\d{1,3})(?![A-Za-z0-9]))",
    re.IGNORECASE,
)
YEAR_RE = re.compile(r"\s*\(((?:19|20)\d{2})\)\s*$")


def stable_id(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:32]


def display_title(value: str) -> str:
    return Path(value).stem.replace(".", " ").replace("_", " ").strip()


def _episode_numbers(filename: str, default_season: int = 1) -> tuple[int, int | None]:
    match = EPISODE_RE.search(filename)
    if not match:
        return default_season, None
    if match.group(1) is not None:
        return int(match.group(1)), int(match.group(2))
    return int(match.group(3)), int(match.group(4))


def _season_number(dirname: str) -> int | None:
    match = SEASON_RE.match(dirname)
    return int(match.group(1)) if match else None


def scan_catalog(connection: sqlite3.Connection, roots: dict[str, Path]) -> dict[str, int | str]:
    """Reconcile media files and the inferred folder hierarchy into SQLite."""
    for library, root in roots.items():
        if not root.is_dir():
            raise FileNotFoundError(f"{library.title()} library is not mounted: {root}")

    now = datetime.now(timezone.utc).isoformat()
    nodes: dict[str, dict[str, object]] = {}
    files: list[tuple[object, ...]] = []

    def add_node(library: str, kind: str, relative: PurePosixPath | str,
                 title: str, parent_id: str | None, season_number: int | None = None,
                 source_year: int | None = None) -> str:
        rel = relative.as_posix() if isinstance(relative, PurePosixPath) else relative
        node_id = stable_id(f"{library}:{kind}:{rel}")
        nodes[node_id] = {
            "id": node_id, "library": library, "kind": kind,
            "relative_path": rel, "parent_id": parent_id, "title": title,
            "season_number": season_number, "source_year": source_year,
            "scanned_at": now, "added_at": now,
        }
        return node_id

    def add_collections(library: str, parts: tuple[str, ...]) -> str | None:
        parent_id = None
        prefix: list[str] = []
        for part in parts:
            prefix.append(part)
            relative = PurePosixPath(*prefix)
            parent_id = add_node(library, "collection", relative, part, parent_id)
        return parent_id

    for library, root in roots.items():
        for media_path in root.rglob("*"):
            try:
                if media_path.is_symlink() or not media_path.is_file() or media_path.suffix.lower() not in VIDEO_EXTENSIONS:
                    continue
                stat = media_path.stat()
            except OSError:
                continue

            relative = PurePosixPath(media_path.relative_to(root).as_posix())
            item_id = stable_id(f"{library}:{relative.as_posix()}")
            node_id: str
            series_node_id: str | None = None
            season_number: int | None = None
            episode_number: int | None = None

            if library == "movies":
                folder = relative.parent
                direct_file = str(folder) == "."
                movie_relative = relative if direct_file else folder
                title = display_title(media_path.name) if direct_file else movie_relative.name
                year_match = YEAR_RE.search(title)
                source_year = int(year_match.group(1)) if year_match else None
                if year_match:
                    title = title[:year_match.start()].strip()
                collection_parts = () if direct_file else movie_relative.parts[:-1]
                parent_id = add_collections(library, collection_parts)
                node_id = add_node(library, "movie", movie_relative, title, parent_id,
                                   source_year=source_year)
            else:
                season_index = next(
                    (index for index, part in enumerate(relative.parts[:-1])
                     if _season_number(part) is not None),
                    None,
                )
                if season_index is not None:
                    show_parts = relative.parts[:season_index]
                    season_dir = relative.parts[season_index]
                    season_number = _season_number(season_dir)
                    if not show_parts:
                        show_parts = (display_title(media_path.name),)
                    series_relative = PurePosixPath(*show_parts)
                    season_relative = PurePosixPath(*show_parts, season_dir)
                else:
                    show_parts = relative.parts[:-1]
                    season_number, episode_number = _episode_numbers(media_path.name)
                    if not show_parts:
                        show_parts = (display_title(media_path.name),)
                    series_relative = PurePosixPath(*show_parts)
                    season_relative = PurePosixPath(*show_parts, f"Season {season_number:02d}")

                collection_parent = add_collections(library, show_parts[:-1])
                series_node_id = add_node(library, "series", series_relative,
                                          series_relative.name, collection_parent)
                season_node_id = add_node(
                    library, "season", season_relative,
                    f"Temporada {season_number:02d}", series_node_id,
                    season_number=season_number,
                )
                node_id = season_node_id
                if episode_number is None:
                    _, episode_number = _episode_numbers(media_path.name, season_number or 1)

            modified_at = datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()
            files.append((item_id, library, display_title(media_path.name), relative.as_posix(),
                          media_path.suffix.lower(), stat.st_size, modified_at, now,
                          node_id, series_node_id, season_number, episode_number))

    connection.execute("CREATE TEMP TABLE IF NOT EXISTS scan_files (id TEXT PRIMARY KEY)")
    connection.execute("DELETE FROM scan_files")
    connection.executemany("INSERT INTO scan_files (id) VALUES (?)", [(row[0],) for row in files])

    connection.execute("CREATE TEMP TABLE IF NOT EXISTS scan_nodes (id TEXT PRIMARY KEY)")
    connection.execute("DELETE FROM scan_nodes")
    connection.executemany("INSERT INTO scan_nodes (id) VALUES (?)", [(node_id,) for node_id in nodes])

    connection.executemany(
        """INSERT INTO catalog_nodes
           (id, library, kind, relative_path, parent_id, title, season_number, source_year,
            scanned_at, added_at, available)
           VALUES (:id, :library, :kind, :relative_path, :parent_id, :title, :season_number,
                   :source_year, :scanned_at, :added_at, 1)
           ON CONFLICT(id) DO UPDATE SET
             parent_id=excluded.parent_id, title=excluded.title,
             season_number=excluded.season_number, source_year=excluded.source_year,
             scanned_at=excluded.scanned_at, available=1""",
        list(nodes.values()),
    )
    connection.executemany(
        """INSERT INTO library_items
           (id, library, title, relative_path, extension, size_bytes, modified_at, scanned_at,
            node_id, series_node_id, season_number, episode_number, available)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
           ON CONFLICT(id) DO UPDATE SET
             library=excluded.library, title=excluded.title, relative_path=excluded.relative_path,
             extension=excluded.extension, size_bytes=excluded.size_bytes,
             modified_at=excluded.modified_at, scanned_at=excluded.scanned_at,
             node_id=excluded.node_id, series_node_id=excluded.series_node_id,
             season_number=excluded.season_number, episode_number=excluded.episode_number,
             available=1""",
        files,
    )
    unavailable = connection.execute(
        "UPDATE library_items SET available=0 WHERE available=1 AND id NOT IN (SELECT id FROM scan_files)"
    ).rowcount
    connection.execute(
        "UPDATE catalog_nodes SET available=0 WHERE available=1 AND id NOT IN (SELECT id FROM scan_nodes)"
    )
    connection.commit()
    return {"scanned": len(files), "nodes": len(nodes), "marked_unavailable": unavailable,
            "completed_at": now}
