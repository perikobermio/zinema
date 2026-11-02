from __future__ import annotations

import re
import threading
import time
import unicodedata
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse

import httpx


INDEX_URL = "https://www.euskal-encodings.eus/aurkibidea/filmak"
SITE_ROOT = "https://www.euskal-encodings.eus/"
CACHE_SECONDS = 15 * 60
_CACHE_LOCK = threading.Lock()
_CACHE: tuple[float, list[dict[str, object]]] | None = None


class _IndexLinks(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, str]] = []
        self._href = ""
        self._parts: list[str] = []
        self._depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "a":
            attrs_dict = dict(attrs)
            self._href = attrs_dict.get("href") or ""
            self._parts = []
            self._depth = 1
        elif self._depth:
            self._depth += 1

    def handle_endtag(self, tag: str) -> None:
        if not self._depth:
            return
        if tag == "a":
            title = " ".join(" ".join(self._parts).split())
            if title and self._href:
                self.links.append((self._href, title))
            self._depth = 0
            self._href = ""
            self._parts = []
        elif self._depth > 1:
            self._depth -= 1

    def handle_data(self, data: str) -> None:
        if self._depth:
            self._parts.append(data)


def _key(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(char for char in normalized if not unicodedata.combining(char))
    return " ".join(re.sub(r"[^a-z0-9]+", " ", without_marks).split())


def _read_index() -> list[dict[str, object]]:
    global _CACHE
    now = time.monotonic()
    with _CACHE_LOCK:
        if _CACHE is not None and now - _CACHE[0] < CACHE_SECONDS:
            return _CACHE[1]

    headers = {
        "User-Agent": "Zinema/0.1 (catalog search)",
        "Accept": "text/html,application/xhtml+xml",
    }
    with httpx.Client(headers=headers, timeout=httpx.Timeout(12.0), follow_redirects=True) as client:
        response = client.get(INDEX_URL)
        response.raise_for_status()

    parser = _IndexLinks()
    parser.feed(response.text)
    items: list[dict[str, object]] = []
    seen: set[str] = set()
    for href, title in parser.links:
        url = urljoin(SITE_ROOT, href)
        parsed = urlparse(url)
        if parsed.hostname not in {
            "www.euskal-encodings.eus", "euskal-encodings.eus",
            "www.euskal-encodings.com", "euskal-encodings.com",
        }:
            continue
        if "topic=" not in parsed.query and not re.search(r"/topic/\d+", parsed.path):
            continue
        if url in seen:
            continue
        seen.add(url)
        year_match = re.search(r"(?<!\d)((?:19|20)\d{2})(?!\d)", title)
        items.append({
            "id": url,
            "title": title,
            "year": int(year_match.group(1)) if year_match else None,
            "url": url,
            "_key": _key(title),
        })

    if not items:
        if "please wait while your request is being verified" in response.text.casefold():
            raise RuntimeError("Euskal Encodings está limitando la consulta automática")
        raise RuntimeError("No se encontraron películas en el índice de Euskal Encodings")

    with _CACHE_LOCK:
        _CACHE = (time.monotonic(), items)
    return items


def search_films(query: str, limit: int = 20) -> list[dict[str, object]]:
    terms = _key(query).split()
    if not terms:
        return []

    matches: list[tuple[float, dict[str, object]]] = []
    for item in _read_index():
        title_key = str(item["_key"])
        if all(term in title_key for term in terms):
            score = 2.0 if " ".join(terms) in title_key else 1.0
            matches.append((score, item))

    matches.sort(key=lambda pair: (-pair[0], str(pair[1]["title"]).casefold()))
    return [{key: value for key, value in item.items() if not key.startswith("_")}
            for _, item in matches[:limit]]
