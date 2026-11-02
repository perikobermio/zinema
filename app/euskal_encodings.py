from __future__ import annotations

import re
import threading
import time
import unicodedata
from urllib.parse import urljoin

import httpx


API_URL = "https://zernonikusi.eus/public/filter/movies"
FORUM_URL = "https://www.euskal-encodings.eus/index.php?topic={}"
COVERS_URL = "https://www.euskal-encodings.eus/aurkibidea/"
CACHE_SECONDS = 60
_CACHE_LOCK = threading.Lock()
_CACHE: dict[str, tuple[float, list[dict[str, object]]]] = {}


def _key(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(char for char in normalized if not unicodedata.combining(char))
    return " ".join(re.sub(r"[^a-z0-9]+", " ", without_marks).split())


def _score(query: str, title: str, original_title: str) -> float:
    query_key = _key(query)
    title_key = _key(title)
    original_key = _key(original_title)
    if title_key == query_key:
        return 4.0
    if title_key.startswith(query_key):
        return 3.0
    if query_key in title_key:
        return 2.0
    if original_key.startswith(query_key):
        return 1.5
    if query_key in original_key:
        return 1.0
    return 0.0


def search_films(query: str, limit: int = 20) -> list[dict[str, object]]:
    title_query = query.strip()
    if len(_key(title_query)) < 2:
        return []

    cache_key = _key(title_query)
    now = time.monotonic()
    with _CACHE_LOCK:
        cached = _CACHE.get(cache_key)
        if cached is not None and now - cached[0] < CACHE_SECONDS:
            return cached[1][:limit]

    headers = {
        "User-Agent": "Zinema/0.1 (catalog search)",
        "Accept": "application/json",
    }
    payload = {
        "filters": {"orderBy": "creationDateDesc", "searchTerm": title_query},
        "page": 1,
    }
    with httpx.Client(headers=headers, timeout=httpx.Timeout(12.0)) as client:
        response = client.post(API_URL, json=payload)
        response.raise_for_status()
        data = response.json()

    raw_movies = data.get("movies") if isinstance(data, dict) else None
    if not isinstance(raw_movies, list):
        raise RuntimeError("Euskal Encodings devolvió una respuesta inesperada")

    ranked: list[tuple[float, dict[str, object]]] = []
    for movie in raw_movies:
        if not isinstance(movie, dict):
            continue
        title = str(movie.get("title") or "").strip()
        topic = str(movie.get("link") or "").strip()
        if not title or not re.fullmatch(r"\d+(?:\.\d+)?", topic):
            continue
        original_title = str(movie.get("original_title") or "").strip()
        year_value = str(movie.get("year") or "")
        year_match = re.search(r"(?:19|20)\d{2}", year_value)
        cover = str(movie.get("cover") or "").strip()
        result: dict[str, object] = {
            "id": str(movie.get("id") or topic),
            "title": title,
            "original_title": original_title,
            "year": int(year_match.group(0)) if year_match else None,
            "url": FORUM_URL.format(topic),
            "cover_url": urljoin(COVERS_URL, cover) if cover else "",
        }
        ranked.append((_score(title_query, title, original_title), result))

    ranked.sort(key=lambda item: (-item[0], str(item[1]["title"]).casefold()))
    results = [result for _, result in ranked]
    with _CACHE_LOCK:
        _CACHE[cache_key] = (time.monotonic(), results)
        expired = [key for key, (saved_at, _) in _CACHE.items()
                   if time.monotonic() - saved_at >= CACHE_SECONDS]
        for key in expired:
            _CACHE.pop(key, None)
    return results[:limit]
