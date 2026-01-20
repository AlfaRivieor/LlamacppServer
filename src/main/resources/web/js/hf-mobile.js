(function () {
    const state = {
        loading: false,
        query: '',
        base: 'mirror',
        limit: 30,
        nextPage: 0,
        hits: [],
        selectedRepo: '',
        ggufFiles: [],
        groups: [],
        mmprojGroups: []
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

    async function copyText(text) {
        const t = text == null ? '' : String(text);
        if (!t) return false;
        try {
            if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                await navigator.clipboard.writeText(t);
                return true;
            }
        } catch (e) {
        }
        try {
            const ta = document.createElement('textarea');
            ta.value = t;
            ta.setAttribute('readonly', 'readonly');
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            ta.style.top = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            document.body.removeChild(ta);
            return ok;
        } catch (e) {
            return false;
        }
    }

    function parseRepo(repoId) {
        const s = repoId == null ? '' : String(repoId).trim();
        const idx = s.indexOf('/');
        if (idx <= 0 || idx >= s.length - 1) return null;
        return { author: s.slice(0, idx), modelId: s.slice(idx + 1) };
    }

    function isMmprojPath(path) {
        const s = path == null ? '' : String(path).toLowerCase();
        if (!s) return false;
        return s.includes('mmproj') || s.endsWith('.mmproj') || s.endsWith('.bin');
    }

    function shardInfo(path) {
        const p = path == null ? '' : String(path);
        const m = p.match(/-(\d{5})-?of-(\d{5})\.gguf$/i);
        if (!m) return null;
        return {
            baseKey: p.replace(/-(\d{5})-?of-(\d{5})\.gguf$/i, ''),
            partIndex: Number(m[1]),
            partTotal: Number(m[2]),
            displayPath: p.replace(/-(\d{5})-?of-(\d{5})\.gguf$/i, '.gguf')
        };
    }

    function groupFiles(files) {
        const list = Array.isArray(files) ? files : [];
        const normal = [];
        const sharded = new Map();

        for (const f of list) {
            if (!f) continue;
            const path = f.path != null ? String(f.path) : '';
            const dl = f.downloadUrl != null ? String(f.downloadUrl) : '';
            if (!path || !dl) continue;
            const si = shardInfo(path);
            if (!si) {
                normal.push({
                    key: path,
                    displayPath: path,
                    urls: [dl],
                    size: Number.isFinite(Number(f.size)) ? Number(f.size) : null,
                    isMmproj: isMmprojPath(path)
                });
                continue;
            }
            const existing = sharded.get(si.baseKey);
            const item = { path, dl, partIndex: si.partIndex, size: Number.isFinite(Number(f.size)) ? Number(f.size) : null };
            if (!existing) {
                sharded.set(si.baseKey, {
                    key: si.baseKey,
                    displayPath: si.displayPath,
                    partTotal: Number.isFinite(si.partTotal) ? si.partTotal : null,
                    items: [item],
                    isMmproj: isMmprojPath(si.displayPath)
                });
            } else {
                existing.items.push(item);
            }
        }

        const merged = [];
        for (const g of sharded.values()) {
            g.items.sort((a, b) => (a.partIndex || 0) - (b.partIndex || 0));
            merged.push({
                key: g.key,
                displayPath: g.displayPath,
                urls: g.items.map((x) => x.dl),
                size: g.items.reduce((sum, x) => sum + (Number.isFinite(x.size) ? x.size : 0), 0),
                shardCount: g.items.length,
                shardTotal: g.partTotal,
                isMmproj: g.isMmproj
            });
        }

        const all = normal.concat(merged);
        all.sort((a, b) => String(a.displayPath || '').localeCompare(String(b.displayPath || ''), 'zh-CN'));
        return {
            groups: all.filter((g) => !g.isMmproj),
            mmprojGroups: all.filter((g) => g.isMmproj)
        };
    }

    function bestMmprojUrls(mmprojGroups) {
        const list = Array.isArray(mmprojGroups) ? mmprojGroups : [];
        let best = null;
        let bestSize = -1;
        for (const g of list) {
            const size = Number.isFinite(Number(g.size)) ? Number(g.size) : 0;
            if (size > bestSize) {
                bestSize = size;
                best = g;
            }
        }
        return best && Array.isArray(best.urls) ? best.urls.slice() : [];
    }

    function renderHits() {
        const container = byId('mobileHfHits');
        if (!container) return;

        const hits = Array.isArray(state.hits) ? state.hits : [];
        if (!hits.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon"><i class="fas fa-search"></i></div>
                    <div class="empty-state-title">${state.loading ? '搜索中...' : '没有结果'}</div>
                    <div class="empty-state-text">${state.loading ? '正在请求 HuggingFace 数据' : '换个关键词再试试'}</div>
                </div>
            `;
            return;
        }

        container.innerHTML = hits.map((h) => {
            const repoId = h && h.repoId != null ? String(h.repoId) : '';
            const url = h && h.modelUrl != null ? String(h.modelUrl) : '';
            const title = repoId ? escapeHtml(repoId) : '未知';
            const badge = h && h.likes != null ? `<span class="badge"><i class="fas fa-star"></i> ${escapeHtml(h.likes)}</span>` : '';
            const linkBtn = url ? `<button class="btn btn-secondary btn-sm" data-hf-act="open" data-hf-url="${escapeHtml(url)}"><i class="fas fa-external-link-alt"></i> 主页</button>` : '';
            const actions = [linkBtn].filter(Boolean).join('');
            return `
                <div class="model-item" style="align-items: flex-start;">
                    <div class="model-details">
                        <div class="model-name" style="margin-bottom: 0.35rem; cursor:pointer;" data-hf-act="gguf" data-hf-repo="${escapeHtml(repoId)}">${title}</div>
                        <div class="model-meta" style="gap: 0.5rem;">${badge}</div>
                    </div>
                    ${actions ? `<div style="display:flex; gap:0.5rem; margin-left:auto;">${actions}</div>` : ''}
                </div>
            `;
        }).join('');
    }

    function setMoreVisible(visible, enabled) {
        const wrap = byId('mobileHfMore');
        const btn = byId('mobileHfMoreBtn');
        if (wrap) wrap.style.display = visible ? '' : 'none';
        if (btn) btn.disabled = !enabled;
    }

    async function fetchHitsPage(query, base, limit, startPage = 0, maxPages = 1) {
        const url = `/api/hf/search?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`
            + `&startPage=${encodeURIComponent(String(startPage))}&maxPages=${encodeURIComponent(String(maxPages))}&base=${encodeURIComponent(base)}`;
        const resp = await fetch(url);
        const data = await resp.json();
        if (!data || data.success !== true) throw new Error((data && data.error) ? data.error : '搜索失败');
        const hits = data.data && data.data.hits ? data.data.hits : [];
        return Array.isArray(hits) ? hits : [];
    }

    async function search(reset) {
        if (state.loading) return;
        const input = byId('mobileHfQueryInput');
        const baseEl = byId('mobileHfBaseSelect');
        const limitEl = byId('mobileHfLimitSelect');
        const query = input ? String(input.value || '').trim() : '';
        if (!query) {
            showToast('提示', '请输入搜索关键字', 'info');
            return;
        }

        state.query = query;
        state.base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
        state.limit = limitEl ? Number(limitEl.value || 30) : 30;

        if (reset) {
            state.hits = [];
            state.nextPage = 0;
        }

        state.loading = true;
        renderHits();
        setMoreVisible(false, false);

        try {
            const page = state.nextPage;
            const newHits = await fetchHitsPage(state.query, state.base, state.limit, page);
            const seen = new Set(state.hits.map((h) => (h && h.repoId != null) ? String(h.repoId) : ''));
            for (const h of newHits) {
                const id = h && h.repoId != null ? String(h.repoId) : '';
                if (!id || seen.has(id)) continue;
                seen.add(id);
                state.hits.push(h);
            }
            state.nextPage = page + 1;
            renderHits();
            setMoreVisible(newHits.length > 0, true);
            if (!newHits.length) showToast('提示', '没有更多结果了', 'info');
        } catch (e) {
            renderHits();
            setMoreVisible(true, true);
            showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
        } finally {
            state.loading = false;
        }
    }

    function renderGgufList() {
        const listEl = byId('mobileHfGgufList');
        if (!listEl) return;

        if (!state.selectedRepo) {
            listEl.innerHTML = `<div class="empty-state">未选择模型</div>`;
            return;
        }

        if (!state.groups.length) {
            listEl.innerHTML = `<div class="empty-state">未找到 GGUF 文件</div>`;
            return;
        }

        listEl.innerHTML = state.groups.map((g, idx) => {
            const title = escapeHtml(g.displayPath || '');
            const shardText = g.shardCount && g.shardTotal ? `分片 ${g.shardCount}/${g.shardTotal}` : (g.shardCount ? `分片 ${g.shardCount}` : '');
            const badges = shardText ? `<div class="model-meta"><span><i class="fas fa-th-large"></i> ${escapeHtml(shardText)}</span></div>` : '';
            return `
                <div class="model-item" style="align-items: flex-start;">
                    <div class="model-details" style="min-width:0;">
                        <div class="model-name" style="font-size:0.95rem; word-break: break-all;">${title}</div>
                        ${badges}
                    </div>
                    <div style="display:flex; gap:0.5rem; margin-left:auto;">
                        <button class="btn btn-secondary btn-sm" data-hf-gguf-act="copy" data-hf-gguf-idx="${idx}"><i class="fas fa-copy"></i></button>
                        <button class="btn btn-primary btn-sm" data-hf-gguf-act="download" data-hf-gguf-idx="${idx}"><i class="fas fa-download"></i></button>
                    </div>
                </div>
            `;
        }).join('');
    }

    async function openRepo(repoId) {
        const id = repoId == null ? '' : String(repoId).trim();
        if (!id) return;
        state.selectedRepo = id;
        state.ggufFiles = [];
        state.groups = [];
        state.mmprojGroups = [];

        const repoLabel = byId('mobileHfGgufRepo');
        const titleEl = byId('mobileHfGgufTitle');
        if (repoLabel) repoLabel.textContent = id;
        if (titleEl) titleEl.textContent = 'GGUF 文件';

        const listEl = byId('mobileHfGgufList');
        if (listEl) listEl.innerHTML = `<div class="loading-spinner"><div class="spinner"></div></div>`;

        const modal = byId('mobileHfGgufModal');
        if (modal) modal.classList.add('show');

        try {
            const baseEl = byId('mobileHfBaseSelect');
            const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
            const resp = await fetch(`/api/hf/gguf?model=${encodeURIComponent(id)}&base=${encodeURIComponent(base)}`);
            const data = await resp.json();
            if (!data || data.success !== true) throw new Error((data && data.error) ? data.error : '解析失败');
            const result = data.data || {};
            state.ggufFiles = Array.isArray(result.ggufFiles) ? result.ggufFiles : [];
            const grouped = groupFiles(state.ggufFiles);
            state.groups = grouped.groups;
            state.mmprojGroups = grouped.mmprojGroups;
            renderGgufList();
            if (result.treeError) showToast('提示', String(result.treeError), 'info');
        } catch (e) {
            state.ggufFiles = [];
            state.groups = [];
            state.mmprojGroups = [];
            if (listEl) listEl.innerHTML = `<div class="empty-state">解析失败</div>`;
            showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
        }
    }

    async function createDownloadsForGroup(groupIndex) {
        const group = state.groups && state.groups[groupIndex] ? state.groups[groupIndex] : null;
        if (!group) return;
        const repo = parseRepo(state.selectedRepo);
        if (!repo) {
            showToast('错误', 'RepoId 无效', 'error');
            return;
        }
        const urls = Array.isArray(group.urls) ? group.urls.slice() : [];
        if (!urls.length) {
            showToast('提示', '下载链接为空', 'info');
            return;
        }
        const mmprojUrls = bestMmprojUrls(state.mmprojGroups);
        const merged = new Set(urls);
        mmprojUrls.forEach((u) => merged.add(u));

        const payload = {
            author: repo.author,
            modelId: repo.modelId,
            downloadUrl: Array.from(merged),
            path: group.displayPath || group.key || ''
        };
        if (group.displayPath) payload.name = group.displayPath.split('/').pop();

        try {
            const resp = await fetch('/api/downloads/model/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!resp.ok) throw new Error(`请求失败(${resp.status})`);
            const data = await resp.json();
            if (!data || data.success !== true) throw new Error((data && data.error) ? data.error : '创建下载任务失败');
            showToast('成功', mmprojUrls.length ? '已创建下载任务（包含 mmproj）' : '已创建下载任务', 'success');
        } catch (e) {
            showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        const searchBtn = byId('mobileHfSearchBtn');
        const input = byId('mobileHfQueryInput');
        const moreBtn = byId('mobileHfMoreBtn');
        const hits = byId('mobileHfHits');
        const gguf = byId('mobileHfGgufList');
        const copyAllBtn = byId('mobileHfCopyAllBtn');

        if (searchBtn) searchBtn.addEventListener('click', function () { search(true); });
        if (moreBtn) moreBtn.addEventListener('click', function () { search(false); });
        if (input) {
            input.addEventListener('keydown', function (e) {
                if (e && e.key === 'Enter') {
                    e.preventDefault();
                    search(true);
                }
            });
        }

        if (hits) {
            hits.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('[data-hf-act]') : null;
                if (!btn) return;
                const act = btn.getAttribute('data-hf-act');
                if (act === 'gguf') {
                    openRepo(btn.getAttribute('data-hf-repo'));
                } else if (act === 'open') {
                    const url = btn.getAttribute('data-hf-url');
                    if (url) window.open(url, '_blank');
                }
            });
        }

        if (gguf) {
            gguf.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('[data-hf-gguf-act]') : null;
                if (!btn) return;
                const act = btn.getAttribute('data-hf-gguf-act');
                const idx = Number(btn.getAttribute('data-hf-gguf-idx'));
                if (!Number.isFinite(idx)) return;
                if (act === 'download') {
                    createDownloadsForGroup(idx);
                } else if (act === 'copy') {
                    const group = state.groups && state.groups[idx] ? state.groups[idx] : null;
                    const text = group && Array.isArray(group.urls) ? group.urls.join('\n') : '';
                    copyText(text).then((ok) => {
                        if (ok) showToast('已复制', '链接已复制到剪贴板', 'success');
                        else showToast('复制失败', '无法写入剪贴板', 'error');
                    });
                }
            });
        }

        if (copyAllBtn) {
            copyAllBtn.addEventListener('click', function () {
                const urls = (state.groups || []).flatMap((g) => Array.isArray(g.urls) ? g.urls : []);
                const text = urls.join('\n');
                copyText(text).then((ok) => {
                    if (ok) showToast('已复制', `已复制 ${urls.length} 条链接`, 'success');
                    else showToast('复制失败', '无法写入剪贴板', 'error');
                });
            });
        }
    });

    window.MobileHfBrowser = { search: () => search(true), openRepo };
})();
