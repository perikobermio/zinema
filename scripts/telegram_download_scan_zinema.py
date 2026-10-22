#!/usr/bin/env python3
"""List downloadable Telegram media as JSON without downloading or changing messages."""

import argparse
import asyncio
import json
import os
import re
import sys
from pathlib import Path

from telethon import TelegramClient

from telegram_download_zinema import (
    API_HASH,
    API_ID,
    CHAT_ID,
    SESSION_PATH,
    is_target,
    media_title,
    resolve_chat,
)


EPISODE_PATTERN = re.compile(
    r"(?i)(?:\bS(?P<s_season>\d{1,2})\s*E(?P<s_episode>\d{1,3})\b"
    r"|\b(?P<x_season>\d{1,2})x(?P<x_episode>\d{1,3})\b)"
)
VIDEO_EXTENSIONS = {
    ".avi", ".m4v", ".mkv", ".mov", ".mp4", ".mpeg", ".mpg",
    ".ts", ".webm", ".wmv",
}


def media_filename(message) -> str:
    name = getattr(message.file, "name", None)
    if name:
        return os.path.basename(name.replace("\\", "/"))
    return media_title(message)


async def scan(limit: int) -> list[dict[str, object]]:
    records = []
    async with TelegramClient(SESSION_PATH, API_ID, API_HASH) as client:
        entity = await resolve_chat(client, CHAT_ID)
        if entity is None:
            raise RuntimeError(f"No se ha encontrado el chat de Telegram {CHAT_ID!r}.")
        async for message in client.iter_messages(entity, limit=limit):
            if not is_target(message):
                continue
            filename = media_filename(message)
            mime_type = getattr(message.file, "mime_type", None)
            if not (str(mime_type or "").lower().startswith("video/")
                    or Path(filename).suffix.lower() in VIDEO_EXTENSIONS):
                continue
            match = EPISODE_PATTERN.search(filename + " " + (message.message or ""))
            season_number = episode_number = None
            if match:
                season_number = int(match.group("s_season") or match.group("x_season"))
                episode_number = int(match.group("s_episode") or match.group("x_episode"))
            records.append({
                "message_id": int(message.id),
                "filename": filename,
                "title": media_title(message),
                "caption": (message.message or "").strip(),
                "date": message.date.isoformat() if message.date else None,
                "size_bytes": int(getattr(message.file, "size", 0) or 0),
                "mime_type": mime_type,
                "season_number": season_number,
                "episode_number": episode_number,
            })
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=1000,
                        help="Máximo de mensajes recientes a examinar (por defecto 1000).")
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 10000:
        parser.error("--limit debe estar entre 1 y 10000")
    try:
        print(json.dumps(asyncio.run(scan(args.limit)), ensure_ascii=False))
        return 0
    except Exception as error:
        print(f"Error al consultar Telegram: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
