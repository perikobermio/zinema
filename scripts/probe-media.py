#!/usr/bin/env python3
"""Compara códec, resolución, bitrate y audio de los vídeos de una carpeta.

Sirve para averiguar por qué una serie va con lag en la TV y otra no
(normalmente: HEVC 10-bit / 4K / HDR / bitrate muy alto / audio DTS-TrueHD).

Uso: scripts/probe-media.py /d/NAS/Serijek/"Crystal Lake" /d/NAS/Serijek/"Ted Lasso"
"""
import json
import subprocess
import sys
from pathlib import Path

EXT = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".wmv"}


def probe(path: Path) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
        capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for arg in sys.argv[1:]:
        files = sorted(p for p in Path(arg).rglob("*") if p.suffix.lower() in EXT)[:3]
        print(f"\n== {arg} ({len(files)} muestras)")
        for f in files:
            try:
                info = probe(f)
            except Exception as exc:  # noqa: BLE001
                print(f"  {f.name}: error {exc}")
                continue
            video = next((s for s in info["streams"] if s["codec_type"] == "video"), {})
            audio = [s for s in info["streams"] if s["codec_type"] == "audio"]
            mbps = int(info["format"].get("bit_rate", 0)) / 1e6
            print(f"  {f.name}")
            print(f"    contenedor={info['format'].get('format_name')}  bitrate={mbps:.1f} Mbps  "
                  f"tamaño={int(info['format'].get('size', 0)) / 1e9:.2f} GB")
            print(f"    vídeo={video.get('codec_name')} {video.get('profile', '')} "
                  f"{video.get('width')}x{video.get('height')} pix_fmt={video.get('pix_fmt')} "
                  f"fps={video.get('avg_frame_rate')} hdr={video.get('color_transfer', '-')}")
            print("    audio=" + ", ".join(
                f"{a.get('codec_name')}({a.get('channels')}ch,{a.get('tags', {}).get('language', '?')})"
                for a in audio))


if __name__ == "__main__":
    main()
