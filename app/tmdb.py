from __future__ import annotations

import json
import re
import time
import unicodedata
from difflib import SequenceMatcher
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sqlite3
from urllib.parse import quote

import httpx

TMDB_API = "https://api.themoviedb.org/3"
TMDB_IMAGES = "https://image.tmdb.org/t/p"
YEAR_RE = re.compile(r"\s*\(((?:19|20)\d{2})\)\s*$")


def _normalise(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.casefold())
    value = "".join(char for char in value if not unicodedata.combining(char))
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


def _source_title(title: str, source_year: int | None) -> tuple[str, int | None]:
    match = YEAR_RE.search(title)
    if match:
        source_year = source_year or int(match.group(1))
        title = title[:match.start()].strip()
    return title, source_year


def _match_score(query: str, result: str, original: str, year: int | None,
                 result_date: str | None) -> float:
    query_norm = _normalise(query)
    title_score = max(
        SequenceMatcher(None, query_norm, _normalise(result)).ratio(),
        SequenceMatcher(None, query_norm, _normalise(original)).ratio() if original else 0.0,
    )
    if not year:
        return title_score
    result_year = None
    if result_date and len(result_date) >= 4 and result_date[:4].isdigit():
        result_year = int(result_date[:4])
    if result_year == year:
        return min(1.0, title_score * 0.85 + 0.15)
    if result_year and abs(result_year - year) == 1:
        return title_score * 0.85 + 0.05
    return title_score * 0.85


def _api_get(client: httpx.Client, path: str, params: dict[str, object] | None = None,
             api_key: str = "") -> dict:
    request_params = dict(params or {})
    if api_key:
        request_params["api_key"] = api_key
    for attempt in range(4):
        try:
            response = client.get(f"{TMDB_API}{path}", params=request_params)
        except httpx.RequestError as exc:
            raise RuntimeError("Could not connect to TMDb") from None
        if response.status_code == 429 or response.status_code >= 500:
            if attempt == 3:
                raise RuntimeError(f"TMDb returned HTTP {response.status_code}")
            retry_after = response.headers.get("Retry-After")
            time.sleep(float(retry_after) if retry_after and retry_after.isdigit() else 1.5 ** attempt)
            continue
        if response.is_error:
            raise RuntimeError(f"TMDb returned HTTP {response.status_code}")
        return response.json()
    raise RuntimeError("TMDb request retry limit reached")


def _image_config(client: httpx.Client, api_key: str = "") -> tuple[str, str, str]:
    data = _api_get(client, "/configuration", api_key=api_key).get("images", {})
    base = data.get("secure_base_url") or f"{TMDB_IMAGES}/"
    posters = data.get("poster_sizes") or ["original"]
    backdrops = data.get("backdrop_sizes") or ["original"]
    poster_size = "w500" if "w500" in posters else posters[-1]
    backdrop_size = "w1280" if "w1280" in backdrops else backdrops[-1]
    return base, poster_size, backdrop_size


def _save_image(client: httpx.Client, base: str, size: str, image_path: str | None,
                destination: Path) -> str | None:
    if not image_path:
        return None
    url = f"{base.rstrip('/')}/{size}/{quote(image_path.lstrip('/'), safe='/')}"
    response = client.get(url)
    response.raise_for_status()
    content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
    extension = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}.get(content_type)
    if extension is None:
        return None
    if len(response.content) > 20 * 1024 * 1024:
        return None
    destination = destination.with_suffix(extension)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_suffix(destination.suffix + ".tmp")
    temp_path.write_bytes(response.content)
    temp_path.replace(destination)
    return destination.name


def _write_match(connection: sqlite3.Connection, node: sqlite3.Row, details: dict,
                 artwork_dir: Path, client: httpx.Client, image_config: tuple[str, str, str]) -> None:
    entity_kind = "movie" if node["kind"] == "movie" else "tv"
    external_ids = details.get("external_ids") or {}
    tmdb_id = int(details["id"])
    safe_id = node["id"]
    image_base, poster_size, backdrop_size = image_config
    poster_file = _save_image(client, image_base, poster_size, details.get("poster_path"),
                              artwork_dir / f"{safe_id}-poster.jpg")
    backdrop_file = _save_image(client, image_base, backdrop_size, details.get("backdrop_path"),
                                artwork_dir / f"{safe_id}-backdrop.jpg")
    release = details.get("release_date") if entity_kind == "movie" else details.get("first_air_date")
    year = int(release[:4]) if release and len(release) >= 4 and release[:4].isdigit() else None
    title = details.get("title") or details.get("name") or node["title"]
    original_title = details.get("original_title") or details.get("original_name")
    genres = json.dumps([genre["name"] for genre in details.get("genres", []) if genre.get("name")],
                        ensure_ascii=False)
    connection.execute(
        """UPDATE catalog_nodes SET tmdb_id=?, imdb_id=?, metadata_title=?, original_title=?,
           overview=?, year=?, genres=?, poster_file=COALESCE(?, poster_file),
           backdrop_file=COALESCE(?, backdrop_file), metadata_status='matched',
           metadata_source='tmdb', metadata_checked_at=?, metadata_candidates=NULL,
           metadata_error=NULL WHERE id=?""",
        (tmdb_id, external_ids.get("imdb_id"), title, original_title,
         details.get("overview") or "", year, genres, poster_file, backdrop_file,
         datetime.now(timezone.utc).isoformat(), safe_id),
    )


def _fetch_details(client: httpx.Client, kind: str, tmdb_id: int, language: str,
                   api_key: str = "") -> dict:
    return _api_get(client, f"/{kind}/{tmdb_id}",
                    {"language": language, "append_to_response": "external_ids"}, api_key)


def enrich_pending(connection: sqlite3.Connection, access_token: str, language: str,
                   artwork_dir: Path, batch_size: int = 100,
                   minimum_delay: float = 0.25, api_key: str = "") -> dict[str, int]:
    """Match pending movie/series nodes, then save TMDb metadata and local artwork."""
    now = datetime.now(timezone.utc)
    cutoff = (now - timedelta(days=30)).isoformat()
    error_cutoff = (now - timedelta(days=1)).isoformat()
    rows = connection.execute(
        """SELECT * FROM catalog_nodes
           WHERE available=1 AND kind IN ('movie', 'series')
             AND (metadata_checked_at IS NULL
                  OR (metadata_status='error' AND metadata_checked_at < ?)
                  OR (metadata_status!='error' AND metadata_checked_at < ?))
           ORDER BY CASE WHEN tmdb_id IS NOT NULL THEN 1 ELSE 0 END, scanned_at
           LIMIT ?""",
        (error_cutoff, cutoff, batch_size),
    ).fetchall()
    if not rows:
        return {"matched": 0, "needs_review": 0, "not_found": 0, "errors": 0, "processed": 0}

    headers = {"accept": "application/json"}
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    counts = {"matched": 0, "needs_review": 0, "not_found": 0, "errors": 0, "processed": 0}
    with httpx.Client(headers=headers, timeout=httpx.Timeout(30.0), follow_redirects=True) as client:
        config = _image_config(client, api_key)
        for node in rows:
            counts["processed"] += 1
            try:
                kind = "movie" if node["kind"] == "movie" else "tv"
                if node["tmdb_id"]:
                    details = _fetch_details(client, kind, int(node["tmdb_id"]), language, api_key)
                    _write_match(connection, node, details, artwork_dir, client, config)
                    counts["matched"] += 1
                    connection.commit()
                    continue

                title, source_year = _source_title(node["title"], node["source_year"])
                params: dict[str, object] = {"query": title, "language": language, "include_adult": "false"}
                if source_year:
                    params["year" if kind == "movie" else "first_air_date_year"] = source_year
                search = _api_get(client, f"/search/{kind}", params, api_key)
                candidates = []
                for result in search.get("results", [])[:10]:
                    result_title = result.get("title") or result.get("name") or ""
                    original = result.get("original_title") or result.get("original_name") or ""
                    date = result.get("release_date") or result.get("first_air_date")
                    score = _match_score(title, result_title, original, source_year, date)
                    candidates.append({
                        "tmdb_id": result.get("id"), "title": result_title,
                        "year": date[:4] if date and len(date) >= 4 else None,
                        "score": round(score, 4), "poster_path": result.get("poster_path"),
                    })
                candidates.sort(key=lambda item: item["score"], reverse=True)
                if not candidates:
                    connection.execute(
                        "UPDATE catalog_nodes SET metadata_status='not_found', metadata_checked_at=?, metadata_error=NULL WHERE id=?",
                        (datetime.now(timezone.utc).isoformat(), node["id"]),
                    )
                    counts["not_found"] += 1
                else:
                    best = candidates[0]
                    margin = best["score"] - candidates[1]["score"] if len(candidates) > 1 else 1.0
                    if best["score"] >= 0.93 and margin >= 0.04:
                        details = _fetch_details(client, kind, int(best["tmdb_id"]), language, api_key)
                        _write_match(connection, node, details, artwork_dir, client, config)
                        counts["matched"] += 1
                    else:
                        connection.execute(
                            """UPDATE catalog_nodes SET metadata_status='needs_review',
                               metadata_checked_at=?, metadata_candidates=?, metadata_error=NULL
                               WHERE id=?""",
                            (datetime.now(timezone.utc).isoformat(),
                             json.dumps(candidates[:5], ensure_ascii=False), node["id"]),
                        )
                        counts["needs_review"] += 1
                connection.commit()
                time.sleep(max(0.0, minimum_delay))
            except Exception as exc:
                connection.execute(
                    """UPDATE catalog_nodes SET metadata_status='error', metadata_checked_at=?,
                       metadata_error=? WHERE id=?""",
                    (datetime.now(timezone.utc).isoformat(), str(exc)[:500], node["id"]),
                )
                connection.commit()
                counts["errors"] += 1
    return counts


def apply_tmdb_id(connection: sqlite3.Connection, node: sqlite3.Row, tmdb_id: int,
                  access_token: str, language: str, artwork_dir: Path,
                  api_key: str = "") -> None:
    kind = "movie" if node["kind"] == "movie" else "tv"
    headers = {"accept": "application/json"}
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    with httpx.Client(headers=headers, timeout=httpx.Timeout(30.0), follow_redirects=True) as client:
        config = _image_config(client, api_key)
        details = _fetch_details(client, kind, tmdb_id, language, api_key)
        if int(details.get("id", -1)) != tmdb_id:
            raise ValueError("TMDb returned a different item id")
        _write_match(connection, node, details, artwork_dir, client, config)
    connection.commit()
