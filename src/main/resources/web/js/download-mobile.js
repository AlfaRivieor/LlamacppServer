(function () {
    const state = {
        started: false,
        downloads: [],
        websocket: null,
        reconnectAttempts: 0,
        maxReconnectAttempts: 5,
        reconnectIntervalMs: 5000,
        speedByTaskId: {}
    };

    function byId(id) {
        return document.getElementById(id);
    }

    function showToast(title, msg, type) {
        if (typeof window.showToast === 'function') {
            window.showToast(title, msg, type);
        }
    }

    function escapeHtml(v) {
        const s = v == null ? '' : String(v);
        return s.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatFileSize(bytes) {
        const n = Number(bytes);
        if (!Number.isFinite(n) || n < 0) return '-';
        if (n < 1024) return `${Math.round(n)} B`;
        if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
        if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
        return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    }

    function formatSpeed(bps) {
        const n = Number(bps);
        if (!Number.isFinite(n) || n <= 0) return '-';
        return `${formatFileSize(n)}/s`;
    }

    function statusMeta(stateName) {
        const s = stateName == null ? '' : String(stateName).toUpperCase();
        if (s === 'DOWNLOADING') return { text: '下载中', icon: 'fa-spinner fa-spin', cls: 'status-downloading' };
        if (s === 'IDLE') return { text: '等待中', icon: 'fa-clock', cls: 'status-idle' };
        if (s === 'COMPLETED') return { text: '已完成', icon: 'fa-check-circle', cls: 'status-completed' };
        if (s === 'FAILED') return { text: '失败', icon: 'fa-exclamation-circle', cls: 'status-failed' };
        if (s === 'PAUSED') return { text: '已暂停', icon: 'fa-pause-circle', cls: 'status-paused' };
        return { text: '未知', icon: 'fa-question-circle', cls: 'status-idle' };
    }

    function updateStats() {
        const list = Array.isArray(state.downloads) ? state.downloads : [];
        const active = list.filter((d) => d && String(d.state || '').toUpperCase() === 'DOWNLOADING').length;
        const pending = list.filter((d) => d && String(d.state || '').toUpperCase() === 'IDLE').length;
        const done = list.filter((d) => d && String(d.state || '').toUpperCase() === 'COMPLETED').length;
        const activeEl = byId('mobileDownloadsActiveCount');
        const pendingEl = byId('mobileDownloadsPendingCount');
        const doneEl = byId('mobileDownloadsDoneCount');
        if (activeEl) activeEl.textContent = String(active);
        if (pendingEl) pendingEl.textContent = String(pending);
        if (doneEl) doneEl.textContent = String(done);
    }

    function renderList() {
        const container = byId('mobileDownloadsList');
        if (!container) return;

        const list = Array.isArray(state.downloads) ? state.downloads : [];
        if (!list.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon"><i class="fas fa-download"></i></div>
                    <div class="empty-state-title">没有下载任务</div>
                    <div class="empty-state-text">点击“新建任务”创建下载</div>
                </div>
            `;
            updateStats();
            return;
        }

        const sorted = list.slice().sort((a, b) => {
            const ta = a && a.createdAt != null ? Number(a.createdAt) : 0;
            const tb = b && b.createdAt != null ? Number(b.createdAt) : 0;
            return tb - ta;
        });

        container.innerHTML = sorted.map((d) => {
            const taskId = d && d.taskId != null ? String(d.taskId) : '';
            const fileName = d && d.fileName != null ? String(d.fileName) : (d && d.url ? String(d.url).split('/').pop() : '');
            const st = statusMeta(d && d.state);
            const total = d && d.totalBytes != null ? Number(d.totalBytes) : 0;
            const done = d && d.downloadedBytes != null ? Number(d.downloadedBytes) : 0;
            const pct = total > 0 ? Math.min(100, Math.max(0, Math.round(done / total * 100))) : 0;
            const speed = state.speedByTaskId[taskId];
            const speedText = formatSpeed(speed);

            const canResume = st.text === '等待中' || st.text === '失败' || st.text === '已暂停';
            const canPause = st.text === '下载中';
            const actions = `
                <div style="display:flex; gap:0.5rem; margin-left:auto;">
                    ${canPause ? `<button class="btn btn-secondary btn-sm" data-dl-act="pause" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-pause"></i></button>` : ''}
                    ${canResume ? `<button class="btn btn-primary btn-sm" data-dl-act="resume" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-play"></i></button>` : ''}
                    <button class="btn btn-danger btn-sm" data-dl-act="delete" data-dl-id="${escapeHtml(taskId)}"><i class="fas fa-trash"></i></button>
                </div>
            `;

            const progressBar = (total > 0 || done > 0) ? `
                <div style="width:100%; margin-top:0.5rem;">
                    <div style="display:flex; gap:0.75rem; font-size:0.8rem; color: var(--text-secondary); flex-wrap: wrap;">
                        <span><i class="fas fa-percentage"></i> ${pct}%</span>
                        <span><i class="fas fa-gauge-high"></i> ${escapeHtml(speedText)}</span>
                        <span><i class="fas fa-download"></i> ${escapeHtml(formatFileSize(done))}</span>
                        ${total > 0 ? `<span><i class="fas fa-hdd"></i> ${escapeHtml(formatFileSize(total))}</span>` : ''}
                    </div>
                    <div class="progress-bar"><div class="progress-fill" style="width:${pct}%;"></div></div>
                </div>
            ` : '';

            return `
                <div class="model-item" data-dl-item="${escapeHtml(taskId)}" style="align-items:flex-start;">
                    <div class="model-details" style="min-width:0;">
                        <div class="model-name" style="margin-bottom:0.35rem; word-break: break-all;">${escapeHtml(fileName || taskId || '任务')}</div>
                        <div class="model-meta" style="gap:0.5rem;">
                            <span class="model-status-badge ${escapeHtml(st.cls)}" style="min-width:auto; padding: 0.25rem 0.6rem;">
                                <i class="fas ${escapeHtml(st.icon)}"></i> <span>${escapeHtml(st.text)}</span>
                            </span>
                        </div>
                        ${progressBar}
                    </div>
                    ${actions}
                </div>
            `;
        }).join('');

        updateStats();
    }

    function mergeUpdate(taskId, patch) {
        const id = taskId == null ? '' : String(taskId);
        if (!id) return;
        const list = Array.isArray(state.downloads) ? state.downloads : [];
        let found = false;
        for (let i = 0; i < list.length; i++) {
            if (list[i] && String(list[i].taskId) === id) {
                state.downloads[i] = { ...list[i], ...patch };
                found = true;
                break;
            }
        }
        if (!found) {
            state.downloads.unshift({ taskId: id, ...patch });
        }
    }

    function handleWsMessage(raw) {
        try {
            const data = JSON.parse(raw);
            if (!data || !data.type) return;
            if (data.type === 'notification') {
                showToast(data.title || '通知', data.message || '', data.level || 'info');
                return;
            }
            if (data.type === 'download_update' && data.taskId) {
                mergeUpdate(data.taskId, data);
                if (data.state) {
                    const st = String(data.state).toUpperCase();
                    if (st === 'COMPLETED') showToast('下载完成', `任务 ${data.fileName || data.taskId} 已完成`, 'success');
                    if (st === 'FAILED') showToast('下载失败', `任务 ${data.fileName || data.taskId} 失败`, 'error');
                }
                renderList();
                return;
            }
            if (data.type === 'download_progress' && data.taskId) {
                const id = String(data.taskId);
                const speed = data.speedBytesPerSecond != null ? Number(data.speedBytesPerSecond) : (data.speed != null ? Number(data.speed) : NaN);
                if (Number.isFinite(speed)) state.speedByTaskId[id] = speed;
                mergeUpdate(id, {
                    downloadedBytes: data.downloadedBytes,
                    totalBytes: data.totalBytes
                });
                renderList();
                return;
            }
        } catch (e) {
        }
    }

    function connectWs() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws`;
        try {
            state.websocket = new WebSocket(wsUrl);
            state.websocket.onopen = function () {
                state.reconnectAttempts = 0;
                try {
                    state.websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
                } catch (e) {
                }
            };
            state.websocket.onmessage = function (event) {
                handleWsMessage(event.data);
            };
            state.websocket.onclose = function () {
                if (!state.started) return;
                if (state.reconnectAttempts >= state.maxReconnectAttempts) return;
                state.reconnectAttempts += 1;
                setTimeout(connectWs, state.reconnectIntervalMs);
            };
            state.websocket.onerror = function () {
            };
        } catch (e) {
        }
    }

    function refresh() {
        const container = byId('mobileDownloadsList');
        if (container) container.innerHTML = `<div class="loading-spinner"><div class="spinner"></div></div>`;
        fetch('/api/downloads/list')
            .then((r) => r.json())
            .then((data) => {
                if (!data || data.success !== true) throw new Error((data && data.error) ? data.error : '获取下载列表失败');
                state.downloads = Array.isArray(data.downloads) ? data.downloads : [];
                renderList();
            })
            .catch((e) => {
                if (container) {
                    container.innerHTML = `
                        <div class="empty-state">
                            <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                            <div class="empty-state-title">加载失败</div>
                            <div class="empty-state-text">${escapeHtml(e && e.message ? e.message : '网络错误')}</div>
                            <button class="btn btn-primary" id="mobileDownloadsRetryBtn">重试</button>
                        </div>
                    `;
                    const retry = byId('mobileDownloadsRetryBtn');
                    if (retry) retry.onclick = refresh;
                }
            });
    }

    function getDownloadPath() {
        return fetch('/api/downloads/path/get')
            .then((r) => r.json())
            .then((data) => (data && data.path) ? String(data.path) : '')
            .catch(() => '');
    }

    function setDownloadPath(path) {
        return fetch('/api/downloads/path/set', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        })
            .then((r) => r.json());
    }

    function createDownload(payload) {
        return fetch('/api/downloads/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then((r) => r.json());
    }

    function pause(taskId) {
        return fetch('/api/downloads/pause', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function resume(taskId) {
        return fetch('/api/downloads/resume', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function del(taskId) {
        return fetch('/api/downloads/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        }).then((r) => r.json());
    }

    function openCreateModal() {
        getDownloadPath().then((path) => {
            const el = byId('mobileDownloadPathInput');
            if (el) el.value = path || '';
        });
        const modal = byId('mobileDownloadCreateModal');
        if (modal) modal.classList.add('show');
    }

    function openSettingsModal() {
        getDownloadPath().then((path) => {
            const el = byId('mobileDefaultDownloadPathInput');
            if (el) el.value = path || '';
        });
        const modal = byId('mobileDownloadSettingsModal');
        if (modal) modal.classList.add('show');
    }

    function start() {
        if (state.started) return;
        state.started = true;
        refresh();
        connectWs();
    }

    function stop() {
        state.started = false;
        state.reconnectAttempts = 0;
        try {
            if (state.websocket) state.websocket.close();
        } catch (e) {
        }
        state.websocket = null;
    }

    document.addEventListener('DOMContentLoaded', function () {
        const list = byId('mobileDownloadsList');
        if (list) {
            list.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('[data-dl-act]') : null;
                if (!btn) return;
                const act = btn.getAttribute('data-dl-act');
                const taskId = btn.getAttribute('data-dl-id');
                if (!taskId) return;
                if (act === 'pause') {
                    pause(taskId).then((data) => {
                        if (data && data.success) showToast('成功', '已暂停', 'success');
                        else showToast('错误', (data && data.error) ? data.error : '暂停失败', 'error');
                    });
                } else if (act === 'resume') {
                    resume(taskId).then((data) => {
                        if (data && data.success) showToast('成功', '已恢复', 'success');
                        else showToast('错误', (data && data.error) ? data.error : '恢复失败', 'error');
                    });
                } else if (act === 'delete') {
                    if (!confirm('确定要删除这个下载任务吗？')) return;
                    del(taskId).then((data) => {
                        if (data && data.success) {
                            showToast('成功', '已删除', 'success');
                            refresh();
                        } else {
                            showToast('错误', (data && data.error) ? data.error : '删除失败', 'error');
                        }
                    });
                }
            });
        }

        const refreshBtn = byId('mobileDownloadRefreshBtn');
        const createBtn = byId('mobileDownloadCreateBtn');
        const settingsBtn = byId('mobileDownloadSettingsBtn');
        const createSubmit = byId('mobileDownloadCreateSubmitBtn');
        const settingsSave = byId('mobileDownloadSettingsSaveBtn');

        if (refreshBtn) refreshBtn.addEventListener('click', refresh);
        if (createBtn) createBtn.addEventListener('click', openCreateModal);
        if (settingsBtn) settingsBtn.addEventListener('click', openSettingsModal);

        if (createSubmit) {
            createSubmit.addEventListener('click', function () {
                const urlEl = byId('mobileDownloadUrlInput');
                const pathEl = byId('mobileDownloadPathInput');
                const fileNameEl = byId('mobileDownloadFileNameInput');
                const url = urlEl ? String(urlEl.value || '').trim() : '';
                const path = pathEl ? String(pathEl.value || '').trim() : '';
                const fileName = fileNameEl ? String(fileNameEl.value || '').trim() : '';
                if (!url) {
                    showToast('错误', '请输入下载URL', 'error');
                    return;
                }
                if (!path) {
                    showToast('错误', '请输入保存路径', 'error');
                    return;
                }
                const payload = { url, path };
                if (fileName) payload.fileName = fileName;
                createDownload(payload).then((data) => {
                    if (data && data.success) {
                        showToast('成功', '任务已创建', 'success');
                        if (typeof window.closeModal === 'function') window.closeModal('mobileDownloadCreateModal');
                        refresh();
                    } else {
                        showToast('错误', (data && data.error) ? data.error : '创建失败', 'error');
                    }
                }).catch(() => showToast('错误', '网络请求失败', 'error'));
            });
        }

        if (settingsSave) {
            settingsSave.addEventListener('click', function () {
                const el = byId('mobileDefaultDownloadPathInput');
                const path = el ? String(el.value || '').trim() : '';
                if (!path) {
                    showToast('错误', '请输入下载路径', 'error');
                    return;
                }
                setDownloadPath(path).then((data) => {
                    if (data && data.path) {
                        showToast('成功', '已保存', 'success');
                        if (typeof window.closeModal === 'function') window.closeModal('mobileDownloadSettingsModal');
                    } else {
                        showToast('错误', (data && data.error) ? data.error : '保存失败', 'error');
                    }
                }).catch(() => showToast('错误', '网络请求失败', 'error'));
            });
        }
    });

    window.MobileDownloadManager = {
        start,
        stop,
        refresh,
        openCreateModal,
        openSettingsModal
    };
})();

