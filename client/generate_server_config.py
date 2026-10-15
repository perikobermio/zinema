#!/usr/bin/env python3
"""Generate the Android server URL constant from the repository .env file."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import urlsplit


DEFAULT_SERVER_URL = "https://zinema.ebu.freemyip.com"


def read_server_url(env_path: Path) -> str:
    value = ""
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if stripped.startswith("export "):
                stripped = stripped[7:].lstrip()
            key, separator, raw_value = stripped.partition("=")
            if separator and key.strip() == "ZINEMA_SERVER_URL":
                value = raw_value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                break
    value = value or DEFAULT_SERVER_URL
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise SystemExit("ZINEMA_SERVER_URL must be an absolute http(s) URL")
    return value.rstrip("/")


def main() -> None:
    env_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    server_url = read_server_url(env_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        "package com.zinema.client;\n\n"
        "final class ServerConfig {\n"
        "    private ServerConfig() {}\n"
        f"    static final String DEFAULT_SERVER = {json.dumps(server_url)};\n"
        "}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
