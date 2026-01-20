(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    function escapeHtml(v) {
        const s = v == null ? '' : String(v);
        return s.replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function safeText(v) {
        return v == null ? '' : String(v);
    }

    let rows = [];
    let editingOriginalPath = '';

    function setListHtml(html) {
        const el = byId('mobileModelPathList');
        if (el) el.innerHTML = html;
    }

    function setCount(n) {
        const el = byId('mobileModelPathCount');
        if (el) el.textContent = String(n == null ? 0 : n);
    }

    function renderEmpty(icon, title, text) {
        return [
            '<div class="empty-state">',
            `<div class="empty-state-icon"><i class="fas ${icon}"></i></div>`,
            `<div class="empty-state-title">${escapeHtml(title)}</div>`,
            `<div class="empty-state-text">${escapeHtml(text)}</div>`,
            '</div>'
        ].join('');
    }

    function renderList() {
        if (!Array.isArray(rows) || rows.length === 0) {
            setListHtml(renderEmpty('fa-folder-open', '暂无路径', '还没有配置模型根目录'));
            return;
        }

        const cards = rows.map((it) => {
            const path = safeText(it && it.path).trim();
            const name = safeText(it && it.name).trim();
            const desc = safeText(it && it.description).trim();
            const title = name || (path ? path.split(/[\\/]/).filter(Boolean).pop() : '') || '模型目录';
            const tag = encodeURIComponent(path);

            const extra = desc
                ? `<div style="margin-top: 0.35rem; font-size: 0.875rem; color: var(--text-secondary); line-height: 1.35;">${escapeHtml(desc)}</div>`
                : '';

            return [
                `<div class="model-item" data-mp-item="${tag}">`,
                '<div style="display:flex; align-items:flex-start; gap: 0.75rem; width:100%;">',
                '<div style="width: 40px; height: 40px; border-radius: 0.75rem; display:flex; align-items:center; justify-content:center; background: rgba(16, 185, 129, 0.12); flex: 0 0 auto;">',
                '<i class="fas fa-folder-open" style="color: rgb(16, 185, 129);"></i>',
                '</div>',
                '<div style="flex:1; min-width:0;">',
                `<div style="font-weight: 750; overflow:hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(title)}">${escapeHtml(title)}</div>`,
                `<div style="margin-top: 0.15rem; font-size: 0.85rem; color: var(--text-secondary); word-break: break-all;">${escapeHtml(path)}</div>`,
                extra,
                '</div>',
                '</div>',
                '<div style="display:flex; gap: 0.5rem; margin-top: 0.75rem; width:100%; justify-content:flex-end; flex-wrap: wrap;">',
                '<button class="btn btn-secondary btn-sm" data-mp-act="edit"><i class="fas fa-edit"></i> 编辑</button>',
                '<button class="btn btn-secondary btn-sm" data-mp-act="delete" style="border-color: rgba(239,68,68,0.35); color: rgb(239,68,68);"><i class="fas fa-trash"></i> 删除</button>',
                '</div>',
                '</div>'
            ].join('');
        });

        setListHtml(cards.join(''));
    }

    async function refresh() {
        setListHtml('<div class="loading-spinner"><div class="spinner"></div></div>');
        try {
            const resp = await fetch('/api/model/path/list');
            const data = await resp.json();
            if (!(data && data.success)) {
                toast('错误', (data && data.error) ? data.error : '加载失败', 'error');
                setListHtml(renderEmpty('fa-exclamation-triangle', '加载失败', '无法获取模型路径列表'));
                rows = [];
                setCount(0);
                return;
            }
            const list = data && data.data && Array.isArray(data.data.items) ? data.data.items : [];
            rows = list;
            const count = (data && data.data && data.data.count != null) ? data.data.count : list.length;
            setCount(count);
            renderList();
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
            setListHtml(renderEmpty('fa-wifi', '网络错误', '无法连接到服务器'));
            rows = [];
            setCount(0);
        }
    }

    function openEditor(mode, item) {
        const modal = byId('mobileModelPathEditModal');
        if (!modal) return;

        const titleEl = byId('mobileModelPathEditTitle');
        const nameEl = byId('mobileModelPathNameInput');
        const pathEl = byId('mobileModelPathPathInput');
        const descEl = byId('mobileModelPathDescInput');

        const p = safeText(item && item.path).trim();
        const n = safeText(item && item.name).trim();
        const d = safeText(item && item.description).trim();

        editingOriginalPath = mode === 'edit' ? p : '';
        if (nameEl) nameEl.value = mode === 'edit' ? n : '';
        if (pathEl) pathEl.value = mode === 'edit' ? p : '';
        if (descEl) descEl.value = mode === 'edit' ? d : '';

        if (titleEl) titleEl.textContent = mode === 'edit' ? '编辑模型目录' : '添加模型目录';
        modal.classList.add('show');
    }

    async function saveEditor() {
        const nameEl = byId('mobileModelPathNameInput');
        const pathEl = byId('mobileModelPathPathInput');
        const descEl = byId('mobileModelPathDescInput');
        const saveBtn = byId('mobileModelPathSaveBtn');

        const path = safeText(pathEl && pathEl.value).trim();
        const name = safeText(nameEl && nameEl.value).trim();
        const description = safeText(descEl && descEl.value).trim();

        if (!path) {
            toast('错误', '请输入目录路径', 'error');
            return;
        }

        const payload = { path };
        if (name) payload.name = name;
        if (description) payload.description = description;

        if (saveBtn) saveBtn.disabled = true;
        try {
            const url = editingOriginalPath ? '/api/model/path/update' : '/api/model/path/add';
            const body = editingOriginalPath ? { originalPath: editingOriginalPath, ...payload } : payload;
            const resp = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await resp.json();

            if (!(data && data.success)) {
                toast('错误', (data && data.error) ? data.error : '保存失败', 'error');
                return;
            }

            toast('成功', editingOriginalPath ? '已更新' : '已添加', 'success');
            editingOriginalPath = '';
            if (typeof window.closeModal === 'function') window.closeModal('mobileModelPathEditModal');
            await refresh();
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        } finally {
            if (saveBtn) saveBtn.disabled = false;
        }
    }

    async function removeOne(path) {
        const p = safeText(path).trim();
        if (!p) return;
        if (!confirm('确定要删除这个模型目录吗？')) return;
        try {
            const resp = await fetch('/api/model/path/remove', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path: p })
            });
            const data = await resp.json();
            if (data && data.success) {
                toast('成功', '已删除', 'success');
                await refresh();
            } else {
                toast('错误', (data && data.error) ? data.error : '删除失败', 'error');
            }
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    function findRowByPath(path) {
        const p = safeText(path).trim();
        if (!p) return null;
        const list = Array.isArray(rows) ? rows : [];
        for (let i = 0; i < list.length; i++) {
            const it = list[i];
            if (safeText(it && it.path).trim() === p) return it;
        }
        return null;
    }

    function bind() {
        const list = byId('mobileModelPathList');
        if (list) {
            list.addEventListener('click', function (e) {
                const btn = e && e.target ? e.target.closest('button[data-mp-act]') : null;
                if (!btn) return;
                const card = btn.closest('[data-mp-item]');
                const tag = card ? card.getAttribute('data-mp-item') : '';
                const path = tag ? decodeURIComponent(tag) : '';
                const act = btn.getAttribute('data-mp-act');
                if (act === 'edit') openEditor('edit', findRowByPath(path) || { path });
                else if (act === 'delete') removeOne(path);
            });
        }

        const refreshBtn = byId('mobileModelPathRefreshBtn');
        if (refreshBtn) refreshBtn.addEventListener('click', refresh);

        const addBtn = byId('mobileModelPathAddBtn');
        if (addBtn) addBtn.addEventListener('click', function () { openEditor('add'); });

        const backBtn = byId('mobileModelPathBackBtn');
        if (backBtn) backBtn.addEventListener('click', function () {
            if (window.MobilePage && typeof window.MobilePage.show === 'function') window.MobilePage.show('settings');
        });

        const saveBtn = byId('mobileModelPathSaveBtn');
        if (saveBtn) saveBtn.addEventListener('click', saveEditor);
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.MobileModelPathSetting = { refresh };
})();

