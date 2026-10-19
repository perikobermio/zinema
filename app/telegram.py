from __future__ import annotations

import asyncio
import io
import os
import threading
from pathlib import Path

from telethon.sync import TelegramClient


_SEND_LOCK = threading.Lock()
DEFAULT_SESSION_PATH = "/data/telegram/zinema"


def _configuration() -> tuple[int, str, str, str]:
    raw_api_id = os.getenv("TELEGRAM_API_ID", "").strip()
    api_hash = os.getenv("TELEGRAM_API_HASH", "").strip()
    if not raw_api_id or not api_hash:
        raise RuntimeError("Telegram no está configurado: define TELEGRAM_API_ID y TELEGRAM_API_HASH")
    try:
        api_id = int(raw_api_id)
    except ValueError:
        raise RuntimeError("TELEGRAM_API_ID debe ser un entero") from None
    session_path = os.getenv("TELEGRAM_SESSION_PATH", DEFAULT_SESSION_PATH).strip()
    target = os.getenv("TELEGRAM_TARGET", "me").strip() or "me"
    if not session_path:
        raise RuntimeError("TELEGRAM_SESSION_PATH no puede estar vacío")
    return api_id, api_hash, session_path, target


def _new_client() -> tuple[TelegramClient, str]:
    api_id, api_hash, session_path, _ = _configuration()
    path = Path(session_path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        path.parent.chmod(0o700)
    except OSError:
        pass
    return TelegramClient(session_path, api_id, api_hash), session_path


async def _send_message(caption: str, thumbnail: bytes | None) -> int:
    client, session_path = _new_client()
    _, _, _, target = _configuration()
    try:
        await client.connect()
        if not await client.is_user_authorized():
            raise RuntimeError("La sesión de Telegram no está autorizada; ejecuta python -m app.telegram en el contenedor")
        if thumbnail:
            photo = io.BytesIO(thumbnail)
            photo.name = "zinema-request-poster.jpg"
            message = await client.send_file(target, photo, caption=caption, force_document=False)
        else:
            message = await client.send_message(target, caption)
        return int(message.id)
    finally:
        await client.disconnect()
        try:
            Path(session_path + ".session").chmod(0o600)
        except OSError:
            pass


def send_telegram_message(caption: str, thumbnail: bytes | None = None) -> int:
    """Send a notification through the persistent user session (Saved Messages by default)."""
    with _SEND_LOCK:
        return asyncio.run(_send_message(caption, thumbnail))


def login() -> None:
    """Interactively create the persistent user session from inside the container."""
    client, session_path = _new_client()
    try:
        client.start()
        if not client.is_user_authorized():
            raise RuntimeError("Telegram no autorizó la sesión")
        print("Sesión de Telegram autorizada.")
    finally:
        client.disconnect()
        try:
            Path(session_path + ".session").chmod(0o600)
        except OSError:
            pass


if __name__ == "__main__":
    login()
