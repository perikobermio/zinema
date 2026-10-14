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


def normalize_genres(values: object) -> list[str]:
    """Normalize Spanish genre labels and collapse case/accent duplicates."""
    if not isinstance(values, (list, tuple)):
        return []
    aliases = {
        "animacion": "Animación",
        "animation": "Animación",
        "fantasia": "Fantasía",
        "fantasy": "Fantasía",
    }
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not isinstance(value, str) or not value.strip():
            continue
        label = value.strip()
        key = _normalise(label)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(aliases.get(key, label))
    return result


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


def _cast_payload(details: dict) -> list[dict[str, str]]:
    credits = details.get("credits") or {}
    cast: list[dict[str, str]] = []
    for member in credits.get("cast", [])[:15]:
        name = member.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        character = member.get("character")
        cast.append({"name": name.strip(),
                     "character": character.strip() if isinstance(character, str) else ""})
    return cast


def _write_match(connection: sqlite3.Connection, node: sqlite3.Row, details: dict,
                 artwork_dir: Path, client: httpx.Client, image_config: tuple[str, str, str],
                 language: str, api_key: str = "") -> None:
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
    genres = json.dumps(normalize_genres(
        [genre.get("name") for genre in details.get("genres", []) if genre.get("name")]
    ), ensure_ascii=False)
    cast = _cast_payload(details)
    connection.execute(
        """UPDATE catalog_nodes SET tmdb_id=?, imdb_id=?, metadata_title=?, original_title=?,
           overview=?, year=?, genres=?, cast_json=?, poster_file=COALESCE(?, poster_file),
           backdrop_file=COALESCE(?, backdrop_file), metadata_status='matched',
           metadata_source='tmdb', metadata_checked_at=?, metadata_candidates=NULL,
           metadata_error=NULL WHERE id=?""",
        (tmdb_id, external_ids.get("imdb_id"), title, original_title,
         details.get("overview") or "", year, genres,
         json.dumps(cast, ensure_ascii=False), poster_file, backdrop_file,
         datetime.now(timezone.utc).isoformat(), safe_id),
    )
    if entity_kind == "tv":
        _write_season_artwork(connection, node, details, artwork_dir, client,
                              image_config, language, api_key)


def _write_season_artwork(connection: sqlite3.Connection, node: sqlite3.Row, details: dict,
                          artwork_dir: Path, client: httpx.Client,
                          image_config: tuple[str, str, str], language: str,
                          api_key: str) -> None:
    seasons = {int(item["season_number"]): item for item in details.get("seasons", [])
               if item.get("season_number") is not None}
    season_rows = connection.execute(
        "SELECT id, season_number, poster_file FROM catalog_nodes "
        "WHERE parent_id=? AND kind='season' AND available=1 "
        "ORDER BY CASE WHEN season_number=0 THEN 1 ELSE 0 END, season_number",
        (node["id"],),
    ).fetchall()
    image_base, poster_size, backdrop_size = image_config
    series_row = connection.execute(
        "SELECT poster_file FROM catalog_nodes WHERE id=?", (node["id"],),
    ).fetchone()
    series_poster = series_row["poster_file"] if series_row else None
    first_available_poster = None
    episode_metadata_ok = True
    for season in season_rows:
        season_number = int(season["season_number"] or 0)
        tmdb_season = seasons.get(season_number, {})
        season_details = {}
        try:
            season_details = _api_get(
                client, f"/tv/{int(details['id'])}/season/{season_number}",
                {"language": language}, api_key,
            )
        except Exception:
            episode_metadata_ok = False
            pass
        episodes = sorted(
            season_details.get("episodes", []),
            key=lambda episode: int(episode.get("episode_number") or 0),
        )
        for episode in episodes:
            episode_number = episode.get("episode_number")
            episode_title = (episode.get("name") or "").strip()
            if episode_number is not None and episode_title:
                connection.execute(
                    """UPDATE library_items SET episode_title=?
                       WHERE series_node_id=? AND season_number=? AND episode_number=?
                         AND available=1""",
                    (episode_title, node["id"], season_number, int(episode_number)),
                )

        image_path = (tmdb_season.get("poster_path") or season_details.get("poster_path"))
        image_type = "poster"
        if not image_path:
            image_path = next((episode.get("still_path") for episode in episodes
                               if episode.get("still_path")), None)
            image_type = "backdrop"
        poster_file = None
        if image_path and not season["poster_file"]:
            try:
                poster_file = _save_image(
                    client, image_base,
                    poster_size if image_type == "poster" else backdrop_size, image_path,
                    artwork_dir / f"{season['id']}-poster.jpg",
                )
            except Exception:
                poster_file = None
        if poster_file:
            connection.execute(
                "UPDATE catalog_nodes SET poster_file=? WHERE id=?",
                (poster_file, season["id"]),
            )
        else:
            poster_file = season["poster_file"] or series_poster
            if not season["poster_file"] and series_poster:
                connection.execute(
                    "UPDATE catalog_nodes SET poster_file=? WHERE id=?",
                    (series_poster, season["id"]),
                )
        if first_available_poster is None and poster_file:
            first_available_poster = poster_file
    if first_available_poster:
        connection.execute(
            "UPDATE catalog_nodes SET poster_file=COALESCE(poster_file, ?) WHERE id=?",
            (first_available_poster, node["id"]),
        )
    if episode_metadata_ok:
        connection.execute(
            "UPDATE catalog_nodes SET episode_metadata_checked_at=? WHERE id=?",
            (datetime.now(timezone.utc).isoformat(), node["id"]),
        )


def _fetch_details(client: httpx.Client, kind: str, tmdb_id: int, language: str,
                   api_key: str = "") -> dict:
    return _api_get(client, f"/{kind}/{tmdb_id}",
                    {"language": language, "append_to_response": "external_ids,credits"}, api_key)


def _search_titles(client: httpx.Client, kind: str, title: str, source_year: int | None,
                   language: str, api_key: str = "") -> list[dict]:
    """Retry searches without a restrictive year and, when needed, in English."""
    languages = [language]
    if language.casefold() != "en-us":
        languages.append("en-US")
    results: dict[int, dict] = {}
    for search_language in languages:
        year_variants = [source_year, None] if source_year else [None]
        for year in year_variants:
            params: dict[str, object] = {
                "query": title, "language": search_language, "include_adult": "false",
            }
            if year:
                params["year" if kind == "movie" else "first_air_date_year"] = year
            search = _api_get(client, f"/search/{kind}", params, api_key)
            for result in search.get("results", []):
                result_id = result.get("id")
                if result_id is not None:
                    results.setdefault(int(result_id), result)
            if results:
                return list(results.values())
    return []


def search_metadata_candidates(node: sqlite3.Row, access_token: str, language: str,
                               api_key: str = "") -> list[dict]:
    kind = "movie" if node["kind"] == "movie" else "tv"
    title, source_year = _source_title(node["title"], node["source_year"])
    headers = {"accept": "application/json"}
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    with httpx.Client(headers=headers, timeout=httpx.Timeout(30.0), follow_redirects=True) as client:
        results = _search_titles(client, kind, title, source_year, language, api_key)
    candidates = []
    for result in results:
        candidate_title = result.get("title") or result.get("name") or ""
        original = result.get("original_title") or result.get("original_name") or ""
        date = result.get("release_date") or result.get("first_air_date")
        candidates.append({
            "tmdb_id": result.get("id"), "title": candidate_title,
            "original_title": original,
            "year": int(date[:4]) if date and len(date) >= 4 and date[:4].isdigit() else None,
            "overview": result.get("overview") or "",
            "poster_path": result.get("poster_path"),
            "score": round(_match_score(title, candidate_title, original, source_year, date), 4),
        })
    candidates.sort(key=lambda item: item["score"], reverse=True)
    return candidates[:10]


def get_candidate_details(kind: str, tmdb_id: int, access_token: str,
                          language: str, api_key: str = "") -> dict:
    entity_kind = "movie" if kind == "movie" else "tv"
    headers = {"accept": "application/json"}
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    with httpx.Client(headers=headers, timeout=httpx.Timeout(30.0), follow_redirects=True) as client:
        details = _fetch_details(client, entity_kind, tmdb_id, language, api_key)
    release = details.get("release_date") if entity_kind == "movie" else details.get("first_air_date")
    return {
        "tmdb_id": int(details["id"]),
        "metadata_title": details.get("title") or details.get("name") or "",
        "overview": details.get("overview") or "",
        "year": int(release[:4]) if release and len(release) >= 4 and release[:4].isdigit() else None,
        "genres": [item["name"] for item in details.get("genres", []) if item.get("name")],
        "poster_path": details.get("poster_path"),
        "backdrop_path": details.get("backdrop_path"),
    }


def search_image_options(kind: str, query: str, access_token: str, language: str,
                         api_key: str = "", tmdb_id: int | None = None,
                         season_number: int | None = None) -> list[dict]:
    entity_kind = "movie" if kind == "movie" else "tv"
    headers = {"accept": "application/json"}
    if access_token:
        headers["Authorization"] = f"Bearer {access_token}"
    with httpx.Client(headers=headers, timeout=httpx.Timeout(30.0), follow_redirects=True) as client:
        base, poster_size, backdrop_size = _image_config(client, api_key)
        results: list[dict] = []
        if tmdb_id:
            path = (f"/tv/{tmdb_id}/season/{season_number}/images" if season_number is not None
                    else f"/{entity_kind}/{tmdb_id}/images")
            images = _api_get(client, path, {
                "language": language,
                "include_image_language": f"{language.split('-')[0]},en,null",
            }, api_key)
            if season_number is not None:
                groups = [("poster", images.get("posters", []))]
            else:
                groups = [("poster", images.get("posters", [])),
                          ("backdrop", images.get("backdrops", []))]
            for image_type, values in groups:
                for item in values:
                    file_path = item.get("file_path")
                    if not file_path:
                        continue
                    size = poster_size if image_type == "poster" else backdrop_size
                    results.append({
                        "path": file_path, "type": image_type,
                        "url": f"{base.rstrip('/')}/{size}/{quote(file_path.lstrip('/'), safe='/')}",
                        "width": item.get("width"), "height": item.get("height"),
                        "score": item.get("vote_average") or 0,
                    })
        else:
            title, source_year = _source_title(query, None)
            search = _search_titles(client, entity_kind, title, source_year, language, api_key)
            for item in search:
                for image_type, file_path in (
                    ("poster", item.get("poster_path")),
                    ("backdrop", item.get("backdrop_path")),
                ):
                    if not file_path:
                        continue
                    size = poster_size if image_type == "poster" else backdrop_size
                    results.append({
                        "path": file_path, "type": image_type,
                        "url": f"{base.rstrip('/')}/{size}/{quote(file_path.lstrip('/'), safe='/')}",
                        "width": None, "height": None,
                        "score": item.get("vote_average") or 0,
                    })
        unique = {item["path"]: item for item in results}
        return sorted(unique.values(), key=lambda item: item["score"], reverse=True)[:10]


def enrich_pending(connection: sqlite3.Connection, access_token: str, language: str,
                   artwork_dir: Path, batch_size: int = 100,
                   minimum_delay: float = 0.25, api_key: str = "",
                   node_id: str | None = None) -> dict[str, int]:
    """Match pending movie/series nodes, then save TMDb metadata and local artwork."""
    now = datetime.now(timezone.utc)
    cutoff = (now - timedelta(days=30)).isoformat()
    error_cutoff = (now - timedelta(days=1)).isoformat()
    rows = connection.execute(
        """SELECT * FROM catalog_nodes
           WHERE available=1 AND kind IN ('movie', 'series')
             AND (COALESCE(metadata_source, '')!='manual'
                  OR (tmdb_id IS NOT NULL AND cast_json IS NULL))
             AND (? IS NULL OR id=?)
             AND (metadata_checked_at IS NULL
                  OR (metadata_status='error' AND metadata_checked_at < ?)
                  OR (metadata_status!='error' AND metadata_checked_at < ?)
                  OR (tmdb_id IS NOT NULL AND cast_json IS NULL)
                  OR (kind='series' AND tmdb_id IS NOT NULL
                      AND episode_metadata_checked_at IS NULL))
           ORDER BY CASE WHEN tmdb_id IS NOT NULL THEN 1 ELSE 0 END, scanned_at
           LIMIT ?""",
        (node_id, node_id, error_cutoff, cutoff, batch_size),
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
                if node["metadata_source"] == "manual" and node["tmdb_id"]:
                    details = _fetch_details(client, kind, int(node["tmdb_id"]), language, api_key)
                    connection.execute(
                        "UPDATE catalog_nodes SET cast_json=? WHERE id=?",
                        (json.dumps(_cast_payload(details), ensure_ascii=False), node["id"]),
                    )
                    connection.commit()
                    continue
                if node["tmdb_id"]:
                    details = _fetch_details(client, kind, int(node["tmdb_id"]), language, api_key)
                    _write_match(connection, node, details, artwork_dir, client, config,
                                 language, api_key)
                    counts["matched"] += 1
                    connection.commit()
                    continue

                title, source_year = _source_title(node["title"], node["source_year"])
                candidates = []
                for result in _search_titles(client, kind, title, source_year,
                                             language, api_key)[:10]:
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
                        _write_match(connection, node, details, artwork_dir, client, config,
                                     language, api_key)
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
        _write_match(connection, node, details, artwork_dir, client, config,
                     language, api_key)
    connection.commit()
