# YouTube Downloader & Media Manager

> A robust, self-hosted YouTube downloader and media manager featuring a modern Python/Flask backend (`yt-dlp`) and a native **Jetpack Compose Android Application** with Material 3 design.

![Python](https://img.shields.io/badge/Python-3.8%2B-blue?style=flat-square&logo=python)
![Flask](https://img.shields.io/badge/Flask-2.x-lightgrey?style=flat-square&logo=flask)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Android-green?style=flat-square&logo=android)
![yt-dlp](https://img.shields.io/badge/yt--dlp-latest-red?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)

---

## Architecture & Project Structure

```
youtube-downloder/
├── app.py                      # Flask backend app — search, stream proxy, yt-dlp download worker
├── static/                     # Web UI static assets (CSS, JS)
├── templates/                  # Web UI single-page HTML
├── app/                        # Native Android App (Jetpack Compose + Material 3)
│   ├── src/main/
│   │   ├── java/com/nicolous/youtubedownloader/
│   │   │   ├── MainActivity.kt
│   │   │   ├── data/api/YouTubeApiService.kt
│   │   │   ├── data/model/Models.kt
│   │   │   └── ui/screens/     # MainScreen, DownloadDialog
│   │   ├── res/                # Vector app icons, themes, strings
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── ...
└── README.md
```

---

## Features

### 🖥️ Python / Flask Backend
- **Search YouTube** — search by keyword or paste video/playlist URLs directly.
- **Download Engine (`yt-dlp`)** — robust extraction with anti-bot adjustments and player client fallbacks.
- **Stream Proxy** — built-in proxy with HTTP Range request support for in-browser/in-app playback.
- **Real-Time Progress** — live Server-Sent Events (SSE) streaming download percentage, speed, and ETA.
- **Rate Limiting** — concurrent download limits per IP with `Retry-After` headers.
- **Self-Hosted Fonts & HTTPS** — caches Material Symbols / Plus Jakarta Sans locally and auto-generates self-signed TLS certs.

### 📱 Native Android App (Jetpack Compose)
- **Modern Material 3 UI** — dark theme, polished YouTube-inspired design system, cards, and smooth animations.
- **Live Search & Thumbnails** — async image loading via Coil and lazy columns.
- **Interactive Download Dialog** — choose between **Video (MP4)** with quality selection (`360p`, `720p`, `1080p`) or **Audio (MP3)** at 192 kbps.
- **Background Downloader** — downloads media directly via OkHttp and saves files straight to the phone's public **Downloads** folder (`/storage/emulated/0/Download`).
- **Custom Adaptive App Icon** — professional vector app launcher icon.

---

## Requirements

| Dependency | Notes |
|---|---|
| Python 3.8+ | Backend server |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Core download engine |
| [Flask](https://flask.palletsprojects.com/) | Web framework |
| [requests](https://requests.readthedocs.io/) | HTTP client |
| `ffmpeg` *(recommended)* | Required for 720p/1080p video+audio merging. Without it, downloads fall back to pre-muxed formats (max ~480p). |
| Android SDK / Gradle | To build and run the Android app |

---

## Installation & Setup

### 1. Python Backend
```bash
# Clone repository
git clone https://github.com/nicolous260/youtube-downloder.git
cd youtube-downloder

# Install Python dependencies
pip install flask yt-dlp requests cryptography

# (Optional - Recommended for HD merging)
# Ubuntu/Debian: sudo apt install ffmpeg
# macOS: brew install ffmpeg

# Run backend server
python app.py
```

### 2. Native Android App
1. Open the project folder in **Android Studio**.
2. Connect an Android device or emulator via USB (enable USB debugging).
3. Ensure adb reverse is set up for local communication:
   ```bash
   adb reverse tcp:5000 tcp:5000
   ```
4. Click **Run** (`Shift + F10`) to build and install the app on your device.

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/search` | Search YouTube or resolve a URL/playlist. Body: `{ query, page }` |
| `POST` | `/api/info` | Fetch metadata for a single video ID or URL. Body: `{ id }` |
| `POST` | `/api/download` | Stream download progress (SSE). Body: `{ id, type, quality }` |
| `GET` | `/api/stream/<video_id>` | Proxy a YouTube stream URL with Range support |
| `GET` | `/api/serve/<task_id>` | Serve a completed download file |

---

## License

MIT — feel free to use, modify, and distribute.
