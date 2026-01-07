function openModelBenchmarkDialog(modelId, modelName) {
    const modalId = 'modelBenchmarkModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal';
        modal.innerHTML = `
            <div class="modal-content" style="max-width: 520px;">
                <div class="modal-header">
                    <h3 class="modal-title"><i class="fas fa-tachometer-alt"></i> 模型性能测试</h3>
                    <button class="modal-close" onclick="closeModal('${modalId}')">&times;</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label class="form-label">模型</label>
                        <div class="form-control" id="benchmarkModelName" style="background-color:#f9fafb;"></div>
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="benchmarkLlamaBinPathSelect">Llama.cpp 版本</label>
                        <select class="form-control" id="benchmarkLlamaBinPathSelect"></select>
                    </div>
                    <div class="form-group">
                        <label class="form-label">重复次数 (-r)</label>
                        <input type="number" class="form-control" id="benchmarkInputRepetitions" min="1" value="5">
                    </div>
                    <div class="form-group">
                        <label class="form-label">提示长度 (-p)</label>
                        <input type="text" class="form-control" id="benchmarkInputNPrompt" value="2048" placeholder="例如: 512 或 512,1024">
                    </div>
                    <div class="form-group">
                        <label class="form-label">生成长度 (-n)</label>
                        <input type="text" class="form-control" id="benchmarkInputNGen" value="32" placeholder="例如: 128 或 128,256">
                    </div>
                    <div class="form-group">
                        <label class="form-label">批量 (-b)</label>
                        <input type="text" class="form-control" id="benchmarkInputBatchSize" value="2048" placeholder="例如: 512 或 512,1024">
                    </div>
                    <div class="form-group">
                        <label class="form-label">子批 (-ub)</label>
                        <input type="text" class="form-control" id="benchmarkInputUBatchSize" value="2048" placeholder="例如: 512 或 512,1024">
                    </div>
                    <div class="form-group">
                        <label class="form-label">Prompt/生成组合 (-pg)</label>
                        <input type="text" class="form-control" id="benchmarkInputPg" placeholder="例如: 512,128 或 256,64">
                    </div>
                    <div class="form-group">
                        <label class="form-label">Flash Attention (-fa)</label>
                        <input type="text" class="form-control" id="benchmarkInputFa" value="1" placeholder="例如: 0,1">
                    </div>
                    <div class="form-group">
                        <label class="form-label">内存映射 (-mmp)</label>
                        <input type="text" class="form-control" id="benchmarkInputMmp" value="0" placeholder="例如: 0,1">
                    </div>
                    <div class="form-group">
                        <label class="form-label">线程 (-t)</label>
                        <input type="text" class="form-control" id="benchmarkInputThreads" placeholder="例如: 4 或 4,8">
                    </div>
                    <div class="form-group">
                        <label class="form-label" for="benchmarkInputExtraParams">额外参数</label>
                        <textarea class="form-control" id="benchmarkInputExtraParams" rows="2" placeholder="例如: --grp-attn-n 8 --grp-attn-w 512"></textarea>
                        <small class="form-text">输入额外的命令行参数，用空格分隔</small>
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-secondary" onclick="closeModal('${modalId}')">取消</button>
                    <button class="btn btn-primary" id="benchmarkRunBtn" onclick="submitModelBenchmark()">开始测试</button>
                </div>
            </div>
        `;
        document.body.appendChild(modal);
    }
    window.__benchmarkModelId = modelId;
    window.__benchmarkModelName = modelName;
    const nameEl = document.getElementById('benchmarkModelName');
    if (nameEl) {
        nameEl.textContent = modelName || modelId;
    }
    const repInput = document.getElementById('benchmarkInputRepetitions');
    const nPromptInput = document.getElementById('benchmarkInputNPrompt');
    const nGenInput = document.getElementById('benchmarkInputNGen');
    const threadsInput = document.getElementById('benchmarkInputThreads');
    const batchInput = document.getElementById('benchmarkInputBatchSize');
    const ubatchInput = document.getElementById('benchmarkInputUBatchSize');
    const pgInput = document.getElementById('benchmarkInputPg');
    const faInput = document.getElementById('benchmarkInputFa');
    const mmpInput = document.getElementById('benchmarkInputMmp');
    const extraInput = document.getElementById('benchmarkInputExtraParams');
    if (repInput) repInput.value = repInput.value || 3;
    if (nPromptInput) nPromptInput.value = nPromptInput.value || 512;
    if (nGenInput) nGenInput.value = nGenInput.value || 128;
    if (threadsInput) threadsInput.value = '';
    if (pgInput) pgInput.value = '';
    if (extraInput) extraInput.value = '';
    const binSelect = document.getElementById('benchmarkLlamaBinPathSelect');
    if (binSelect) {
        binSelect.innerHTML = '<option value="">加载中...</option>';
        fetch('/api/llamacpp/list')
            .then(r => r.json())
            .then(listData => {
                const paths = (listData && listData.success && listData.data) ? (listData.data.paths || []) : [];
                if (!paths.length) {
                    binSelect.innerHTML = '<option value="">未配置路径</option>';
                } else {
                    binSelect.innerHTML = paths.map(p => `<option value="${p}">${p}</option>`).join('');
                    binSelect.value = paths[0];
                }
            })
            .catch(() => {
                binSelect.innerHTML = '<option value="">加载失败</option>';
            })
            .finally(() => {
                modal.classList.add('show');
            });
    } else {
        modal.classList.add('show');
    }
}

function submitModelBenchmark() {
    const modelId = window.__benchmarkModelId;
    const modelName = window.__benchmarkModelName;
    if (!modelId) {
        showToast('错误', '未选择模型', 'error');
        return;
    }
    const repInput = document.getElementById('benchmarkInputRepetitions');
    const nPromptInput = document.getElementById('benchmarkInputNPrompt');
    const nGenInput = document.getElementById('benchmarkInputNGen');
    const threadsInput = document.getElementById('benchmarkInputThreads');
    const batchInput = document.getElementById('benchmarkInputBatchSize');
    const ubatchInput = document.getElementById('benchmarkInputUBatchSize');
    const pgInput = document.getElementById('benchmarkInputPg');
    const faInput = document.getElementById('benchmarkInputFa');
    const mmpInput = document.getElementById('benchmarkInputMmp');
    const extraInput = document.getElementById('benchmarkInputExtraParams');
    const btn = document.getElementById('benchmarkRunBtn');
    const binSelect = document.getElementById('benchmarkLlamaBinPathSelect');

    let repetitions = repInput ? parseInt(repInput.value, 10) : 3;
    const p = nPromptInput ? nPromptInput.value.trim() : '';
    const n = nGenInput ? nGenInput.value.trim() : '';
    const t = threadsInput ? threadsInput.value.trim() : '';
    const batchSize = batchInput ? batchInput.value.trim() : '';
    const ubatchSize = ubatchInput ? ubatchInput.value.trim() : '';
    const pg = pgInput ? pgInput.value.trim() : '';
    const fa = faInput ? faInput.value.trim() : '';
    const mmp = mmpInput ? mmpInput.value.trim() : '';
    const extraParams = extraInput ? extraInput.value.trim() : '';
    const llamaBinPath = binSelect ? (binSelect.value || '').trim() : '';

    if (isNaN(repetitions) || repetitions <= 0) repetitions = 3;
    if (!llamaBinPath) {
        showToast('错误', '请先选择 Llama.cpp 版本路径', 'error');
        return;
    }

    const payload = { modelId: modelId };
    if (repetitions > 0) payload.repetitions = repetitions;
    if (p) payload.p = p;
    if (n) payload.n = n;
    if (t) payload.t = t;
    if (batchSize) payload.batchSize = batchSize;
    if (ubatchSize) payload.ubatchSize = ubatchSize;
    if (pg) payload.pg = pg;
    if (fa) payload.fa = fa;
    if (mmp) payload.mmp = mmp;
    if (extraParams) payload.extraParams = extraParams;
    payload.llamaBinPath = llamaBinPath;

    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '测试中...';
    }
    showToast('提示', '已开始模型测试', 'info');

    fetch('/api/models/benchmark', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
        .then(r => r.json())
        .then(res => {
            if (!res || !res.success) {
                const message = res && res.error ? res.error : '模型测试失败';
                showToast('错误', message, 'error');
            } else {
                const data = res.data || {};
                closeModal('modelBenchmarkModal');
                showModelBenchmarkResultModal(modelId, modelName, data);
            }
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        })
        .then(() => {
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '开始测试';
            }
        });
}

function openModelBenchmarkList(modelId, modelName) {
    const modalId = 'modelBenchmarkCompareModal';
    let modal = document.getElementById(modalId);
    if (!modal) {
        modal = document.createElement('div');
        modal.id = modalId;
        modal.className = 'modal';
        modal.innerHTML = `
            <div class="modal-content" style="min-width: 70vw; max-width: 95vw;">
                <div class="modal-header">
                    <h3 class="modal-title"><i class="fas fa-file-alt"></i> 模型测试结果对比</h3>
                    <button class="modal-close" onclick="closeModal('${modalId}')">&times;</button>
                </div>
                <div class="modal-body">
                    <div style="display:flex; gap:16px; height:60vh;">
                        <div style="width:32%; border:1px solid #e5e7eb; border-radius:0.75rem; overflow:hidden; background:#f9fafb;">
                            <div style="padding:8px 10px; border-bottom:1px solid #e5e7eb; font-size:13px; color:#374151;">测试结果文件</div>
                            <div id="${modalId}List" style="max-height:calc(60vh - 36px); overflow:auto; font-size:13px; color:#374151;">加载中...</div>
                        </div>
                        <div style="flex:1; display:flex; flex-direction:column; min-width:0;">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                                <div id="${modalId}ModelInfo" style="font-size:14px; color:#374151;"></div>
                                <div>
                                    <button class="btn btn-secondary" style="padding:4px 10px; font-size:12px;" onclick="clearBenchmarkResultContent()">清空内容</button>
                                </div>
                            </div>
                            <pre id="${modalId}Content" style="flex:1; max-height:calc(60vh - 36px); overflow:auto; font-size:13px; background:#111827; color:#e5e7eb; padding:10px; border-radius:0.75rem;"></pre>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="btn btn-secondary" onclick="closeModal('${modalId}')">关闭</button>
                </div>
            </div>`;
        document.body.appendChild(modal);
    }
    window.__benchmarkModelId = modelId;
    window.__benchmarkModelName = modelName;
    const listEl = document.getElementById(modalId + 'List');
    const modelInfoEl = document.getElementById(modalId + 'ModelInfo');
    if (modelInfoEl) {
        const name = modelName || modelId;
        modelInfoEl.textContent = name ? '当前模型: ' + name : '';
    }
    if (listEl) listEl.innerHTML = '加载中...';
    modal.classList.add('show');
    fetch('/api/models/benchmark/list?modelId=' + encodeURIComponent(modelId))
        .then(r => r.json())
        .then(d => {
            if (!d.success) {
                showToast('错误', d.error || '获取测试结果列表失败', 'error');
                return;
            }
            const files = (d.data && d.data.files) ? d.data.files : [];
            if (!files.length) {
                listEl.innerHTML = '<div style="color:#666; padding:8px 10px;">未找到测试结果文件</div>';
                return;
            }
            let html = '<div style="border-top:1px solid #e5e7eb;">';
            files.forEach(item => {
                const fn = typeof item === 'string' ? item : (item && item.name) ? item.name : '';
                const size = (item && typeof item === 'object' && item.size != null) ? item.size : null;
                const modified = (item && typeof item === 'object' && item.modified) ? item.modified : '';
                const sizeText = size != null ? (typeof formatFileSize === 'function' ? formatFileSize(size) : (size + ' B')) : '';
                html += `
                    <div class="list-row" style="display:flex; justify-content:space-between; align-items:center; padding:8px 10px; border-bottom:1px solid #e5e7eb; background:#f9fafb;">
                        <div style="display:flex; flex-direction:column; gap:4px; max-width:65%;">
                            <span style="word-break:break-all;"><i class="fas fa-file-alt" style="margin-right:6px;"></i>${fn}</span>
                            <span style="color:#6b7280; font-size:12px;">修改时间: ${modified || '-'}</span>
                            <span style="color:#6b7280; font-size:12px;">大小: ${sizeText || '-'}</span>
                        </div>
                        <div style="display:flex; flex-direction:column; gap:4px; align-items:flex-end;">
                            <button class="btn btn-primary" style="padding:2px 10px; font-size:12px;" onclick="loadBenchmarkResult(this.dataset.fn)" data-fn="${fn}">追加</button>
                            <button class="btn btn-secondary" style="padding:2px 10px; font-size:12px;" onclick="deleteBenchmarkResult(this.dataset.fn, this)" data-fn="${fn}">删除</button>
                        </div>
                    </div>`;
            });
            html += '</div>';
            listEl.innerHTML = html;
        }).catch(() => {
            showToast('错误', '网络错误，获取测试结果列表失败', 'error');
        });
}

function deleteBenchmarkResult(fileName, btn) {
    if (!fileName) {
        showToast('错误', '无效的文件名', 'error');
        return;
    }
    if (!confirm('确定要删除该测试结果文件吗？')) {
        return;
    }
    btn.disabled = true;
    fetch('/api/models/benchmark/delete?fileName=' + encodeURIComponent(fileName), {
        method: 'POST'
    }).then(r => r.json()).then(d => {
        if (!d.success) {
            showToast('错误', d.error || '删除测试结果失败', 'error');
            btn.disabled = false;
            return;
        }
        const row = btn.closest('.list-row');
        if (row && row.parentElement) {
            row.parentElement.removeChild(row);
        }
        showToast('成功', '测试结果已删除', 'success');
    }).catch(() => {
        showToast('错误', '网络错误，删除测试结果失败', 'error');
        btn.disabled = false;
    });
}

function clearBenchmarkResultContent() {
    const modalId = 'modelBenchmarkCompareModal';
    const contentEl = document.getElementById(modalId + 'Content');
    if (contentEl) {
        contentEl.textContent = '';
    }
}

function appendBenchmarkResultBlock(modelId, modelName, data) {
    const modalId = 'modelBenchmarkCompareModal';
    const contentEl = document.getElementById(modalId + 'Content');
    if (!contentEl) {
        return;
    }
    const name = modelName || modelId || '';
    const d = data || {};
    let text = '';
    const existing = contentEl.textContent || '';
    if (existing.trim().length > 0) {
        text += '\n\n';
    }
    const fileName = d.fileName || '';
    text += '==============================\n';
    if (fileName) {
        text += '文件: ' + fileName + '\n';
    }
    if (name) {
        text += '模型: ' + name + '\n';
    }
    if (d.modelId || modelId) {
        text += '模型ID: ' + (d.modelId || modelId || '') + '\n';
    }
    if (d.commandStr) {
        text += '\n命令:\n' + d.commandStr + '\n';
    } else if (d.command && d.command.length) {
        text += '\n命令:\n' + d.command.join(' ') + '\n';
    }
    if (d.exitCode != null) {
        text += '\n退出码: ' + d.exitCode + '\n';
    }
    if (d.savedPath) {
        text += '\n保存文件: ' + d.savedPath + '\n';
    }
    if (d.rawOutput) {
        text += '\n原始输出:\n' + d.rawOutput + '\n';
    }
    contentEl.textContent += text;
}

function loadBenchmarkResult(fileName) {
    const modelId = window.__benchmarkModelId;
    const modelName = window.__benchmarkModelName;
    if (!fileName) {
        showToast('错误', '无效的文件名', 'error');
        return;
    }
    fetch('/api/models/benchmark/get?fileName=' + encodeURIComponent(fileName))
        .then(r => r.json())
        .then(d => {
            if (!d.success) {
                showToast('错误', d.error || '读取测试结果失败', 'error');
                return;
            }
            const data = d.data || {};
            appendBenchmarkResultBlock(modelId, modelName, data);
        }).catch(() => {
            showToast('错误', '网络错误，读取测试结果失败', 'error');
        });
}

function showModelBenchmarkResultModal(modelId, modelName, data) {
    openModelBenchmarkList(modelId, modelName);
    appendBenchmarkResultBlock(modelId, modelName, data);
}

