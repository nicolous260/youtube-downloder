## YouTube Downloder

> A self-hosted YouTube downloader and media manager with a clean, modern web UI.

![Python](https://img.shields.io/badge/Python-3.8%2B-blue?style=flat-square&logo=python)
![Flask](https://img.shields.io/badge/Flask-2.x-lightgrey?style=flat-square&logo=flask)
![yt-dlp](https://img.shields.io/badge/yt--dlp-latest-red?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)

---

## Features

- **Search YouTube** — search by keyword or paste a video/playlist URL directly
- **Download video or audio** — choose MP4 (360p / 720p / 1080p) or extract MP3 at 192 kbps
- **Real-time progress** — live download bar with speed and ETA streamed via SSE
- **Playlist support** — fetch up to 100 entries from any playlist URL
- **Stream proxy** — built-in proxy with Range request support for in-browser playback
- **Download history** — client-side history drawer so you can re-download past items
- **Rate limiting** — max 3 concurrent downloads per IP with `Retry-After` header
- **Dark / light theme** — toggle in the nav bar, persists across sessions
- **Self-hosted fonts** — Material Symbols and Plus Jakarta Sans are cached locally; no external calls at runtime
- **HTTPS out of the box** — auto-generates a self-signed cert on first run (requires `cryptography` or `openssl`)

---

## Requirements

| Dependency | Notes |
|---|---|
| Python 3.8+ | |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Core download engine |
| [Flask](https://flask.palletsprojects.com/) | Web server |
| [requests](https://requests.readthedocs.io/) | HTTP client |
| ffmpeg *(recommended)* | Required for 720p/1080p video+audio merging. Without it, downloads fall back to pre-muxed formats (max ~480p). |
| `cryptography` *(optional)* | Enables adhoc HTTPS cert. Alternatively, `openssl` in PATH works too. |

---

## Installation

```bash
# 1. Clone the repo
https://github.com/nicolous260/youtube-downloder.git

cd youtube-downloder

# 2. Install Python dependencies
pip install flask yt-dlp requests cryptography

# 3. (Recommended) Install ffmpeg
# macOS:   brew install ffmpeg
# Ubuntu:  sudo apt install ffmpeg
# Windows: https://ffmpeg.org/download.html

# 4. Run the app
python app.py
```

Then open **https://localhost:5000** in your browser. Accept the self-signed certificate warning on first visit.

---

## Cookies (age-restricted / sign-in required content)

Some videos require a YouTube session cookie. Export your browser cookies to a `cookies.txt` file (Netscape format) and either:

- Place it at `/root/ytdl/cookies.txt`, **or**
- Set the environment variable:

```bash
YTDL_COOKIES=/path/to/cookies.txt python app.py
```

---

## Project Structure

```
Youtube downloder/
├── app.py                  # Flask app — all routes and backend logic
├── static/
│   ├── css/
│   │   └── main.css        # UI styles (dark/light theme variables)
│   └── js/
│       └── main.js         # Frontend logic (search, download, history)
├── templates/
│   └── index.html          # Single-page shell
└── font_cache/             # Auto-created; cached Google Fonts (woff2 + CSS)
```

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/search` | Search YouTube or resolve a URL/playlist. Body: `{ query, page }` |
| `POST` | `/api/info` | Fetch metadata for a single video ID or URL. Body: `{ id }` |
| `POST` | `/api/download` | Stream download progress (SSE). Body: `{ id, type, quality }` |
| `GET` | `/api/stream/<video_id>` | Proxy a YouTube stream URL with Range support |
| `GET` | `/api/serve/<task_id>` | Serve a completed download for 2 minutes after finishing |

### Download request body

```json
{
  "id": "dQw4w9WgXcQ",
  "type": "video",        // "video" | "audio"
  "quality": "1080"       // "360" | "720" | "1080" (video only)
}
```

### Download event stream

Each line in the SSE stream is a JSON object:

```jsonc
{ "status": "downloading", "percent": 42.3, "speed": "3.1 MB/s", "eta": 12 }
{ "status": "processing" }
{ "status": "finished", "url": "/api/serve/<task_id>", "filename": "video.mp4" }
{ "status": "error", "error": "Video unavailable or private." }
```

---

## Configuration

All tuneable constants live at the top of `app.py`:

| Constant | Default | Description |
|---|---|---|
| `MAX_DOWNLOADS_PER_IP` | `3` | Concurrent download slots per IP |
| `TASK_DIR_TTL` | `120` | Seconds a finished file is kept before deletion |
| `STREAM_CACHE_TTL` | `3600` | Seconds a resolved stream URL is cached |
| `SEARCH_CACHE_MAX` | `30` | Max distinct search queries kept in memory |
| `PER_PAGE` | `12` | Search results per page |

---

## Notes

- Downloaded files are stored in a system temp directory and **automatically deleted** 2 minutes after the download completes.
- The stream cache evicts entries after 1 hour (YouTube signed URLs typically expire after ~6 hours).
- Path-traversal protection is enforced on all task IDs — only valid UUID4 values are accepted.

---

## License

MIT — do whatever you like, but please don't use this to infringe on anyone's content rights.
