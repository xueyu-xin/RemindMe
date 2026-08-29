/**
 * 记忆库页面模块 - Memory JavaScript
 * 只读档案室形态：记忆由 AI 经对话产生，页面提供分类/关键词筛选、删除（单条/批量）、
 * 备注编辑（唯一可手动编辑字段）、同分类记忆聚合、导出（JSON/TXT），
 * 并通过 WebSocket 与 Agent 聊天侧实时同步
 * 依赖：main.js 的 showToast / handleWebSocketMessageV2 分发；后端 /api/memory 系列接口
 */
window.MemoryPage = (function () {

    // ===== 分类定义：与后端 MemoryCategory 枚举一一对应，desc 为悬浮说明 =====
    var CATEGORIES = [
        { code: 'PERSONAL_INFO',    name: '个人信息', icon: '🧑', desc: '用户自身的基本属性与状态' },
        { code: 'ACTIVITY',         name: '活动事务', icon: '📅', desc: '近期或远期发生的事务与安排' },
        { code: 'EVENT',            name: '重要日期', icon: '🎂', desc: '需要记住的关键日期与纪念日' },
        { code: 'PLAN',             name: '计划目标', icon: '🎯', desc: '需要长期跟踪的目标与计划' },
        { code: 'EXAM_SCHEDULE',    name: '考试安排', icon: '📝', desc: '时间确定的重要考试' },
        { code: 'HABIT',            name: '个人习惯', icon: '📌', desc: '有固定规律的行为习惯' },
        { code: 'HEALTH_CONDITION', name: '健康状况', icon: '💊', desc: '与健康相关的身体情况' },
        { code: 'DIET_PREFERENCE',  name: '饮食偏好', icon: '🍚', desc: '口味偏好与饮食禁忌' },
        { code: 'INTEREST',         name: '兴趣爱好', icon: '🎮', desc: '喜欢做的事，无固定时间规律' },
        { code: 'LANGUAGE_STYLE',   name: '语言风格', icon: '💬', desc: '对助手回复风格的要求' },
        { code: 'OTHER',            name: '其他',     icon: '📦', desc: '暂时无法归入上述分类的记忆' }
    ];

    // 分类编码转中文名；未知编码或为空时归"未分类"
    function categoryName(code) {
        for (var i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].code === code) return CATEGORIES[i].name;
        }
        return '未分类';
    }

    // 分类编码转图标
    function categoryIcon(code) {
        for (var i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].code === code) return CATEGORIES[i].icon;
        }
        return '❓';
    }

    // ===== 状态 =====
    var state = {
        loaded: false,          // 是否已加载过数据（WS 回调只在加载过后才静默刷新）
        all: [],                // 全量记忆列表（后端 KnowledgeResult 数组）
        activeCategory: 'ALL',  // 当前选中的分类筛选编码，ALL 表示全部
        keyword: '',            // 搜索关键词（匹配分类/标题/内容）
        checked: {},            // 勾选集合 {memoryId: true}
        detailId: null,         // 右栏当前展示的记忆 id
        panelMode: 'empty',     // 右栏模式：empty | view | remark（备注编辑）
        remarkEditingId: null,  // 备注编辑模式下的记忆 id
        refreshing: false,      // 刷新中标记，防止并发请求
        page: 1,                // 当前页码（1 起）
        pageSize: 10            // 每页条数
    };

    // WebSocket 远程变更的防抖定时器
    var remoteChangeTimer = null;

    // 分类悬浮说明气泡元素（全局唯一，挂在 body 上）
    var catTipEl = null;

    // 删除确认弹窗的待执行回调
    var pendingConfirmAction = null;

    // ===== 工具函数 =====

    // 获取当前登录用户 id，与 main.js 保持一致
    function getUserId() {
        return parseInt(localStorage.getItem('userId')) || 1;
    }

    // 轻提示：main.js 的 showToast 是闭包私有函数，这里操作同一个 #toast 元素实现同款效果
    var toastTimer = null;
    function toast(message, isSuccess) {
        var el = document.getElementById('toast');
        if (!el) { console.log('[记忆库]', message); return; }
        el.textContent = message;
        el.style.background = '';
        el.style.color = '';
        el.classList.remove('toast-success', 'toast-error');
        el.classList.add(isSuccess ? 'toast-success' : 'toast-error');
        el.style.display = 'block';
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            el.style.display = 'none';
            el.classList.remove('toast-success', 'toast-error');
        }, 3000);
    }

    // HTML 转义，防止记忆内容中的特殊字符破坏页面结构
    function esc(text) {
        if (text === null || text === undefined) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // 标签用于内联 onclick 的转义：先转义 JS 单引号再做 HTML 转义，防止文本含引号时破坏内联代码
    function escAttr(text) {
        return esc(String(text).replace(/'/g, "\\'"));
    }

    // ===== 统一样式的删除确认弹窗（复用项目 .modal-overlay 组件）=====

    // 弹出确认：显示文案并暂存确认后的回调；弹窗缺失时降级原生 confirm
    function requestConfirm(text, action) {
        var modal = document.getElementById('memoryConfirmModal');
        var textEl = document.getElementById('memoryConfirmText');
        if (!modal || !textEl) {
            if (window.confirm(text)) action();
            return;
        }
        textEl.textContent = text;
        pendingConfirmAction = action;
        modal.classList.add('show');
    }

    // 取消：仅关闭弹窗
    function hideConfirm() {
        var modal = document.getElementById('memoryConfirmModal');
        if (modal) modal.classList.remove('show');
        pendingConfirmAction = null;
    }

    // 确认：关闭弹窗并执行暂存的回调
    function confirmAction() {
        var action = pendingConfirmAction;
        hideConfirm();
        if (action) action();
    }

    // ===== 统一接口请求 =====

    // 统一接口请求：code !== 200 时抛出后端 message
    async function api(url, options) {
        var res = await fetch(url, options);
        var json = await res.json();
        if (json.code !== 200) {
            throw new Error(json.message || '请求失败');
        }
        return json;
    }

    // POST JSON 快捷封装
    function apiPost(url, body) {
        return api(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    }

    // ===== 数据加载 =====

    // 进入记忆库页：拉取全量记忆（switchTab 每次切入都会调用，保证数据新鲜）
    async function enter() {
        try {
            await refresh();
        } catch (e) {
            toast(e.message || '加载记忆失败', false);
            renderList();
        }
    }

    // 拉取全量记忆列表并重渲染（保持当前筛选/搜索/右栏状态）
    async function refresh() {
        if (state.refreshing) return;
        state.refreshing = true;
        try {
            var res = await api('/api/memory/list?userId=' + getUserId());
            state.all = res.data || [];
            state.loaded = true;
            // 右栏详情对应的记忆被删后回到空态
            if (state.detailId && !findById(state.detailId) && state.panelMode !== 'empty') {
                state.detailId = null;
                state.panelMode = 'empty';
                state.remarkEditingId = null;
            }
            // 刷新后勾选中可能存在已不存在的记忆，清理并同步按钮/全选框
            var validIds = {};
            state.all.forEach(function (m) { validIds[m.id] = true; });
            Object.keys(state.checked).forEach(function (id) {
                if (!validIds[id]) delete state.checked[id];
            });
            updateBatchBtn();
            renderCategoryBar();
            renderList();
            renderDetail();
        } catch (e) {
            toast(e.message || '获取记忆列表失败', false);
        } finally {
            state.refreshing = false;
        }
    }

    // 按 id 查找本地缓存的记忆
    function findById(id) {
        for (var i = 0; i < state.all.length; i++) {
            if (state.all[i].id === id) return state.all[i];
        }
        return null;
    }

    // 计算当前可见列表：按分类筛选 + 关键词匹配分类/标题/内容
    function getVisibleList() {
        var list = state.all.slice();
        if (state.activeCategory !== 'ALL') {
            list = list.filter(function (m) {
                // 后端无分类的历史数据视为 OTHER，保证与左栏筛选一致
                var cat = m.category || 'OTHER';
                return cat === state.activeCategory;
            });
        }
        if (state.keyword) {
            var kw = state.keyword.toLowerCase();
            list = list.filter(function (m) {
                if ((categoryName(m.category) + ' ' + (m.category || '')).toLowerCase().indexOf(kw) !== -1) return true;
                if ((m.title || '').toLowerCase().indexOf(kw) !== -1) return true;
                if ((m.content || '').toLowerCase().indexOf(kw) !== -1) return true;
                return false;
            });
        }
        return list;
    }

    // ===== 渲染：分类栏 =====

    function renderCategoryBar() {
        var bar = document.getElementById('memoryCategoryBar');
        if (!bar) return;

        // 统计各分类记忆数量（无分类的历史数据归入 OTHER）
        var counts = {};
        state.all.forEach(function (m) {
            var cat = m.category || 'OTHER';
            counts[cat] = (counts[cat] || 0) + 1;
        });

        var html = '<div class="memory-cat-item' + (state.activeCategory === 'ALL' ? ' active' : '') + '"'
            + ' data-tip="查看所有分类下的记忆"'
            + ' onclick="MemoryPage.selectCategory(\'ALL\')">'
            + '<span class="memory-cat-name">🍬 全部</span>'
            + '<span class="memory-cat-count">' + state.all.length + '</span>'
            + '</div>';

        CATEGORIES.forEach(function (cat) {
            html += '<div class="memory-cat-item' + (state.activeCategory === cat.code ? ' active' : '') + '"'
                + ' data-tip="' + escAttr(cat.desc) + '"'
                + ' onclick="MemoryPage.selectCategory(\'' + cat.code + '\')">'
                + '<span class="memory-cat-name">' + cat.icon + ' ' + esc(cat.name) + '</span>'
                + '<span class="memory-cat-count">' + (counts[cat.code] || 0) + '</span>'
                + '</div>';
        });

        bar.innerHTML = html;
    }

    // 切换分类筛选并回到第一页
    function selectCategory(code) {
        state.activeCategory = code;
        state.page = 1;
        renderCategoryBar();
        renderList();
    }

    // ===== 渲染：记忆列表 =====

    function renderList() {
        var listEl = document.getElementById('memoryList');
        if (!listEl) return;

        var all = getVisibleList();

        // 分页：钳制当前页后切片
        var totalPages = Math.max(1, Math.ceil(all.length / state.pageSize));
        if (state.page > totalPages) state.page = totalPages;
        if (state.page < 1) state.page = 1;
        var list = all.slice((state.page - 1) * state.pageSize, state.page * state.pageSize);

        if (all.length === 0) {
            var emptyText = state.keyword
                ? '没有匹配「' + esc(state.keyword) + '」的记忆'
                : (state.activeCategory !== 'ALL'
                    ? '「' + esc(categoryName(state.activeCategory)) + '」分类下暂时没有记忆'
                    : (state.all.length === 0
                        ? '还没有记忆哦，点底部栏「对话」和助手聊聊天吧～'
                        : '这个分类下暂时没有记忆'));
            listEl.innerHTML = '<div class="memory-empty">'
                + '<div class="memory-empty-icon">🧸</div>'
                + '<div class="memory-empty-text">' + emptyText + '</div></div>';
            return;
        }

        // 列表顶部全选行：作用于当前页，右侧提示已选总数
        var selectedCount = Object.keys(state.checked).length;
        var allChecked = list.every(function (m) { return !!state.checked[m.id]; });
        var html = '<div class="memory-select-all-row">'
            + '<input type="checkbox" id="memorySelectAllBox"' + (allChecked ? ' checked' : '')
            + ' onchange="MemoryPage.toggleSelectAll(this.checked)">'
            + '<label for="memorySelectAllBox">选本页（共 ' + list.length + ' 条）</label>'
            + (selectedCount > 0 ? '<span class="memory-select-all-info">已选 ' + selectedCount + ' 条</span>' : '')
            + '</div>';

        list.forEach(function (m) {
            var checked = !!state.checked[m.id];
            var title = m.title || (m.content || '').replace(/\s+/g, ' ').slice(0, 20);
            var summary = (m.content || '').replace(/\s+/g, ' ');
            if (summary.length > 80) summary = summary.slice(0, 80) + '…';

            // 分类 chip：无分类时显示"未分类"占位
            var tagsHtml = '<span class="memory-tag">' + categoryIcon(m.category) + ' ' + esc(categoryName(m.category)) + '</span>';

            html += '<div class="memory-item' + (state.detailId === m.id ? ' selected' : '') + '" data-id="' + esc(m.id) + '">'
                + '<input type="checkbox" class="memory-check" ' + (checked ? 'checked' : '')
                + ' title="勾选后可批量删除"'
                + ' onclick="event.stopPropagation();MemoryPage.toggleCheck(\'' + esc(m.id) + '\')">'
                + '<div class="memory-item-main" onclick="MemoryPage.showDetail(\'' + esc(m.id) + '\')">'
                + '<div class="memory-item-title">' + esc(title) + '</div>'
                + '<div class="memory-item-content">' + esc(summary) + '</div>'
                + '<div class="memory-item-meta">'
                + tagsHtml
                + '<span class="memory-time">' + esc(m.createTime || '') + '</span>'
                + '</div></div>'
                + '<div class="memory-item-ops">'
                + '<button class="memory-op-btn memory-op-del" title="删除"'
                + ' onclick="event.stopPropagation();MemoryPage.deleteMemory(\'' + esc(m.id) + '\')">🗑️</button>'
                + '</div></div>';
        });

        // 底部分页条（只有超过一页时显示）
        if (totalPages > 1) {
            html += buildPaginationHtml(totalPages);
        }

        listEl.innerHTML = html;
    }

    // ===== 渲染：分页条 =====

    // 生成分页条：< 上一页 + 省略式页码 + 下一页 > + 跳页输入
    function buildPaginationHtml(totalPages) {
        var nums = getPageNumbers(totalPages);
        var html = '<div class="memory-pagination">'
            + '<button class="memory-page-btn"' + (state.page === 1 ? ' disabled' : '')
            + ' onclick="MemoryPage.goToPage(' + (state.page - 1) + ')">&lt;</button>';

        nums.forEach(function (n) {
            if (n === '...') {
                html += '<span class="memory-page-dots">…</span>';
            } else if (n === state.page) {
                html += '<button class="memory-page-btn active" disabled>' + n + '</button>';
            } else {
                html += '<button class="memory-page-btn" onclick="MemoryPage.goToPage(' + n + ')">' + n + '</button>';
            }
        });

        html += '<button class="memory-page-btn"' + (state.page === totalPages ? ' disabled' : '')
            + ' onclick="MemoryPage.goToPage(' + (state.page + 1) + ')">&gt;</button>'
            + '<span class="memory-page-jump">跳至第'
            + '<input type="number" class="memory-page-jump-input" id="memoryPageJumpInput" min="1" max="' + totalPages + '"'
            + ' onkeydown="if(event.key===\'Enter\')MemoryPage.jumpToPage()">'
            + '页</span>'
            + '</div>';
        return html;
    }

    // 计算页码序列：首尾必显，当前页±2 窗口，间隔用省略号
    function getPageNumbers(total) {
        var result = [1];
        var windowStart = Math.max(2, state.page - 2);
        var windowEnd = Math.min(total - 1, state.page + 2);
        if (windowStart > 2) result.push('...');
        for (var p = windowStart; p <= windowEnd; p++) result.push(p);
        if (windowEnd < total - 1) result.push('...');
        if (total > 1) result.push(total);
        return result;
    }

    // 跳转到指定页（自动钳制范围并重渲染）
    function goToPage(p) {
        var all = getVisibleList();
        var totalPages = Math.max(1, Math.ceil(all.length / state.pageSize));
        var target = Math.min(Math.max(parseInt(p, 10) || 1, 1), totalPages);
        if (target === state.page) return;
        state.page = target;
        renderList();
    }

    // 读取跳页输入框并跳转
    function jumpToPage() {
        var input = document.getElementById('memoryPageJumpInput');
        if (input) goToPage(input.value);
    }

    // ===== 渲染：右栏详情面板 =====

    // 查看记忆详情
    function showDetail(id) {
        state.detailId = id;
        state.panelMode = 'view';
        state.remarkEditingId = null;
        renderList();
        renderDetail();
    }

    // 关闭详情：右栏回到空态，并清除列表选中高亮
    function closeDetail() {
        state.detailId = null;
        state.panelMode = 'empty';
        state.remarkEditingId = null;
        renderList();
        renderDetail();
    }

    // 右栏主渲染入口：按 panelMode 分发
    function renderDetail() {
        var panel = document.getElementById('memoryDetailPanel');
        if (!panel) return;

        if (state.panelMode === 'remark') {
            var editing = findById(state.remarkEditingId);
            if (editing) { panel.innerHTML = buildRemarkHtml(editing); return; }
            state.panelMode = 'view';
        }
        if (state.panelMode === 'view') {
            var view = findById(state.detailId);
            if (view) { panel.innerHTML = buildViewHtml(view); return; }
        }
        panel.innerHTML = buildEmptyDetailHtml();
    }

    // 找出与当前记忆同分类的相关记忆：按创建时间倒序，最多取 5 条
    function findRelatedMemories(memory) {
        var myCat = memory.category || 'OTHER';
        return state.all
            .filter(function (m) { return m.id !== memory.id && (m.category || 'OTHER') === myCat; })
            .slice(0, 5);
    }

    // 详情视图：分类 chip + 内容 + 备注（可编辑）+ 同分类记忆聚合
    function buildViewHtml(m) {
        var tagsHtml = '<span class="memory-tag memory-tag-big">' + categoryIcon(m.category) + ' '
            + esc(categoryName(m.category)) + '</span>';

        var remark = m.remark || '';
        var remarkHtml = '<div class="memory-detail-remark">'
            + '<div class="memory-detail-remark-head">'
            + '<span class="memory-detail-section-title">📝 备注</span>'
            + '<button class="memory-btn memory-btn-plain memory-btn-small" onclick="MemoryPage.editRemark()">✏️ ' + (remark ? '编辑' : '添加') + '</button>'
            + '</div>'
            + (remark
                ? '<div class="memory-detail-remark-text">' + esc(remark).replace(/\n/g, '<br>') + '</div>'
                : '<div class="memory-detail-remark-empty">还没有备注，点「添加」写点给自己的话吧</div>')
            + '</div>';

        // 同分类的其他记忆，点击可跳转
        var related = findRelatedMemories(m);
        var relatedHtml = '';
        if (related.length > 0) {
            var items = related.map(function (r) {
                return '<div class="memory-related-item" onclick="MemoryPage.showDetail(\'' + esc(r.id) + '\')">'
                    + '<span class="memory-related-title">' + esc(r.title || (r.content || '').slice(0, 20)) + '</span>'
                    + '<span class="memory-related-tags">' + esc(categoryName(r.category)) + '</span>'
                    + '</div>';
            }).join('');
            relatedHtml = '<div class="memory-detail-related">'
                + '<div class="memory-detail-section-title">🔗 相关记忆</div>'
                + items
                + '</div>';
        }

        return '<div class="memory-detail-header">'
            + '<div class="memory-detail-tags">' + tagsHtml + '</div>'
            + '<div class="memory-detail-ops">'
            + '<button class="memory-btn memory-btn-danger memory-btn-small" onclick="MemoryPage.deleteMemory(\'' + esc(m.id) + '\')">🗑️ 删除</button>'
            + '<button class="memory-detail-close" title="关闭详情" onclick="MemoryPage.closeDetail()">×</button>'
            + '</div></div>'
            + (m.title ? '<div class="memory-detail-title">' + esc(m.title) + '</div>' : '')
            + '<div class="memory-detail-content">' + esc(m.content || '').replace(/\n/g, '<br>') + '</div>'
            + remarkHtml
            + relatedHtml
            + '<div class="memory-detail-time">' + formatMemoryTime(m) + '</div>';
    }

    // 详情页时间展示：创建时间与更新时间相同（从未更新过）时只显示创建时间，否则两个都显示
    function formatMemoryTime(m) {
        if (!m.createTime) return '🕒 记录于 --';
        if (m.updateTime && m.updateTime !== m.createTime) {
            return '🕒 创建于 ' + esc(m.createTime) + '<br>✏️ 更新于 ' + esc(m.updateTime);
        }
        return '🕒 创建于 ' + esc(m.createTime);
    }

    // 备注编辑视图：textarea + 保存/取消
    function buildRemarkHtml(m) {
        return '<div class="memory-detail-header">'
            + '<span class="memory-detail-back" onclick="MemoryPage.cancelRemark()">← 返回详情</span>'
            + '</div>'
            + '<div class="memory-detail-title">📝 编辑备注</div>'
            + '<div class="memory-remark-tip">助手召回这条记忆时会一并阅读备注，记忆内容如需修改请直接告诉助手</div>'
            + '<textarea class="memory-input memory-textarea" id="memoryRemarkInput" rows="6"'
            + ' placeholder="请为这条记忆编写备注吧">' + esc(m.remark || '') + '</textarea>'
            + '<div class="memory-form-buttons">'
            + '<button class="memory-btn memory-btn-plain" onclick="MemoryPage.cancelRemark()">取消</button>'
            + '<button class="memory-btn memory-btn-primary" id="memoryRemarkSaveBtn" onclick="MemoryPage.saveRemark()">💾 保存</button>'
            + '</div>';
    }

    // 右栏空态
    function buildEmptyDetailHtml() {
        return '<div class="memory-detail-empty">'
            + '<div class="memory-empty-icon">🍓</div>'
            + '<div class="memory-empty-text">点击中间的记忆查看详情</div>'
            + '</div>';
    }

    // ===== 备注编辑 =====

    // 进入备注编辑模式
    function editRemark() {
        if (!state.detailId) return;
        state.panelMode = 'remark';
        state.remarkEditingId = state.detailId;
        renderDetail();
    }

    // 取消备注编辑：回到详情视图
    function cancelRemark() {
        state.panelMode = 'view';
        state.remarkEditingId = null;
        renderDetail();
    }

    // 保存备注到后端并刷新详情
    async function saveRemark() {
        var input = document.getElementById('memoryRemarkInput');
        var btn = document.getElementById('memoryRemarkSaveBtn');
        if (!input || !state.remarkEditingId) return;

        var remark = input.value.trim();
        if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }

        try {
            await apiPost('/api/memory/update-remark', {
                memoryId: state.remarkEditingId,
                userId: getUserId(),
                remark: remark
            });
            toast('备注已保存', true);
            // 本地先更新，再由 WS 事件触发全量刷新兜底
            var editedId = state.remarkEditingId;
            var local = findById(editedId);
            if (local) local.remark = remark;
            state.panelMode = 'view';
            state.remarkEditingId = null;
            renderDetail();
        } catch (e) {
            toast(e.message || '备注保存失败，请稍后再试', false);
            if (btn) { btn.disabled = false; btn.textContent = '💾 保存'; }
        }
    }

    // ===== 删除操作 =====

    // 删除单条记忆（弹窗二次确认）
    function deleteMemory(id) {
        requestConfirm('确定要删除这条记忆吗？删除后不可恢复哦！', async function () {
            try {
                await apiPost('/api/memory/delete', { memoryId: id, userId: getUserId() });
                delete state.checked[id];
                updateBatchBtn();
                toast('记忆已删除', true);
                await refresh();
            } catch (e) {
                toast(e.message || '删除失败，请稍后再试', false);
            }
        });
    }

    // 批量删除勾选的记忆（弹窗二次确认，逐条删除）
    function batchDelete() {
        var ids = Object.keys(state.checked);
        if (ids.length === 0) return;

        requestConfirm('确定要删除选中的 ' + ids.length + ' 条记忆吗？删除后不可恢复哦！', async function () {
            var btn = document.getElementById('memoryBatchDeleteBtn');
            if (btn) { btn.disabled = true; btn.textContent = '删除中…'; }

            try {
                var success = 0;
                var fail = 0;
                // 后端批量接口逐条删除，这里循环调用保持与单条删除一致的事件推送
                for (var i = 0; i < ids.length; i++) {
                    try {
                        await apiPost('/api/memory/delete', { memoryId: ids[i], userId: getUserId() });
                        success++;
                    } catch (e) {
                        fail++;
                    }
                }
                state.checked = {};
                updateBatchBtn();
                if (fail === 0) {
                    toast('已删除 ' + success + ' 条记忆', true);
                } else {
                    toast('删除完成：成功 ' + success + ' 条，失败 ' + fail + ' 条', false);
                }
                await refresh();
            } finally {
                if (btn) { btn.disabled = Object.keys(state.checked).length === 0; }
            }
        });
    }

    // ===== 勾选管理 =====

    // 全选/取消全选当前页（跨页勾选保留，跨页逐页勾选可累加）
    function toggleSelectAll(checked) {
        var visible = getVisibleList();
        var start = (state.page - 1) * state.pageSize;
        var pageItems = visible.slice(start, start + state.pageSize);
        pageItems.forEach(function (m) {
            if (checked) {
                state.checked[m.id] = true;
            } else {
                delete state.checked[m.id];
            }
        });
        updateBatchBtn();
        renderList();
    }

    // 工具栏全选框：勾选 = 选中当前筛选下的全部记忆（跨页）；取消 = 清空全部勾选
    function toggleSelectAllGlobal(checked) {
        var visible = getVisibleList();
        if (visible.length === 0) {
            // 无可选记忆时勾选框回弹为未勾选
            syncGlobalSelectAllBox();
            return;
        }
        if (checked) {
            visible.forEach(function (m) { state.checked[m.id] = true; });
        } else {
            state.checked = {};
        }
        updateBatchBtn();
        renderList();
        toast(checked ? '已选中当前筛选下的全部 ' + visible.length + ' 条记忆' : '已清空全部勾选', true);
    }

    // 同步工具栏全选框状态：当前筛选结果非空且全部已勾选时打勾
    function syncGlobalSelectAllBox() {
        var box = document.getElementById('memorySelectAllGlobalBox');
        if (!box) return;
        var visible = getVisibleList();
        box.checked = visible.length > 0 && visible.every(function (m) { return !!state.checked[m.id]; });
    }

    // 勾选/取消某条记忆
    function toggleCheck(id) {
        if (state.checked[id]) {
            delete state.checked[id];
        } else {
            state.checked[id] = true;
        }
        updateBatchBtn();
        syncSelectAllBox();
        // 只更新列表里对应勾选框状态，避免整列表重绘闪烁
        var boxes = document.querySelectorAll('.memory-check');
        boxes.forEach(function (box) {
            var itemId = box.getAttribute('onclick') || '';
            if (itemId.indexOf("'" + id + "'") !== -1) {
                box.checked = !!state.checked[id];
            }
        });
    }

    // 同步列表顶部全选框状态：当前页全部勾选时才打勾，并更新"已选 X 条"提示
    function syncSelectAllBox() {
        var box = document.getElementById('memorySelectAllBox');
        if (!box) return;
        var visible = getVisibleList();
        var start = (state.page - 1) * state.pageSize;
        var pageItems = visible.slice(start, start + state.pageSize);
        var allChecked = pageItems.length > 0 && pageItems.every(function (m) { return !!state.checked[m.id]; });
        box.checked = allChecked;

        var row = box.closest('.memory-select-all-row');
        if (!row) return;
        var info = row.querySelector('.memory-select-all-info');
        var selectedCount = Object.keys(state.checked).length;
        if (selectedCount > 0) {
            if (!info) {
                info = document.createElement('span');
                info.className = 'memory-select-all-info';
                row.appendChild(info);
            }
            info.textContent = '已选 ' + selectedCount + ' 条';
        } else if (info) {
            info.remove();
        }
    }

    // 同步批量删除按钮文案与可用状态，并联动工具栏全选框
    function updateBatchBtn() {
        var btn = document.getElementById('memoryBatchDeleteBtn');
        if (btn) {
            var count = Object.keys(state.checked).length;
            btn.textContent = '删除选中(' + count + ')';
            btn.disabled = count === 0;
        }
        syncGlobalSelectAllBox();
    }

    // ===== 搜索 =====

    // 本地关键词过滤（输入即筛）
    function onSearchInput() {
        var input = document.getElementById('memorySearchInput');
        state.keyword = input ? input.value.trim() : '';
        state.page = 1;
        renderList();
    }

    // ===== 导出 =====

    // 展开/收起导出菜单
    function toggleExportMenu() {
        var menu = document.getElementById('memoryExportMenu');
        if (!menu) return;
        menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
    }

    // 点击页面其他区域时收起导出菜单（只绑定一次）
    function bindGlobalListeners() {
        if (bindGlobalListeners._bound) return;
        bindGlobalListeners._bound = true;
        document.addEventListener('click', function (e) {
            var menu = document.getElementById('memoryExportMenu');
            if (!menu || menu.style.display === 'none') return;
            var wrap = e.target.closest ? e.target.closest('.memory-export-wrap') : null;
            if (!wrap) menu.style.display = 'none';
        });

        // 分类悬浮说明：全局 tooltip 跟随目标项定位，不受分类栏 overflow 裁剪影响
        var bar = document.getElementById('memoryCategoryBar');
        if (bar) {
            bar.addEventListener('mouseover', function (e) {
                var item = e.target.closest ? e.target.closest('.memory-cat-item') : null;
                if (item) showCatTip(item);
            });
            bar.addEventListener('mouseout', function (e) {
                var item = e.target.closest ? e.target.closest('.memory-cat-item') : null;
                if (item) hideCatTip();
            });
        }
    }

    // 显示分类说明气泡：定位在目标项右侧垂直居中
    function showCatTip(item) {
        var tip = item.getAttribute('data-tip');
        if (!tip) return;
        if (!catTipEl) {
            catTipEl = document.createElement('div');
            catTipEl.className = 'memory-cat-tip';
            document.body.appendChild(catTipEl);
        }
        catTipEl.textContent = tip;
        catTipEl.style.display = 'block';
        var rect = item.getBoundingClientRect();
        catTipEl.style.top = Math.round(rect.top + rect.height / 2) + 'px';
        catTipEl.style.left = Math.round(rect.right + 12) + 'px';
    }

    // 隐藏分类说明气泡
    function hideCatTip() {
        if (catTipEl) catTipEl.style.display = 'none';
    }

    // 导出记忆为 JSON / TXT 文件
    async function exportAs(format) {
        var menu = document.getElementById('memoryExportMenu');
        if (menu) menu.style.display = 'none';

        try {
            var json = await api('/api/memory/export?userId=' + getUserId() + '&format=' + format);
            var blob;
            var dateStr = new Date().toISOString().slice(0, 10).replace(/-/g, '');
            var filename;
            if (format === 'txt') {
                blob = new Blob([json.data || ''], { type: 'text/plain;charset=utf-8' });
                filename = '助手记忆_' + dateStr + '.txt';
            } else {
                blob = new Blob([JSON.stringify(json.data || [], null, 2)], { type: 'application/json;charset=utf-8' });
                filename = '助手记忆_' + dateStr + '.json';
            }

            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(a.href);
            toast('已导出：' + filename, true);
        } catch (e) {
            toast(e.message || '导出失败，请稍后再试', false);
        }
    }

    // ===== WebSocket 同步 =====

    // 收到 PRIVATE_MEMORY_CHANGED 推送：防抖后静默刷新列表（保持筛选状态）
    function onRemoteChange() {
        if (!state.loaded) return;
        if (remoteChangeTimer) clearTimeout(remoteChangeTimer);
        remoteChangeTimer = setTimeout(function () {
            refresh();
        }, 800);
    }

    // ===== 对外接口 =====
    return {
        enter: enter,
        showDetail: showDetail,
        closeDetail: closeDetail,
        editRemark: editRemark,
        cancelRemark: cancelRemark,
        saveRemark: saveRemark,
        deleteMemory: deleteMemory,
        batchDelete: batchDelete,
        toggleCheck: toggleCheck,
        toggleSelectAll: toggleSelectAll,
        toggleSelectAllGlobal: toggleSelectAllGlobal,
        selectCategory: selectCategory,
        onSearchInput: onSearchInput,
        goToPage: goToPage,
        jumpToPage: jumpToPage,
        toggleExportMenu: toggleExportMenu,
        exportAs: exportAs,
        onRemoteChange: onRemoteChange,
        hideConfirm: hideConfirm,
        confirmAction: confirmAction,
        bindGlobalListeners: bindGlobalListeners
    };
})();

// 页面就绪后绑定全局监听（导出菜单关闭等）
document.addEventListener('DOMContentLoaded', function () {
    if (window.MemoryPage) {
        MemoryPage.bindGlobalListeners();
    }
});
