from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
from pathlib import Path


def _extract_frame(source: Path, destination: Path) -> bool:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return False
    temporary = destination.with_name(destination.stem + ".tmp.jpg")
    for timestamp in ("00:00:30", "00:00:05", "00:00:00"):
        temporary.unlink(missing_ok=True)
        try:
            subprocess.run(
                [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                 "-ss", timestamp, "-i", str(source), "-frames:v", "1",
                 "-vf", "scale=640:-2", "-q:v", "3", str(temporary)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=40,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if temporary.is_file() and temporary.stat().st_size > 0:
            os.replace(temporary, destination)
            return True
    temporary.unlink(missing_ok=True)
    return False


def extract_episode_thumbnails(
    connection: sqlite3.Connection,
    media_roots: dict[str, Path],
    artwork_dir: Path,
) -> int:
    """Create a distinct sampled frame for each available series video."""
    if not shutil.which("ffmpeg"):
        return 0
    artwork_dir.mkdir(parents=True, exist_ok=True)
    rows = connection.execute(
        """SELECT id, library, relative_path FROM library_items
           WHERE library='series' AND available=1 ORDER BY relative_path"""
    ).fetchall()
    generated = 0
    for row in rows:
        root = media_roots.get(row["library"])
        if root is None:
            continue
        source = (root / row["relative_path"]).resolve()
        try:
            source.relative_to(root.resolve())
            source_stat = source.stat()
        except (OSError, ValueError):
            continue
        if not source.is_file():
            continue
        filename = f"{row['id']}-frame.jpg"
        destination = artwork_dir / filename
        if destination.is_file() and destination.stat().st_mtime >= source_stat.st_mtime:
            connection.execute(
                "UPDATE library_items SET poster_file=? WHERE id=? AND poster_file IS NOT ?",
                (filename, row["id"], filename),
            )
            continue
        if _extract_frame(source, destination):
            connection.execute(
                "UPDATE library_items SET poster_file=? WHERE id=? AND available=1",
                (filename, row["id"]),
            )
            connection.commit()
            generated += 1
    connection.commit()
    return generated
