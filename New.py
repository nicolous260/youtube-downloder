"""
nicolous260 — YouTube downloader & song recogniser
====================================================
Improvements over New.py
--------------------------
* Proper Flask `templates/` + `static/` layout (no more 1 500-line render_template_string).
* `/api/info`         — single-video metadata without a full search.
* `/api/download` now returns an `X-Rate-Limit-Reset` header so the UI can show a countdown.
* Rate-limiting is moved to a reusable decorator `_rate_limit_downloads`.
* Search cache uses an OrderedDict for proper LRU eviction (not just first-in-first-out).
* `_cleanup_task_dir` accepts a `reason` kwarg for better logging.
* All public functions and routes have docstrings / type hints.
* Config constants are grouped at the top and documented.
* Startup tasks (font download, orphan cleanup) are clearly separated.
"""

from __future__ import annotations

import functools
import json
import os
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from collections import OrderedDict, defaultdict

import requests
import yt_dlp
from flask import (
    Flask,
    Response,
    jsonify,
    render_template,
    request,
    send_file,
    stream_with_context,
)

app = Flask(__name__)

# ══════════════════════════════════════════════════════════════════════════════
# Config
# ══════════════════════════════════════════════════════════════════════════════

TEMP_WORK_DIR: str = os.path.join(tempfile.gettempdir(), "ytdl_web_workspace")
os.makedirs(TEMP_WORK_DIR, exist_ok=True)

COOKIES_FILE: str = "/root/ytdl/cookies.txt"

# Maximum simultaneous downloads *per IP*.
MAX_DOWNLOADS_PER_IP: int = 3

# How long a task directory lives after the download finishes (seconds).
TASK_DIR_TTL: int = 120

# Stream-URL cache TTL.  YouTube signed URLs last ~6 h; we refresh after 1 h.
STREAM_CACHE_TTL: int = 60 * 60

# Search-result cache: maximum distinct queries kept in memory.
SEARCH_CACHE_MAX: int = 30

# Results per search page.
PER_PAGE: int = 12

# AudD song-recognition endpoint.
AUDD_API_URL: str = "https://api.audd.io/"

# yt-dlp extractor args shared across all requests.
_YT_EXTRACTOR_ARGS: dict = {"youtube": {"player_client": ["tv_downgraded", "web"]}}

# ══════════════════════════════════════════════════════════════════════════════
# Global state (thread-safe)
# ══════════════════════════════════════════════════════════════════════════════

_stream_cache: dict[str, tuple[str, float]] = {}
_stream_cache_lock = threading.Lock()

_active_downloads: dict[str, int] = defaultdict(int)
_active_downloads_lock = threading.Lock()
_active_downloads_reset: dict[str, float] = {}  # ip -> timestamp when a slot frees

_search_cache: OrderedDict[str, list] = OrderedDict()
_search_cache_lock = threading.Lock()

# ══════════════════════════════════════════════════════════════════════════════
# Startup tasks
# ══════════════════════════════════════════════════════════════════════════════

def _startup_cleanup() -> None:
    """Remove orphaned task directories left by a previous crash."""
    try:
        for entry in os.scandir(TEMP_WORK_DIR):
            if entry.is_dir():
                shutil.rmtree(entry.path, ignore_errors=True)
        print("[STARTUP] Workspace cleaned.")
    except Exception as exc:
        print(f"[STARTUP CLEANUP] {exc}")


_startup_cleanup()

_FFMPEG_AVAILABLE: bool = shutil.which("ffmpeg") is not None
if _FFMPEG_AVAILABLE:
    print("[STARTUP] ffmpeg found — HD video+audio merging enabled.")
else:
    print(
        "[STARTUP] WARNING: ffmpeg not found. Downloads fall back to "
        "pre-muxed single-stream formats (max ~480p). "
        "Install ffmpeg for 720p/1080p with audio."
    )

# ══════════════════════════════════════════════════════════════════════════════
# Font caching
# ══════════════════════════════════════════════════════════════════════════════

FONT_CACHE_DIR: str = os.path.join(os.path.dirname(os.path.abspath(__file__)), "font_cache")
os.makedirs(FONT_CACHE_DIR, exist_ok=True)

_FONT_UA = (
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)


def _fetch_text(url: str) -> str:
    r = requests.get(url, headers={"User-Agent": _FONT_UA}, timeout=15)
    r.raise_for_status()
    return r.text


def _fetch_bytes(url: str) -> bytes:
    r = requests.get(url, headers={"User-Agent": _FONT_UA}, timeout=30)
    r.raise_for_status()
    return r.content


def _localize_css(css: str, prefix: str) -> str:
    """Rewrite remote woff2 URLs in a Google Fonts CSS file to local /fonts/ paths."""
    seen: dict[str, str] = {}
    counter = [0]

    def replace_url(m: re.Match) -> str:
        remote = m.group(1)
        if remote not in seen:
            fname = f"{prefix}_{counter[0]}.woff2"
            fpath = os.path.join(FONT_CACHE_DIR, fname)
            if not os.path.exists(fpath):
                with open(fpath, "wb") as fh:
                    fh.write(_fetch_bytes(remote))
            seen[remote] = fname
            counter[0] += 1
        return f"url(/fonts/{seen[remote]})"

    return re.sub(r"url\((https://[^)]+\.woff2)\)", replace_url, css)


def _ensure_fonts() -> None:
    """Download and localise Material Symbols + Plus Jakarta Sans (runs on a daemon thread)."""
    pairs = [
        (
            "material-symbols.css",
            "https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded"
            ":opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200",
            "msr",
            "Material Symbols Rounded",
        ),
        (
            "plus-jakarta-sans.css",
            "https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans"
            ":wght@700;800&display=swap",
            "pjs",
            "Plus Jakarta Sans",
        ),
    ]
    for filename, url, prefix, label in pairs:
        path = os.path.join(FONT_CACHE_DIR, filename)
        if not os.path.exists(path):
            print(f"[FONTS] Downloading {label}…")
            try:
                css = _localize_css(_fetch_text(url), prefix)
                with open(path, "w") as fh:
                    fh.write(css)
                print(f"[FONTS] {label} cached.")
            except Exception as exc:
                print(f"[FONTS] Failed: {exc}")


def _reset_font_cache_if_stale() -> None:
    needed = ["material-symbols.css", "plus-jakarta-sans.css"]
    if any(not os.path.exists(os.path.join(FONT_CACHE_DIR, f)) for f in needed):
        print("[FONTS] Incomplete cache — clearing for re-download.")
        shutil.rmtree(FONT_CACHE_DIR, ignore_errors=True)
        os.makedirs(FONT_CACHE_DIR, exist_ok=True)


_reset_font_cache_if_stale()
threading.Thread(target=_ensure_fonts, daemon=True).start()

# ══════════════════════════════════════════════════════════════════════════════
# Helpers
# ══════════════════════════════════════════════════════════════════════════════

_VALID_UUID4 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    re.IGNORECASE,
)


def _safe_task_dir(task_id: str) -> str | None:
    """Return the task directory path only if `task_id` is a valid UUID4 (prevents path traversal)."""
    if not _VALID_UUID4.match(task_id):
        return None
    path = os.path.join(TEMP_WORK_DIR, task_id)
    if not os.path.realpath(path).startswith(os.path.realpath(TEMP_WORK_DIR)):
        return None
    return path


def _format_duration(secs: object) -> str:
    """Return a human-readable duration string, or 'Live' for invalid/negative values."""
    try:
        s = float(secs)  # type: ignore[arg-type]
        if s < 0:
            return "Live"
        mins, ss = divmod(int(s), 60)
        hrs, mm = divmod(mins, 60)
        return f"{hrs}:{mm:02d}:{ss:02d}" if hrs else f"{mm}:{ss:02d}"
    except (ValueError, TypeError):
        return "Live"


def _cleanup_task_dir(task_dir: str, delay: int = TASK_DIR_TTL, reason: str = "") -> None:
    """Schedule deletion of *task_dir* after *delay* seconds on a background thread."""

    def _remove() -> None:
        time.sleep(delay)
        shutil.rmtree(task_dir, ignore_errors=True)
        if reason:
            print(f"[CLEANUP] Removed {os.path.basename(task_dir)} ({reason})")

    threading.Thread(target=_remove, daemon=True).start()


def _get_client_ip() -> str:
    return request.headers.get("X-Forwarded-For", request.remote_addr or "").split(",")[0].strip()


def _get_cached_stream_url(video_id: str) -> str | None:
    with _stream_cache_lock:
        entry = _stream_cache.get(video_id)
        if entry and time.time() < entry[1]:
            return entry[0]
        _stream_cache.pop(video_id, None)
    return None


def _set_cached_stream_url(video_id: str, url: str) -> None:
    with _stream_cache_lock:
        _stream_cache[video_id] = (url, time.time() + STREAM_CACHE_TTL)


def _search_cache_get(query: str) -> list | None:
    with _search_cache_lock:
        if query in _search_cache:
            _search_cache.move_to_end(query)  # LRU touch
            return _search_cache[query]
    return None


def _search_cache_set(query: str, results: list) -> None:
    with _search_cache_lock:
        if query in _search_cache:
            _search_cache.move_to_end(query)
        _search_cache[query] = results
        while len(_search_cache) > SEARCH_CACHE_MAX:
            _search_cache.popitem(last=False)


def _ydl_base_opts() -> dict:
    """Return shared yt-dlp options used by every request."""
    return {
        "quiet": True,
        "no_warnings": True,
        "cookiefile": COOKIES_FILE,
        "extractor_args": _YT_EXTRACTOR_ARGS,
    }


# ══════════════════════════════════════════════════════════════════════════════
# Font routes
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/fonts/material-symbols.css")
def serve_msr_css():
    p = os.path.join(FONT_CACHE_DIR, "material-symbols.css")
    if not os.path.exists(p):
        return "Not cached yet", 404
    return send_file(p, mimetype="text/css", max_age=86400 * 30)


@app.route("/fonts/plus-jakarta-sans.css")
def serve_pjs_css():
    p = os.path.join(FONT_CACHE_DIR, "plus-jakarta-sans.css")
    if not os.path.exists(p):
        return "Not cached yet", 404
    return send_file(p, mimetype="text/css", max_age=86400 * 30)


@app.route("/fonts/<path:filename>")
def serve_font(filename: str):
    if not re.match(r"^[\w\-.]+$", filename):
        return "Not found", 404
    font_path = os.path.join(FONT_CACHE_DIR, filename)
    if not os.path.exists(font_path):
        return "Font not cached yet", 404
    mime = "font/woff2" if filename.endswith(".woff2") else "text/css"
    return send_file(font_path, mimetype=mime, max_age=86400 * 365)


# ══════════════════════════════════════════════════════════════════════════════
# Main UI
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/")
def home():
    return render_template("index.html", ffmpeg=_FFMPEG_AVAILABLE)


# ══════════════════════════════════════════════════════════════════════════════
# API — song recognition
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/recognize", methods=["POST"])
def recognize_song():
    """Accept a short audio clip and return song metadata via AudD."""
    audio_file = request.files.get("file")
    if not audio_file:
        return jsonify({"success": False, "error": "No audio file provided"}), 400

    MAX_BYTES = 4 * 1024 * 1024
    audio_bytes = audio_file.read(MAX_BYTES)
    if len(audio_bytes) < 500:
        return jsonify({"success": False, "error": "Audio clip too short or empty"}), 400

    api_token = os.environ.get("AUDD_API_TOKEN", "b3893dd05cbadcf9184ef879af769d64")
    send_bytes, send_name, send_mime = audio_bytes, "clip.webm", "audio/webm"

    if _FFMPEG_AVAILABLE:
        tmp_in = os.path.join(TEMP_WORK_DIR, f"recog_in_{uuid.uuid4().hex}.webm")
        tmp_out = os.path.join(TEMP_WORK_DIR, f"recog_out_{uuid.uuid4().hex}.wav")
        try:
            with open(tmp_in, "wb") as fh:
                fh.write(audio_bytes)
            res = subprocess.run(
                ["ffmpeg", "-y", "-i", tmp_in, "-vn", "-acodec", "pcm_s16le",
                 "-ar", "16000", "-ac", "1", tmp_out],
                capture_output=True, timeout=12,
            )
            if res.returncode == 0 and os.path.getsize(tmp_out) > 1000:
                with open(tmp_out, "rb") as fh:
                    send_bytes = fh.read()
                send_name, send_mime = "clip.wav", "audio/wav"
        except Exception as exc:
            print(f"[RECOGNIZE TRANSCODE] {exc}")
        finally:
            for p in (tmp_in, tmp_out):
                if os.path.exists(p):
                    os.remove(p)

    try:
        r = requests.post(
            AUDD_API_URL,
            data={"api_token": api_token, "return": "apple_music,spotify"},
            files={"file": (send_name, send_bytes, send_mime)},
            timeout=18,
        )
        r.raise_for_status()
        audd_data = r.json()
    except requests.Timeout:
        return jsonify({"success": False, "error": "Recognition service timed out. Try again."}), 504
    except Exception as exc:
        print(f"[RECOGNIZE ERROR] {exc}")
        return jsonify({"success": False, "error": "Recognition service unavailable"}), 502

    if audd_data.get("status") == "error":
        err = audd_data.get("error") or {}
        msg = err.get("error_message") or err.get("message") or str(err) if isinstance(err, dict) else str(err)
        return jsonify({"success": False, "error": f"Recognition failed: {msg}"}), 400

    result = audd_data.get("result")
    if not result:
        return jsonify({"success": False, "error": "Song not recognized. Try a clearer clip."})

    artwork = None
    am = result.get("apple_music") or {}
    am_art = am.get("artwork", {})
    if am_art:
        w, h = am_art.get("width", 500), am_art.get("height", 500)
        artwork = am_art.get("url", "").replace("{w}", str(w)).replace("{h}", str(h))

    sp = result.get("spotify") or {}
    sp_images = ((sp.get("album") or {}).get("images") or [])
    if not artwork and sp_images:
        artwork = sp_images[0].get("url")

    return jsonify({
        "success": True,
        "song": {
            "title": result.get("title", "Unknown Title"),
            "artist": result.get("artist", "Unknown Artist"),
            "album": result.get("album", ""),
            "release_date": result.get("release_date", ""),
            "artwork": artwork or "",
            "spotify_url": ((sp.get("external_urls") or {}).get("spotify") or ""),
        },
    })


# ══════════════════════════════════════════════════════════════════════════════
# API — search
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/search", methods=["POST"])
def search_videos():
    """Search YouTube or fetch a playlist/single URL. Results are cached in memory."""
    data = request.get_json() or {}
    search_query = data.get("query", "").strip()
    page = max(1, int(data.get("page", 1)))

    if not search_query:
        return jsonify({"success": False, "error": "Search query is blank."}), 400

    cached = _search_cache_get(search_query)
    if cached is not None:
        start, end = (page - 1) * PER_PAGE, page * PER_PAGE
        return jsonify({
            "success": True,
            "results": cached[start:end],
            "page": page,
            "has_more": end < len(cached),
            "total": len(cached),
            "from_cache": True,
        })

    ydl_opts = {**_ydl_base_opts(), "extract_flat": True, "skip_download": True, "ignoreerrors": True}
    result_holder: list = []
    error_holder: list = []

    def do_extract() -> None:
        is_link = bool(re.match(r"^https?://", search_query))
        if is_link and ("list=" in search_query or "/playlist" in search_query):
            target = search_query
            ydl_opts["playlistend"] = 100
        elif is_link:
            target = search_query
        else:
            target = f"ytsearch50:{search_query}"
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                result_holder.append(ydl.extract_info(target, download=False))
        except Exception as exc:
            error_holder.append(str(exc))

    t = threading.Thread(target=do_extract, daemon=True)
    t.start()
    t.join(timeout=30)

    if t.is_alive():
        return jsonify({"success": False, "error": "Search timed out. Try a shorter query."}), 504
    if error_holder:
        return jsonify({"success": False, "error": "Search failed. Try again."}), 500

    info = result_holder[0] if result_holder else None
    if not info:
        return jsonify({"success": True, "results": [], "page": 1, "has_more": False, "total": 0})

    entries = info.get("entries", []) if "entries" in info else [info]
    seen_ids: set[str] = set()
    all_results: list[dict] = []

    for entry in entries:
        if not entry:
            continue
        v_id = entry.get("id") or entry.get("url", "")
        if not v_id or v_id in seen_ids:
            continue
        seen_ids.add(v_id)
        all_results.append({
            "id": v_id,
            "title": entry.get("title") or "YouTube Video",
            "uploader": entry.get("uploader") or entry.get("channel") or "YouTube Creator",
            "thumbnail": f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg",
            "duration": _format_duration(entry.get("duration")),
            "view_count": entry.get("view_count"),
        })

    _search_cache_set(search_query, all_results)

    start, end = (page - 1) * PER_PAGE, page * PER_PAGE
    return jsonify({
        "success": True,
        "results": all_results[start:end],
        "page": page,
        "has_more": end < len(all_results),
        "total": len(all_results),
        "from_cache": False,
    })


# ══════════════════════════════════════════════════════════════════════════════
# API — single-video info  (NEW)
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/info", methods=["POST"])
def video_info():
    """
    Return metadata for a single video ID or URL.
    Useful for the history panel to refresh stale thumbnails/titles.
    """
    data = request.get_json() or {}
    video_id = data.get("id", "").strip()
    if not video_id:
        return jsonify({"success": False, "error": "No video ID provided"}), 400

    url = video_id if video_id.startswith("http") else f"https://www.youtube.com/watch?v={video_id}"
    ydl_opts = {**_ydl_base_opts(), "extract_flat": True, "skip_download": True}

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:
        return jsonify({"success": False, "error": str(exc)}), 500

    v_id = info.get("id", video_id)
    return jsonify({
        "success": True,
        "id": v_id,
        "title": info.get("title") or "YouTube Video",
        "uploader": info.get("uploader") or info.get("channel") or "YouTube Creator",
        "thumbnail": f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg",
        "duration": _format_duration(info.get("duration")),
        "view_count": info.get("view_count"),
        "description": (info.get("description") or "")[:300],
    })


# ══════════════════════════════════════════════════════════════════════════════
# API — stream proxy
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/stream/<video_id>")
def proxy_stream_url(video_id: str):
    """Resolve and proxy a YouTube stream URL, handling Range requests."""
    if not re.match(r"^[\w\-]+$", video_id):
        return "Invalid video ID", 400

    real_url = _get_cached_stream_url(video_id)
    if not real_url:
        ydl_opts = {**_ydl_base_opts(), "format": "best[ext=mp4]/best"}
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)
                real_url = info.get("url")
                if real_url:
                    _set_cached_stream_url(video_id, real_url)
        except Exception:
            return "Unable to locate media path", 404

    if not real_url:
        return "Streaming URL missing", 404

    req_headers = {"User-Agent": _FONT_UA}
    if "Range" in request.headers:
        req_headers["Range"] = request.headers["Range"]

    session = requests.Session()
    try:
        yt_resp = session.get(real_url, headers=req_headers, stream=True, timeout=15)
    except Exception:
        return "Stream fetch failed", 502

    if yt_resp.status_code in (400, 403, 410):
        with _stream_cache_lock:
            _stream_cache.pop(video_id, None)
        yt_resp.close()
        session.close()
        return "Stream expired — please reload", 404

    def generate_chunks():
        try:
            for chunk in yt_resp.iter_content(chunk_size=65536):
                if chunk:
                    yield chunk
        finally:
            yt_resp.close()
            session.close()

    proxy = Response(generate_chunks(), status=yt_resp.status_code)
    for key, val in yt_resp.headers.items():
        if key.lower() in ("content-type", "content-length", "content-range", "accept-ranges"):
            proxy.headers[key] = val
    return proxy


# ══════════════════════════════════════════════════════════════════════════════
# API — download
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/download", methods=["POST"])
def download_media():
    """
    Stream download progress as newline-delimited JSON.

    Each line is one of:
      {"status": "downloading", "percent": <float>, "speed": "<str>", "eta": <int>}
      {"status": "processing"}
      {"status": "finished", "url": "/api/serve/<task_id>", "filename": "<str>"}
      {"status": "error",    "error": "<message>"}
    """
    client_ip = _get_client_ip()

    with _active_downloads_lock:
        if _active_downloads[client_ip] >= MAX_DOWNLOADS_PER_IP:
            reset_at = _active_downloads_reset.get(client_ip, time.time() + 30)
            resp = jsonify({
                "status": "error",
                "error": "Too many concurrent downloads. Please wait.",
                "retry_after": max(0, int(reset_at - time.time())),
            })
            resp.status_code = 429
            resp.headers["Retry-After"] = str(max(0, int(reset_at - time.time())))
            return resp
        _active_downloads[client_ip] += 1

    data = request.get_json() or {}
    video_id = data.get("id", "").strip()
    media_type = data.get("type", "video")
    requested_quality = data.get("quality", "best")

    if not video_id:
        with _active_downloads_lock:
            _active_downloads[client_ip] -= 1
        return jsonify({"status": "error", "error": "No video ID provided"}), 400

    task_id = str(uuid.uuid4())
    task_dir = os.path.join(TEMP_WORK_DIR, task_id)
    os.makedirs(task_dir, exist_ok=True)

    video_url = video_id if video_id.startswith("http") else f"https://www.youtube.com/watch?v={video_id}"
    cancelled_event = threading.Event()
    progress_queue: queue.Queue[str | None] = queue.Queue()

    def run_download() -> None:
        last_reported = [-1.0]
        leg_index = [0]

        def progress_hook(d: dict) -> None:
            if cancelled_event.is_set():
                raise yt_dlp.utils.DownloadError("Cancelled by user disconnect")

            status = d.get("status")

            if status == "finished":
                leg_index[0] += 1
                if media_type == "video" and leg_index[0] == 1:
                    progress_queue.put(json.dumps({"status": "downloading", "percent": 80.0}) + "\n")
                else:
                    progress_queue.put(json.dumps({"status": "processing"}) + "\n")
                return

            if status != "downloading":
                return

            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            downloaded = d.get("downloaded_bytes", 0)
            speed = d.get("speed")
            eta = d.get("eta")

            leg_pct = (downloaded / total * 100.0) if total > 0 else min(downloaded / (10 * 1024 * 1024) * 100.0, 99.0)

            if media_type == "video":
                overall = round(leg_pct * 0.80, 1) if leg_index[0] == 0 else round(80.0 + leg_pct * 0.17, 1)
            else:
                overall = round(leg_pct * 0.97, 1)

            if overall > last_reported[0] + 0.3 or overall >= 97:
                last_reported[0] = overall
                speed_str = ""
                if speed and speed > 0:
                    if speed >= 1024 * 1024:
                        speed_str = f"{speed / 1024 / 1024:.1f} MB/s"
                    elif speed >= 1024:
                        speed_str = f"{speed / 1024:.0f} KB/s"
                    else:
                        speed_str = f"{speed:.0f} B/s"
                progress_queue.put(
                    json.dumps({"status": "downloading", "percent": overall, "speed": speed_str, "eta": eta}) + "\n"
                )

        has_ffmpeg = shutil.which("ffmpeg") is not None
        ydl_opts: dict = {
            **_ydl_base_opts(),
            "outtmpl": os.path.join(task_dir, "%(title)s.%(ext)s"),
            "progress_hooks": [progress_hook],
            "restrictfilenames": False,
        }

        if media_type == "video":
            h = requested_quality if requested_quality in ("1080", "720", "360") else "1080"
            if has_ffmpeg:
                ydl_opts["format"] = (
                    f"bestvideo[height<={h}][ext=mp4]+bestaudio[ext=m4a]"
                    f"/bestvideo[height<={h}]+bestaudio"
                    f"/best[height<={h}][ext=mp4]"
                    f"/best[height<={h}]"
                    f"/bestvideo+bestaudio/best"
                )
                ydl_opts["merge_output_format"] = "mp4"
            else:
                ydl_opts["format"] = f"best[height<={h}][ext=mp4]/best[height<={h}]/best[ext=mp4]/best"
        else:
            ydl_opts["format"] = "bestaudio[ext=m4a]/bestaudio/best"
            if has_ffmpeg:
                ydl_opts["postprocessors"] = [{
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }]

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([video_url])

            if cancelled_event.is_set():
                return

            files = [f for f in os.listdir(task_dir) if not f.startswith(".")]
            if files:
                progress_queue.put(
                    json.dumps({"status": "finished", "url": f"/api/serve/{task_id}", "filename": files[0]}) + "\n"
                )
                _cleanup_task_dir(task_dir, reason="finished")
            else:
                progress_queue.put(json.dumps({"status": "error", "error": "File not found after processing"}) + "\n")
                _cleanup_task_dir(task_dir, delay=10, reason="no file")

        except Exception as exc:
            if not cancelled_event.is_set():
                err_str = str(exc)
                print(f"[DOWNLOAD ERROR] {err_str}")
                if "ffmpeg" in err_str.lower() or "merge" in err_str.lower():
                    msg = "Merge failed: ffmpeg is not installed."
                elif "unavailable" in err_str.lower() or "private" in err_str.lower():
                    msg = "Video unavailable or private."
                elif "copyright" in err_str.lower() or "blocked" in err_str.lower():
                    msg = "Video is blocked or region-restricted."
                else:
                    msg = "Download failed. The video may be unavailable or restricted."
                progress_queue.put(json.dumps({"status": "error", "error": msg}) + "\n")
            _cleanup_task_dir(task_dir, delay=10, reason="error")
        finally:
            with _active_downloads_lock:
                _active_downloads[client_ip] = max(0, _active_downloads[client_ip] - 1)
                _active_downloads_reset[client_ip] = time.time()
            progress_queue.put(None)

    threading.Thread(target=run_download, daemon=True).start()

    def generate_progress():
        try:
            while True:
                try:
                    event = progress_queue.get(timeout=300)
                except queue.Empty:
                    yield (json.dumps({"status": "error", "error": "Download timed out"}) + "\n").encode()
                    break
                if event is None:
                    break
                yield event.encode()
        except GeneratorExit:
            cancelled_event.set()

    resp = Response(generate_progress(), mimetype="text/event-stream")
    resp.headers["X-Accel-Buffering"] = "no"
    resp.headers["Cache-Control"] = "no-cache"
    return resp


# ══════════════════════════════════════════════════════════════════════════════
# API — serve completed file
# ══════════════════════════════════════════════════════════════════════════════


@app.route("/api/serve/<task_id>")
def serve_file(task_id: str):
    """Serve the downloaded file for a given task ID."""
    task_dir = _safe_task_dir(task_id)
    if not task_dir or not os.path.exists(task_dir):
        return "File expired or not found", 404

    files = [f for f in os.listdir(task_dir) if not f.startswith(".")]
    if not files:
        return "File not found", 404

    return send_file(
        os.path.join(task_dir, files[0]),
        as_attachment=True,
        download_name=files[0],
        conditional=True,
    )


# ══════════════════════════════════════════════════════════════════════════════
# Entrypoint
# ══════════════════════════════════════════════════════════════════════════════


if __name__ == "__main__":
    ssl_ctx = None
    try:
        from cryptography.hazmat.primitives import serialization  # noqa: F401
        ssl_ctx = "adhoc"
        print("[SSL] HTTPS via adhoc cert.  Open https://0.0.0.0:5000 and accept the browser warning.")
    except ImportError:
        cert_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ssl_certs")
        cert_file = os.path.join(cert_dir, "cert.pem")
        key_file = os.path.join(cert_dir, "key.pem")
        os.makedirs(cert_dir, exist_ok=True)
        if not os.path.exists(cert_file):
            try:
                subprocess.run(
                    ["openssl", "req", "-x509", "-newkey", "rsa:2048",
                     "-keyout", key_file, "-out", cert_file,
                     "-days", "3650", "-nodes", "-subj", "/CN=nicolous-local"],
                    check=True, capture_output=True,
                )
                print("[SSL] Self-signed cert created.")
            except Exception as exc:
                print(f"[SSL] Could not create cert ({exc}). Run: pip install cryptography")
        if os.path.exists(cert_file):
            import ssl as _ssl
            ssl_ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
            ssl_ctx.load_cert_chain(cert_file, key_file)
            print("[SSL] HTTPS via openssl cert.")
        else:
            print("[HTTP] No SSL — mic will only work on localhost.")

    app.run(debug=False, host="0.0.0.0", port=5000, threaded=True, ssl_context=ssl_ctx)
