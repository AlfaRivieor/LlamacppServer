function setModelActionMode(mode) {
    const resolved = mode === 'config' ? 'config' : 'load';
    window.__modelActionMode = resolved;
    const titleText = document.getElementById('modelActionModalTitleText');
    const icon = document.getElementById('modelActionModalIcon');
    const submitBtn = document.getElementById('modelActionSubmitBtn');
    if (resolved === 'config') {
        if (titleText) titleText.textContent = '更新启动参数';
        if (icon) icon.className = 'fas fa-cog';
        if (submitBtn) submitBtn.textContent = '保存';
    } else {
        if (titleText) titleText.textContent = '加载模型';
        if (icon) icon.className = 'fas fa-upload';
        if (submitBtn) submitBtn.textContent = '加载模型';
    }
}

function loadModel(modelId, modelName, mode = 'load') {
    setModelActionMode(mode);
    document.getElementById('modelId').value = modelId;
    document.getElementById('modelName').value = modelName || modelId;
    const hint = document.getElementById('ctxSizeVramHint');
    if (hint) hint.textContent = '';
    window.__loadModelSelectedDevices = ['All'];
    window.__loadModelSelectionFromConfig = true;
    const deviceChecklistEl = document.getElementById('deviceChecklist');
    if (deviceChecklistEl) deviceChecklistEl.innerHTML = '<div class="settings-empty">加载中...</div>';
    window.__availableDevices = [];
    window.__availableDeviceCount = 0;
    renderMainGpuSelect([], window.__loadModelSelectedDevices || []);

    const currentModel = (currentModelsData || []).find(m => m && m.id === modelId);
    const isVisionModel = !!(currentModel && (currentModel.isMultimodal || currentModel.mmproj));
    const enableVisionGroup = document.getElementById('enableVisionGroup');
    if (enableVisionGroup) enableVisionGroup.style.display = isVisionModel ? '' : 'none';

    fetch(`/api/models/config/get?modelId=${encodeURIComponent(modelId)}`)
        .then(r => r.json()).then(data => {
            if (!(data && data.success) && mode === 'config') {
                showToast('错误', (data && data.error) ? data.error : '获取配置失败', 'error');
            }
            const config = (data && data.success && data.data) ? data.data.config : {};
            if (config) {
                if (config.ctxSize) document.getElementById('ctxSize').value = config.ctxSize;
                if (config.batchSize) document.getElementById('batchSize').value = config.batchSize;
                if (config.ubatchSize) document.getElementById('ubatchSize').value = config.ubatchSize;
                if (config.temperature) document.getElementById('temperature').value = config.temperature;
                if (config.topP) document.getElementById('topP').value = config.topP;
                if (config.topK) document.getElementById('topK').value = config.topK;
                if (config.minP) document.getElementById('minP').value = config.minP;
                if (config.presencePenalty || config.presencePenalty === 0) document.getElementById('presencePenalty').value = config.presencePenalty;
                if (config.repeatPenalty || config.repeatPenalty === 0) document.getElementById('repeatPenalty').value = config.repeatPenalty;
                if (config.parallel) document.getElementById('parallel').value = config.parallel;

                document.getElementById('noMmap').checked = !!config.noMmap;
                document.getElementById('mlock').checked = !!config.mlock;
                document.getElementById('embedding').checked = !!config.embedding;
                document.getElementById('reranking').checked = !!config.reranking;
                document.getElementById('flashAttention').checked = config.flashAttention !== undefined ? config.flashAttention : true;
                document.getElementById('extraParams').value = config.extraParams || '';
                const enableVisionEl = document.getElementById('enableVision');
                if (enableVisionEl) enableVisionEl.checked = config.enableVision !== undefined ? !!config.enableVision : true;
                window.__loadModelSelectedDevices = normalizeDeviceSelection(config.device);
                window.__loadModelMainGpu = normalizeMainGpu(config.mg);
                window.__loadModelSelectionFromConfig = true;
            }

            fetch('/api/llamacpp/list').then(r => r.json()).then(listData => {
                const select = document.getElementById('llamaBinPathSelect');
                const paths = (listData && listData.success && listData.data) ? (listData.data.paths || []) : [];
                select.innerHTML = paths.map(p => `<option value="${p}">${p}</option>`).join('');

                if (config.llamaBinPath) {
                    select.value = config.llamaBinPath;
                } else {
                    fetch('/api/setting').then(rs => rs.json()).then(s => {
                        const currentBin = (s && s.success && s.data) ? s.data.llamaBin : null;
                        if (currentBin && select) select.value = currentBin;
                    }).catch(() => {});
                }

                select.onchange = function() { loadDeviceList(); };
                loadDeviceList();
            }).finally(() => {
                const modal = document.getElementById('loadModelModal');
                modal.classList.add('show');
            });
        });
}

function submitModelAction() {
    const mode = window.__modelActionMode === 'config' ? 'config' : 'load';
    const form = document.getElementById('loadModelForm');
    const formData = new FormData(form);
    const data = {};
    formData.forEach((value, key) => {
        if (['ctxSize', 'batchSize', 'ubatchSize', 'topK', 'parallel'].includes(key)) data[key] = parseInt(value);
        else if (['temperature', 'topP', 'minP', 'presencePenalty'].includes(key)) data[key] = parseFloat(value);
        else if (['repeatPenalty'].includes(key)) data[key] = parseFloat(value);
        else if (['noMmap', 'mlock', 'embedding', 'reranking'].includes(key)) data[key] = value === 'on';
        else data[key] = value;
    });
    data.noMmap = document.getElementById('noMmap').checked;
    data.mlock = document.getElementById('mlock').checked;
    data.embedding = document.getElementById('embedding').checked;
    data.reranking = document.getElementById('reranking').checked;
    data.flashAttention = document.getElementById('flashAttention').checked;
    data.llamaBinPath = document.getElementById('llamaBinPathSelect').value;
    data.extraParams = document.getElementById('extraParams').value;
    const enableVisionEl = document.getElementById('enableVision');
    if (enableVisionEl) data.enableVision = enableVisionEl.checked;

    const selectedDevices = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedDevices.length === availableCount;
    if (isAllSelected) data.device = ['All'];
    else data.device = selectedDevices;
    data.mg = getSelectedMainGpu();

    if (mode === 'config') {
        delete data.modelName;
    }

    const submitBtn = document.getElementById('modelActionSubmitBtn');
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = mode === 'config'
            ? '<i class="fas fa-spinner fa-spin"></i> 保存中...'
            : '<i class="fas fa-spinner fa-spin"></i> 处理中...';
    }

    const url = mode === 'config' ? '/api/models/config/set' : '/api/models/load';
    fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    }).then(r => r.json()).then(res => {
        if (res.success) {
            if (mode === 'config') {
                showToast('成功', '启动参数已保存', 'success');
                closeModal('loadModelModal');
            } else {
                if (res.data && res.data.async) {
                    showToast('模型加载中', '正在后台加载...', 'info');
                    showModelLoadingState(data.modelId);
                    window.pendingModelLoad = { modelId: data.modelId };
                    loadModels();
                } else {
                    showToast('成功', '模型加载成功', 'success');
                    closeModal('loadModelModal');
                    loadModels();
                }
            }
        } else {
            showToast('错误', res.error || (mode === 'config' ? '保存失败' : '加载失败'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = mode === 'config' ? '保存' : '加载模型';
            }
        }
    }).catch(() => {
        showToast('错误', '网络请求失败', 'error');
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = mode === 'config' ? '保存' : '加载模型';
        }
    });
}

function submitLoadModel() { submitModelAction(); }

function estimateVramAction() {
    const modelId = document.getElementById('modelId').value;
    if (!modelId) {
        showToast('错误', '请先选择模型', 'error');
        return;
    }
    const ctxSize = parseInt(document.getElementById('ctxSize').value || '2048', 10);
    const batchSize = parseInt(document.getElementById('batchSize').value || '512', 10);
    const ubatch = parseInt(document.getElementById('ubatchSize').value || '0', 10);
    const flashAttention = document.getElementById('flashAttention').checked;
    const enableVisionEl = document.getElementById('enableVision');
    const payload = {
        modelId,
        ctxSize,
        batchSize: isNaN(batchSize) || batchSize <= 0 ? null : batchSize,
        ubatchSize: isNaN(ubatch) || ubatch <= 0 ? null : ubatch,
        flashAttention,
        enableVision: enableVisionEl ? enableVisionEl.checked : true
    };
    fetch('/api/models/vram/estimate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (res && res.success) {
            const bytes = res.data && res.data.bytes ? res.data.bytes : null;
            if (bytes) {
                const toMiB = (val) => (val / (1024 * 1024)).toFixed(1);
                const total = toMiB(bytes.totalVramRequired || 0);
                const weights = toMiB(bytes.modelWeights || 0);
                const kv = toMiB(bytes.kvCache || 0);
                const overhead = toMiB(bytes.runtimeOverhead || 0);

                const text = `预计显存：总计 ${total} MiB（权重 ${weights}，KV ${kv}，其他 ${overhead}）`;
                const hint = document.getElementById('ctxSizeVramHint');
                if (hint) hint.textContent = text;
            } else {
                showToast('错误', '返回数据格式不正确', 'error');
            }
        } else {
            showToast('错误', (res && res.error) ? res.error : '估算失败', 'error');
        }
    }).catch(() => {
        showToast('错误', '网络请求失败', 'error');
    });
}

function estimateVram() { estimateVramAction(); }

function viewModelConfig(modelId) {
    const currentModel = (currentModelsData || []).find(m => m && m.id === modelId);
    loadModel(modelId, currentModel ? currentModel.name : modelId, 'config');
}

function normalizeDeviceSelection(device) {
    if (Array.isArray(device)) {
        const list = device
            .map(v => (v === null || v === undefined) ? '' : String(v))
            .map(v => v.trim())
            .filter(v => v.length > 0);
        const lower = list.map(v => v.toLowerCase());
        if (lower.includes('all') || lower.includes('-1')) return ['All'];
        return lower;
    }
    if (device === null || device === undefined || device === '') return [];
    const v = String(device).trim();
    if (!v) return [];
    const lower = v.toLowerCase();
    if (lower === 'all' || lower === '-1') return ['All'];
    return [lower];
}

function normalizeMainGpu(v) {
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : -1;
}

function getSelectedMainGpu() {
    const el = document.getElementById('mainGpuSelect');
    if (!el) return -1;
    const n = parseInt(el.value, 10);
    return Number.isFinite(n) ? n : -1;
}

function renderMainGpuSelect(devices, selectedKeys) {
    const select = document.getElementById('mainGpuSelect');
    if (!select) return;
    const desired = normalizeMainGpu(window.__loadModelMainGpu);
    let effectiveDevices = Array.isArray(devices) ? devices.slice() : [];
    const keys = Array.isArray(selectedKeys) ? selectedKeys : null;
    if (keys && keys.length > 0 && !keys.includes('All') && !keys.includes('-1')) {
        const filtered = [];
        const normalized = keys.map(v => String(v).trim().toLowerCase()).filter(v => v.length > 0 && v !== 'all' && v !== '-1');
        for (let i = 0; i < effectiveDevices.length; i++) {
            if (deviceMatchesSelection(effectiveDevices[i], normalized)) filtered.push(effectiveDevices[i]);
        }
        if (filtered.length > 0) effectiveDevices = filtered;
    }
    const safe = (Array.isArray(effectiveDevices) && desired >= 0 && desired < effectiveDevices.length) ? desired : -1;
    const options = ['<option value="-1">默认</option>'];
    if (Array.isArray(effectiveDevices)) {
        for (let i = 0; i < effectiveDevices.length; i++) {
            options.push(`<option value="${i}">${escapeHtml(effectiveDevices[i])}</option>`);
        }
    }
    select.innerHTML = options.join('');
    select.value = String(safe);
}

function deviceKeyFromLabel(label) {
    if (label === null || label === undefined) return '';
    const s = String(label).trim();
    const match = s.match(/^([^\s:\-]+)/);
    return match ? match[1].toLowerCase() : s.toLowerCase();
}

function deviceMatchesSelection(deviceLabel, selectedEntries) {
    const label = (deviceLabel === null || deviceLabel === undefined) ? '' : String(deviceLabel).trim();
    const labelLower = label.toLowerCase();
    const key = deviceKeyFromLabel(label);
    const entries = Array.isArray(selectedEntries) ? selectedEntries : [];
    for (let i = 0; i < entries.length; i++) {
        const raw = entries[i];
        if (raw === null || raw === undefined) continue;
        const s = String(raw).trim().toLowerCase();
        if (!s || s === 'all' || s === '-1') continue;
        if (s === key) return true;
        if (labelLower.startsWith(s)) return true;
        if (key && s.startsWith(key)) return true;
    }
    return false;
}

function getSelectedDevicesFromChecklist() {
    const list = document.getElementById('deviceChecklist');
    if (!list) return [];
    const values = Array.from(list.querySelectorAll('input[type="checkbox"][data-device-key]:checked'))
        .map(el => el.getAttribute('data-device-key'))
        .map(v => {
            if (v === null || v === undefined) return '';
            const trimmed = String(v).trim();
            return trimmed.split(':')[0];
        })
        .filter(v => v.length > 0 && v !== 'All' && v !== '-1');
    values.sort((a, b) => {
        const ai = parseInt(a, 10);
        const bi = parseInt(b, 10);
        if (Number.isFinite(ai) && Number.isFinite(bi)) return ai - bi;
        return a.localeCompare(b);
    });
    return values;
}

function updateSelectedDevicesCacheFromChecklist() {
    const list = document.getElementById('deviceChecklist');
    if (!list) return;
    const hasInputs = !!list.querySelector('input[type="checkbox"][data-device-key]');
    if (!hasInputs) return;
    const selectedKeys = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedKeys.length === availableCount;
    window.__loadModelSelectedDevices = isAllSelected ? ['All'] : selectedKeys;
}

function syncMainGpuSelectWithChecklist() {
    const mainGpuEl = document.getElementById('mainGpuSelect');
    if (mainGpuEl) window.__loadModelMainGpu = getSelectedMainGpu();
    updateSelectedDevicesCacheFromChecklist();
    renderMainGpuSelect(window.__availableDevices || [], window.__loadModelSelectedDevices || []);
    window.__loadModelSelectionFromConfig = false;
}

function loadDeviceList() {
    const list = document.getElementById('deviceChecklist');
    const allowReadFromChecklist = !window.__loadModelSelectionFromConfig;
    if (allowReadFromChecklist && list && list.querySelector('input[type="checkbox"][data-device-key]')) {
        updateSelectedDevicesCacheFromChecklist();
    }
    const mainGpuEl = document.getElementById('mainGpuSelect');
    if (mainGpuEl && mainGpuEl.options && mainGpuEl.options.length > 1) {
        window.__loadModelMainGpu = getSelectedMainGpu();
    }
    const llamaBinPath = document.getElementById('llamaBinPathSelect').value;

    if (!llamaBinPath) {
        if (list) list.innerHTML = '<div class="settings-empty">请先选择 Llama.cpp 版本</div>';
        renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        return;
    }

    fetch(`/api/model/device/list?llamaBinPath=${encodeURIComponent(llamaBinPath)}`)
        .then(response => response.json())
        .then(data => {
            if (!list) return;
            if (!(data && data.success && data.data && Array.isArray(data.data.devices))) {
                list.innerHTML = '<div class="settings-empty">获取设备列表失败</div>';
                renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
                return;
            }
            const devices = data.data.devices;
            window.__availableDevices = devices;
            window.__availableDeviceCount = devices.length;
            const selected = window.__loadModelSelectedDevices || [];
            const defaultAll = selected.includes('All') || selected.includes('-1') || selected.length === 0;
            const items = devices.map((device) => {
                const key = deviceKeyFromLabel(device);
                const checked = (defaultAll || deviceMatchesSelection(device, selected)) ? 'checked' : '';
                return `<label style="display:flex; align-items:flex-start; gap:8px; padding:6px 6px; border-radius:8px; cursor:pointer;">
                    <input type="checkbox" ${checked} data-device-key="${escapeHtml(key)}" style="margin-top: 2px;">
                    <span style="font-size: 0.9rem; color: var(--text-primary);">${escapeHtml(device)}</span>
                </label>`;
            });
            list.innerHTML = items.length ? items.join('') : '<div class="settings-empty">未发现可用设备</div>';

            if (!window.__deviceChecklistChangeBound) {
                window.__deviceChecklistChangeBound = true;
                list.addEventListener('change', (e) => {
                    const t = e && e.target ? e.target : null;
                    if (!t) return;
                    if (t.matches && t.matches('input[type="checkbox"][data-device-key]')) {
                        syncMainGpuSelectWithChecklist();
                    }
                });
            }

            syncMainGpuSelectWithChecklist();
        })
        .catch(error => {
            if (list) list.innerHTML = `<div class="settings-empty">获取设备列表失败：${escapeHtml(error && error.message ? error.message : '')}</div>`;
            renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        });
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function(m) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]); });
}

