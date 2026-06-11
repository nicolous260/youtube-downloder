/**
 * nicolous260 — main.js
 *
 * Features
 * ─────────
 * • Theme toggle with localStorage persistence
 * • Search overlay with / keyboard shortcut and clear button
 * • Infinite scroll for search results
 * • Video cards: lazy thumbnail, inline playback, duration badge,
 *   quality picker, video download, audio download, copy-link button
 * • Download progress: real-time percent + speed + ETA
 * • Toast notification system (success / error / info / warning)
 * • Download history drawer (persisted in localStorage, max 50 entries)
 * • Playlist URL detection with result count badge
 * • Rate-limit handling with retry countdown
 */

'use strict';

/* ══════════════════════════════════════════════════════════════════════════
   UTILITIES
   ══════════════════════════════════════════════════════════════════════════ */

/** Escape a string for safe HTML insertion. */
function _esc(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/** Escape a string for use inside a JS single-quoted attribute (onclick=…). */
function _escAttr(str) {
    return String(str).replace(/'/g, "\\'").replace(/\\/g, '\\\\');
}

/** Format a number of views nicely: 1 234 567 → "1.2M views" */
function _fmtViews(n) {
    if (!n) return '';
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M views`;
    if (n >= 1_000)     return `${(n / 1_000).toFixed(0)}K views`;
    return `${n} views`;
}

/* ══════════════════════════════════════════════════════════════════════════
   THEME
   ══════════════════════════════════════════════════════════════════════════ */

(function initTheme() {
    const saved = localStorage.getItem('theme');
    if (saved === 'light') {
        document.body.setAttribute('data-theme', 'light');
        const icon = document.getElementById('themeIcon');
        if (icon) icon.innerText = 'dark_mode';
    }
})();

function toggleTheme() {
    const body = document.body;
    const icon = document.getElementById('themeIcon');
    const isDark = body.getAttribute('data-theme') === 'dark';
    body.setAttribute('data-theme', isDark ? 'light' : 'dark');
    icon.innerText = isDark ? 'dark_mode' : 'light_mode';
    localStorage.setItem('theme', isDark ? 'light' : 'dark');
}

/* ══════════════════════════════════════════════════════════════════════════
   TOAST NOTIFICATIONS
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Show a toast notification.
 * @param {string} message
 * @param {'success'|'error'|'info'|'warning'} type
 * @param {number} [duration=3000] ms before auto-dismiss
 */
function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    const iconMap = { success: 'check_circle', error: 'error', info: 'info', warning: 'warning' };

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <span class="material-symbols-rounded toast-icon">${iconMap[type] || 'info'}</span>
        <span>${_esc(message)}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'toastOut 0.22s ease forwards';
        toast.addEventListener('animationend', () => toast.remove());
    }, duration);
}

/* ══════════════════════════════════════════════════════════════════════════
   SEARCH OVERLAY
   ══════════════════════════════════════════════════════════════════════════ */

function openSearch() {
    document.getElementById('navbar').classList.add('search-active');
    setTimeout(() => document.getElementById('query').focus(), 50);
}

function closeSearch() {
    document.getElementById('navbar').classList.remove('search-active');
}

function clearSearch() {
    const input = document.getElementById('query');
    input.value = '';
    input.focus();
    document.getElementById('clearBtn').style.display = 'none';
}

function handleSearchKey(e) {
    const clearBtn = document.getElementById('clearBtn');
    clearBtn.style.display = e.target.value ? 'flex' : 'none';
    if (e.key === 'Enter') executeSearch();
}

// Global keyboard shortcut: '/' opens search
document.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement.tagName !== 'INPUT') {
        e.preventDefault();
        openSearch();
    }
    if (e.key === 'Escape') {
        closeSearch();
        closeRecognizeModal();
        closeHistory();
    }
});

/* ══════════════════════════════════════════════════════════════════════════
   SEARCH + INFINITE SCROLL
   ══════════════════════════════════════════════════════════════════════════ */

let _currentQuery  = '';
let _currentPage   = 1;
let _hasMore       = false;
let _loadingMore   = false;
let _scrollObserver = null;

function _setupScrollObserver() {
    if (_scrollObserver) _scrollObserver.disconnect();
    const sentinel = document.getElementById('scrollSentinel');
    if (!sentinel) return;
    _scrollObserver = new IntersectionObserver((entries) => {
        if (entries[0].isIntersecting && _hasMore && !_loadingMore) {
            _loadMoreResults();
        }
    }, { rootMargin: '200px' });
    _scrollObserver.observe(sentinel);
}

async function executeSearch() {
    const input = document.getElementById('query');
    const query = input.value.trim();
    const grid  = document.getElementById('videoGrid');
    if (!query) return;

    _currentQuery = query;
    _currentPage  = 1;
    _hasMore      = false;
    _loadingMore  = false;
    if (_scrollObserver) _scrollObserver.disconnect();

    closeSearch();

    grid.innerHTML = `
        <div style="grid-column:1/-1;text-align:center;color:var(--text-sub);padding:80px 0;
                    display:flex;flex-direction:column;align-items:center;gap:14px;">
            <span class="material-symbols-rounded"
                  style="font-size:40px;width:40px;height:40px;opacity:0.4;animation:spin 1s linear infinite;">
                progress_activity
            </span>
            <span style="font-size:14px;">Searching…</span>
        </div>`;

    try {
        const resp = await fetch('/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query, page: 1 }),
        });
        const data = await resp.json();

        if (!data.success) {
            grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;color:var(--error);padding:80px 0;">
                ${_esc(data.error || 'Search failed.')}
            </div>`;
            return;
        }
        if (!data.results || data.results.length === 0) {
            grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;color:var(--text-sub);padding:80px 0;">
                No results found. Try a different query.
            </div>`;
            return;
        }

        _hasMore = data.has_more;
        grid.innerHTML = '';
        data.results.forEach((item, i) => grid.appendChild(_buildCard(item, i)));

        const sentinel = document.createElement('div');
        sentinel.id = 'scrollSentinel';
        sentinel.style.cssText = 'grid-column:1/-1;display:flex;justify-content:center;padding:24px 0;min-height:40px;';
        grid.appendChild(sentinel);
        _setupScrollObserver();

        if (data.total > 1) {
            showToast(`${data.total} results${data.from_cache ? ' (cached)' : ''}`, 'info', 2000);
        }
    } catch (e) {
        grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;color:var(--error);padding:80px 0;">
            Network error — check server logs.
        </div>`;
    }
}

async function _loadMoreResults() {
    if (_loadingMore || !_hasMore) return;
    _loadingMore = true;
    _currentPage++;

    const sentinel = document.getElementById('scrollSentinel');
    if (sentinel) sentinel.innerHTML = `
        <span class="material-symbols-rounded"
              style="font-size:28px;width:28px;height:28px;opacity:0.4;animation:spin 1s linear infinite;">
            progress_activity
        </span>`;

    try {
        const resp = await fetch('/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: _currentQuery, page: _currentPage }),
        });
        const data = await resp.json();
        if (data.success && data.results.length > 0) {
            _hasMore = data.has_more;
            const grid = document.getElementById('videoGrid');
            const start = (_currentPage - 1) * 12;
            data.results.forEach((item, i) => grid.insertBefore(_buildCard(item, start + i), sentinel));
        } else {
            _hasMore = false;
        }
    } catch (_) {}

    if (sentinel) {
        sentinel.innerHTML = _hasMore
            ? ''
            : `<span style="font-size:12px;color:var(--text-sub);">— End of results —</span>`;
        if (!_hasMore && _scrollObserver) _scrollObserver.disconnect();
    }
    _loadingMore = false;
}

/* ══════════════════════════════════════════════════════════════════════════
   CARD BUILDER
   ══════════════════════════════════════════════════════════════════════════ */

function _buildCard(item, index) {
    const card = document.createElement('div');
    card.className = 'video-card';
    card.style.animationDelay = `${(index % 12) * 40}ms`;

    const viewsStr = _fmtViews(item.view_count);
    const metaLine = [item.uploader, viewsStr, item.duration].filter(Boolean).join(' · ');

    card.innerHTML = `
        <div class="thumbnail-wrapper" onclick="playVideoInline(this,'${_esc(item.id)}')">
            <img class="thumbnail-img" alt=""
                 data-src="${_esc(item.thumbnail)}"
                 data-fallback="https://i.ytimg.com/vi/${_esc(item.id)}/hqdefault.jpg">
            <div class="duration-badge">${_esc(item.duration || '')}</div>
            <div class="play-overlay-icon">
                <span class="material-symbols-rounded" style="font-size:28px;width:28px;height:28px;">play_arrow</span>
            </div>
        </div>
        <div class="video-info">
            <div>
                <div class="video-title">${_esc(item.title)}</div>
                <div class="video-meta">${_esc(metaLine)}</div>
            </div>
            <div class="action-row">
                <div class="quality-menu" id="menu-${_esc(item.id)}">
                    <div class="quality-item best"
                         onclick="triggerDownload('${_esc(item.id)}','video','1080')">
                        <span class="material-symbols-rounded" style="font-size:16px;width:16px;height:16px;">hd</span>
                        1080p Full HD
                        <span class="badge">Best</span>
                    </div>
                    <div class="quality-item"
                         onclick="triggerDownload('${_esc(item.id)}','video','720')">
                        <span class="material-symbols-rounded" style="font-size:16px;width:16px;height:16px;">hd</span>
                        720p HD
                    </div>
                    <div class="quality-item"
                         onclick="triggerDownload('${_esc(item.id)}','video','360')">
                        <span class="material-symbols-rounded" style="font-size:16px;width:16px;height:16px;">sd</span>
                        360p SD
                        <span class="badge">Smallest</span>
                    </div>
                </div>
                <div class="btn-container">
                    <button class="btn btn-download"
                            onclick="toggleQualityMenu(this,'${_esc(item.id)}')">
                        <div class="progress-bar-fill"></div>
                        <span class="material-symbols-rounded">smart_display</span>
                        <span>Video</span>
                    </button>
                </div>
                <div class="btn-container">
                    <button class="btn btn-audio"
                            onclick="triggerDownload('${_esc(item.id)}','audio','best')">
                        <div class="progress-bar-fill"></div>
                        <span class="material-symbols-rounded">audiotrack</span>
                        <span>Audio</span>
                    </button>
                </div>
                <button class="copy-btn action-icon-btn"
                        onclick="copyLink('${_esc(item.id)}', this)"
                        title="Copy YouTube link">
                    <span class="material-symbols-rounded" style="font-size:18px;width:18px;height:18px;">link</span>
                </button>
            </div>
        </div>`;

    // Lazy-load thumbnail via IntersectionObserver
    const img     = card.querySelector('.thumbnail-img');
    const wrapper = card.querySelector('.thumbnail-wrapper');
    const observer = new IntersectionObserver((entries) => {
        if (entries[0].isIntersecting) {
            img.src = img.dataset.src;
            img.onload  = () => { img.classList.add('img-loaded'); wrapper.classList.add('loaded'); };
            img.onerror = () => { img.src = img.dataset.fallback; img.classList.add('img-loaded'); };
            observer.disconnect();
        }
    });
    observer.observe(card);

    return card;
}

/* ══════════════════════════════════════════════════════════════════════════
   INLINE PLAYER
   ══════════════════════════════════════════════════════════════════════════ */

function playVideoInline(wrapper, videoId) {
    wrapper.classList.add('loaded');
    wrapper.onclick = null;
    wrapper.innerHTML = `
        <video src="/api/stream/${videoId}"
               controls autoplay playsinline muted
               style="position:absolute;inset:0;width:100%;height:100%;z-index:3;
                      opacity:0;transition:opacity 0.3s;object-fit:cover;">
        </video>`;
    const vid = wrapper.querySelector('video');
    if (vid) {
        vid.onloadeddata = () => { vid.style.opacity = 1; };
        vid.play().then(() => { vid.muted = false; }).catch(() => {});
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   QUALITY MENU
   ══════════════════════════════════════════════════════════════════════════ */

function toggleQualityMenu(button, id) {
    const menu   = document.getElementById(`menu-${id}`);
    const isOpen = menu.style.display === 'flex';
    document.querySelectorAll('.quality-menu').forEach(m => (m.style.display = 'none'));
    if (!isOpen) {
        menu.style.display = 'flex';
        const close = (e) => {
            if (!button.contains(e.target) && !menu.contains(e.target)) {
                menu.style.display = 'none';
                document.removeEventListener('click', close);
            }
        };
        setTimeout(() => document.addEventListener('click', close), 10);
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   COPY LINK
   ══════════════════════════════════════════════════════════════════════════ */

async function copyLink(videoId, btn) {
    const url = `https://www.youtube.com/watch?v=${videoId}`;
    try {
        await navigator.clipboard.writeText(url);
        const icon = btn.querySelector('.material-symbols-rounded');
        icon.innerText = 'check';
        showToast('Link copied!', 'success', 2000);
        setTimeout(() => { icon.innerText = 'link'; }, 1500);
    } catch (_) {
        showToast('Could not copy link', 'error');
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   DOWNLOAD
   ══════════════════════════════════════════════════════════════════════════ */

async function triggerDownload(id, downloadType, quality) {
    const menu = document.getElementById(`menu-${id}`);
    if (menu) menu.style.display = 'none';

    const card = menu
        ? menu.closest('.video-card')
        : document.querySelector(`[onclick*="${id}"]`)?.closest('.video-card');
    if (!card) return;

    const rowButtons = card.querySelectorAll('.action-row button.btn');
    let targetBtn = null;
    rowButtons.forEach(btn => {
        if (downloadType === 'video' && btn.classList.contains('btn-download')) targetBtn = btn;
        if (downloadType === 'audio' && btn.classList.contains('btn-audio'))    targetBtn = btn;
    });
    if (!targetBtn) return;

    const iconSpan  = targetBtn.querySelector('.material-symbols-rounded');
    const textSpan  = targetBtn.querySelector('span:not(.material-symbols-rounded)');
    const fillBar   = targetBtn.querySelector('.progress-bar-fill');
    const origLabel = downloadType === 'video' ? 'Video' : 'Audio';
    const origIcon  = downloadType === 'video' ? 'smart_display' : 'audiotrack';

    const title    = card.querySelector('.video-title')?.innerText || id;
    const uploader = card.querySelector('.video-meta')?.innerText.split('·')[0].trim() || '';

    rowButtons.forEach(b => (b.disabled = true));
    iconSpan.innerText = 'progress_activity';
    iconSpan.style.animation = 'spin 1s linear infinite';
    textSpan.innerText = 'Connecting…';
    if (fillBar) fillBar.style.width = '0%';

    function _resetBtn(delay = 3500) {
        setTimeout(() => {
            iconSpan.style.animation = '';
            iconSpan.innerText = origIcon;
            textSpan.innerText = origLabel;
            if (fillBar) { fillBar.style.width = '0%'; fillBar.style.backgroundColor = ''; }
            rowButtons.forEach(b => (b.disabled = false));
        }, delay);
    }

    try {
        const resp = await fetch('/api/download', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id, type: downloadType, quality }),
        });

        if (resp.status === 429) {
            const errData = await resp.json().catch(() => ({}));
            const retryAfter = errData.retry_after || 30;
            showToast(`Too many downloads — retry in ${retryAfter}s`, 'warning', 4000);
            _resetBtn(0);
            return;
        }
        if (!resp.ok) throw new Error('Network error');

        const reader  = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { value, done } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();

            for (let line of lines) {
                line = line.trim();
                if (!line) continue;
                try {
                    const ev = JSON.parse(line);

                    if (ev.status === 'downloading') {
                        const pct   = ev.percent;
                        const speed = ev.speed ? ` · ${ev.speed}` : '';
                        const eta   = ev.eta   ? ` · ${ev.eta}s`  : '';
                        iconSpan.style.animation = 'spin 1s linear infinite';
                        iconSpan.innerText = 'progress_activity';
                        textSpan.innerText = `${pct}%${speed}`;
                        if (fillBar) fillBar.style.width = `${pct}%`;

                    } else if (ev.status === 'processing') {
                        iconSpan.innerText = 'hourglass_empty';
                        iconSpan.style.animation = 'spin 1.8s linear infinite';
                        textSpan.innerText = 'Merging…';
                        if (fillBar) fillBar.style.width = '99%';

                    } else if (ev.status === 'finished') {
                        iconSpan.style.animation = '';
                        iconSpan.innerText = 'check_circle';
                        textSpan.innerText = 'Ready!';
                        if (fillBar) {
                            fillBar.style.width = '100%';
                            fillBar.style.backgroundColor = 'rgba(34,197,94,0.35)';
                        }

                        if (ev.url) {
                            const a = document.createElement('a');
                            a.href = ev.url;
                            a.style.display = 'none';
                            document.body.appendChild(a);
                            a.click();
                            document.body.removeChild(a);
                        }

                        // Add to history
                        _addHistory({
                            id,
                            title,
                            uploader,
                            thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
                            type: downloadType,
                            quality: downloadType === 'video' ? quality : 'mp3',
                            ts: Date.now(),
                        });
                        showToast(`${origLabel} downloaded!`, 'success');
                        _resetBtn();

                    } else if (ev.status === 'error') {
                        iconSpan.style.animation = '';
                        iconSpan.innerText = 'error';
                        textSpan.innerText = 'Error';
                        showToast(ev.error || 'Download failed', 'error', 5000);
                        _resetBtn(3000);
                    }
                } catch (_) {}
            }
        }
    } catch (netErr) {
        iconSpan.style.animation = '';
        iconSpan.innerText = 'error';
        textSpan.innerText = 'Error';
        showToast('Network error during download', 'error', 5000);
        _resetBtn(2500);
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   DOWNLOAD HISTORY
   ══════════════════════════════════════════════════════════════════════════ */

const HISTORY_KEY = 'nicolous_history';
const HISTORY_MAX = 50;

function _loadHistory() {
    try {
        return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
    } catch (_) {
        return [];
    }
}

function _saveHistory(items) {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(items));
}

function _addHistory(entry) {
    const items = _loadHistory();
    // Deduplicate by id+type — keep newest
    const filtered = items.filter(h => !(h.id === entry.id && h.type === entry.type));
    filtered.unshift(entry);
    _saveHistory(filtered.slice(0, HISTORY_MAX));
}

function showHistory() {
    const drawer   = document.getElementById('historyDrawer');
    const backdrop = document.getElementById('drawerBackdrop');
    const list     = document.getElementById('historyList');
    drawer.classList.add('open');
    backdrop.classList.add('open');

    const items = _loadHistory();
    if (items.length === 0) {
        list.innerHTML = `
            <div class="history-empty">
                <span class="material-symbols-rounded" style="font-size:40px;width:40px;height:40px;opacity:0.3;">
                    history
                </span>
                No downloads yet
            </div>`;
        return;
    }

    list.innerHTML = items.map(h => {
        const ago = _timeAgo(h.ts);
        const badge = h.type === 'video'
            ? `<span class="history-badge video">${h.quality || 'MP4'}</span>`
            : `<span class="history-badge audio">MP3</span>`;
        return `
            <div class="history-item" onclick="searchFromHistory('${_escAttr(h.title + ' ' + h.uploader)}')">
                <img class="history-thumb" src="${_esc(h.thumbnail)}"
                     onerror="this.src='https://i.ytimg.com/vi/${_esc(h.id)}/default.jpg'" alt="">
                <div class="history-info">
                    <div class="history-title">${_esc(h.title)}</div>
                    <div class="history-meta">${_esc(h.uploader)} · ${ago}</div>
                </div>
                ${badge}
            </div>`;
    }).join('');
}

function closeHistory() {
    document.getElementById('historyDrawer').classList.remove('open');
    document.getElementById('drawerBackdrop').classList.remove('open');
}

function clearHistory() {
    localStorage.removeItem(HISTORY_KEY);
    showHistory();
    showToast('History cleared', 'info', 2000);
}

function searchFromHistory(query) {
    closeHistory();
    openSearch();
    setTimeout(() => {
        const input = document.getElementById('query');
        input.value = query;
        document.getElementById('clearBtn').style.display = 'flex';
        executeSearch();
    }, 60);
}

function _timeAgo(ts) {
    const diff = Math.floor((Date.now() - ts) / 1000);
    if (diff < 60)       return 'just now';
    if (diff < 3600)     return `${Math.floor(diff / 60)}m ago`;
    if (diff < 86400)    return `${Math.floor(diff / 3600)}h ago`;
    if (diff < 604800)   return `${Math.floor(diff / 86400)}d ago`;
    return new Date(ts).toLocaleDateString();
}
