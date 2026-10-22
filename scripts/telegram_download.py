#!/usr/bin/env python3
"""
Descarga el último vídeo, audio o documento de un chat de Telegram usando Telethon.

La sesión de Telethon se guarda junto al script (telegram.session), así que solo
pedirá el login (teléfono + código) la primera vez.
"""
import argparse
import asyncio
import os
import sys

from telethon import TelegramClient
from telethon.errors import RPCError

# --- Configuración ---
API_ID = int(os.getenv("TELEGRAM_API_ID", "0") or 0)
API_HASH = os.getenv("TELEGRAM_API_HASH", "")
CHAT_ID = "me"
DOWNLOAD_DIR = "/d/NAS/Incoming"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Telethon añade ".session" al nombre -> SCRIPT_DIR/zinema_download.session
SESSION_PATH = os.path.join(SCRIPT_DIR, "zinema_download")

def is_target(msg):
    """True si el mensaje es vídeo, audio/nota de voz o documento (no stickers ni GIFs)."""
    if msg.sticker or msg.gif:
        return False
    return bool(msg.video or msg.video_note or msg.audio or msg.voice or msg.document)

def media_kind(msg):
    if msg.video or msg.video_note:
        return "vídeo"
    if msg.audio or msg.voice:
        return "audio"
    return "documento"

def media_title(msg):
    """Título del ítem: título del audio, nombre de archivo, texto del mensaje o id."""
    f = msg.file
    return f.title or f.name or (msg.message or "").strip() or f"{msg.id}{f.ext or ''}"

async def resolve_chat(client, chat):
    """Devuelve la entidad del chat o None si no se encuentra."""
    try:
        return await client.get_entity(chat)
    except (ValueError, RPCError):
        pass
    try:
        await client.get_dialogs()
        return await client.get_entity(chat)
    except (ValueError, RPCError):
        return None

async def main(message_id=None):
    if not API_ID or not API_HASH:
        print("ERROR: configura TELEGRAM_API_ID y TELEGRAM_API_HASH en el entorno.", file=sys.stderr)
        return 1
    if not CHAT_ID:
        print("ERROR: CHAT_ID no está configurado. Edita la constante en el script.", file=sys.stderr)
        return 1

    async with TelegramClient(SESSION_PATH, API_ID, API_HASH) as client:
        entity = await resolve_chat(client, CHAT_ID)
        if entity is None:
            print(f"ERROR: no se ha encontrado el chat {CHAT_ID!r}.", file=sys.stderr)
            return 1

        if message_id is not None:
            msg = await client.get_messages(entity, ids=message_id)
            if not msg or not is_target(msg):
                msg = None
        else:
            msg = None
            async for candidate in client.iter_messages(entity):
                if is_target(candidate):
                    msg = candidate
                    break
        if msg is None:
            print("No se ha encontrado ningún vídeo, audio o documento en el chat.")
            return 1

        title = media_title(msg)
        size = msg.file.size or 0
        print(f"Último {media_kind(msg)}: {title} ({size / 1024 / 1024:.1f} MB) - mensaje {msg.id} del {msg.date:%Y-%m-%d %H:%M}")

        def progress(done, total):
            if total:
                print(f"\r  {done / total:6.1%}  ({done / 1024 / 1024:.1f}/{total / 1024 / 1024:.1f} MB)", end="", flush=True)

        # ---- Custom naming logic (robust) ----
        ext = ""
        if getattr(msg.file, "ext", None):
            ext = f".{msg.file.ext}"
        # Build safe title, replace unsafe characters and trim trailing dots
        safe_title = media_title(msg).replace("/", "_").replace("\\", "_").strip().rstrip('.')
        # Collapse multiple spaces
        while "  " in safe_title:
            safe_title = safe_title.replace("  ", " ")
        if getattr(msg.file, "name", None):
            filename = os.path.basename(msg.file.name)
        else:
            filename = f"{safe_title}{ext if ext else '.mp4'}"
        filename = filename.replace('..', '.')
        target_path = os.path.join(DOWNLOAD_DIR, filename)
        try:
            path = await msg.download_media(file=target_path, progress_callback=progress)
        except Exception as dl_err:
            raise RuntimeError(f"Error al descargar el archivo: {dl_err}")
        print()
        if not path or not os.path.isfile(path):
            raise RuntimeError("no se ha generado el archivo")
        if size and os.path.getsize(path) != size:
            print(f"ADVERTENCIA: tamaño esperado {size} bytes, descargado {os.path.getsize(path)} bytes", file=sys.stderr)

        print(f"Descargado en: {path}")
        await client.delete_messages(entity, msg.id)
        await client.send_message(entity, f"{title} ondo deskargatu da")
        return 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true",
                        help="Compatibilidad con el botón de descarga de Zinema.")
    parser.add_argument("--message-id", type=int,
                        help="Descarga este mensaje de Telegram en lugar del más reciente.")
    args = parser.parse_args()
    sys.exit(asyncio.run(main(args.message_id)))
