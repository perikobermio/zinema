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


def moov_position(path: Path) -> str:
    """En MP4: 'inicio' si el índice (moov) va antes que los datos (faststart), 'final' si no."""
    if path.suffix.lower() not in {".mp4", ".m4v", ".mov"}:
        return "-"
    try:
        with path.open("rb") as handle:
            size = path.stat().st_size
            offset = 0
            while offset < size:
                handle.seek(offset)
                header = handle.read(16)
                if len(header) < 8:
                    break
                length = int.from_bytes(header[:4], "big")
                kind = header[4:8]
                if length == 1:
                    length = int.from_bytes(header[8:16], "big")
                if kind == b"moov":
                    return "inicio"
                if kind == b"mdat":
                    return "final"
                if length < 8:
                    break
                offset += length
    except OSError:
        pass
    return "?"


def packet_stats(path: Path, seconds: int = 600) -> dict:
    """Pico de bitrate (ventana de 1 s) y separación entre fotogramas clave en los primeros minutos."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", f"%+{seconds}",
         "-show_entries", "packet=pts_time,size,flags", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True).stdout
    per_second: dict[int, int] = {}
    keyframes: list[float] = []
    for line in out.splitlines():
        parts = line.split(",")
        if len(parts) < 3 or parts[0] in ("", "N/A"):
            continue
        try:
            when, size = float(parts[0]), int(parts[1])
        except ValueError:
            continue
        per_second[int(when)] = per_second.get(int(when), 0) + size
        if "K" in parts[2]:
            keyframes.append(when)
    if not per_second:
        return {}
    peak = max(per_second.values()) * 8 / 1e6
    avg = sum(per_second.values()) * 8 / 1e6 / len(per_second)
    gaps = [b - a for a, b in zip(keyframes, keyframes[1:])]
    return {"peak": peak, "avg": avg, "max_gop": max(gaps) if gaps else 0.0,
            "keyframes": len(keyframes)}


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
            print(f"    nivel={video.get('level')} refs={video.get('refs', '?')} "
                  f"b-frames={video.get('has_b_frames', '?')} r_fps={video.get('r_frame_rate')} "
                  f"moov={moov_position(f)}")
            stats = packet_stats(f)
            if stats:
                flag = "  <-- PICOS" if stats["peak"] > 3 * max(stats["avg"], 0.1) or stats["peak"] > 20 else ""
                print(f"    bitrate medio={stats['avg']:.1f} Mbps  pico(1s)={stats['peak']:.1f} Mbps  "
                      f"GOP máx={stats['max_gop']:.1f}s{flag}")
            print("    audio=" + ", ".join(
                f"{a.get('codec_name')}({a.get('channels')}ch,{a.get('tags', {}).get('language', '?')})"
                for a in audio))


if __name__ == "__main__":
    main()
