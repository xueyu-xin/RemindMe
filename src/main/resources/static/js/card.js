/**
 * 确认卡片系统 - 对话流中的确认卡片
 * 支持多卡片并存，每个卡片独立管理自己的状态、倒计时和操作
 */
var CardSystem = (function() {
    var cards = {}; // { sessionId: { card, timerInterval, timerTimer } }
    var chatHistory = null;

    /**
     * 初始化 - 获取聊天历史容器
     */
    function init() {
        if (!chatHistory) {
            chatHistory = document.getElementById('chatHistory');
        }
    }

    /**
     * 显示确认卡片
     * @param {Object} cardData - 卡片数据
     */
    function show(cardData) {
        init();

        var sessionId = (cardData.data && cardData.data.sessionId) || ('card-' + Date.now());

        var card = {
            message: cardData.message || '请确认信息',
            data: cardData.data || {},
            actions: cardData.actions || [
                { id: 'confirm', label: '确认', style: 'primary' },
                { id: 'cancel', label: '取消', style: 'secondary' }
            ],
            expiresAt: cardData.expiresAt || null,
            element: null,
            editMode: false,
            sessionId: sessionId
        };

        // 如果已存在相同 sessionId 的卡片，先移除
        if (cards[sessionId]) {
            removeCard(sessionId);
        }

        // 创建卡片元素
        var cardElement = createCardElement(card);
        card.element = cardElement;

        // 插入到聊天历史中
        if (chatHistory) {
            var messageWrapper = document.createElement('div');
            messageWrapper.className = 'chat-message bot';
            messageWrapper.id = 'cardWrapper-' + sessionId;
            messageWrapper.appendChild(cardElement);
            chatHistory.appendChild(messageWrapper);
            chatHistory.scrollTop = chatHistory.scrollHeight;
        }

        // 存储卡片
        cards[sessionId] = {
            card: card,
            timerInterval: null,
            timerTimer: null
        };

        // 启动倒计时
        if (card.expiresAt) {
            startCountdown(card.expiresAt, sessionId);
        }

        return card;
    }

    /**
     * 创建卡片DOM元素
     */
    function createCardElement(card) {
        var sessionId = card.sessionId;
        var div = document.createElement('div');
        div.className = 'confirmation-card';
        div.id = 'card-' + sessionId;
        div.setAttribute('data-session-id', sessionId);

        var html = '';
        html += '<div class="card-title">' + escapeHtml(card.message) + '</div>';
        html += '<div class="card-info">';
        html += '<div class="info-item"><span class="info-label">标题</span><span class="info-value">' + escapeHtml(card.data.title || '') + '</span></div>';
        var timeDisplay = '';
        if (card.data.endTime) {
            var startTimeShort = (card.data.remindTime || '').slice(11, 16);
            var endTimeShort = (card.data.endTime || '').slice(11, 16);
            timeDisplay = startTimeShort + ' → ' + endTimeShort;
        } else {
            timeDisplay = card.data.remindTime || '';
        }
        html += '<div class="info-item"><span class="info-label">时间</span><span class="info-value">' + escapeHtml(timeDisplay) + '</span></div>';

        if (card.data.location) {
            html += '<div class="info-item"><span class="info-label">地点</span><span class="info-value">' + escapeHtml(card.data.location) + '</span></div>';
        }

        html += '<div class="info-item"><span class="info-label">优先级</span><span class="info-value">' + escapeHtml(card.data.priority || 'NORMAL') + '</span></div>';
        html += '</div>';

        // 操作按钮
        html += '<div class="card-actions">';
        for (var i = 0; i < card.actions.length; i++) {
            var action = card.actions[i];
            html += '<button class="action-btn ' + (action.style || '') + '" data-action-id="' + action.id + '" data-session-id="' + sessionId + '">' + escapeHtml(action.label) + '</button>';
        }
        html += '</div>';

        // 倒计时显示
        html += '<div class="card-timer" id="timer-' + sessionId + '"></div>';

        div.innerHTML = html;

        // 绑定按钮事件
        var buttons = div.querySelectorAll('.action-btn');
        for (var j = 0; j < buttons.length; j++) {
            (function(btn) {
                btn.addEventListener('click', function() {
                    var actionId = btn.getAttribute('data-action-id');
                    var btnSessionId = btn.getAttribute('data-session-id');
                    handleAction(actionId, btnSessionId);
                });
            })(buttons[j]);
        }

        return div;
    }

    /**
     * 处理卡片操作
     */
    function handleAction(actionId, sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry) return;

        var card = cardEntry.card;
        var userId = localStorage.getItem('userId');

        switch (actionId) {
            case 'confirm':
                confirm(sessionId, userId);
                break;
            case 'cancel':
                cancel(sessionId, userId);
                break;
            case 'modify':
            case 'edit':
                // 'edit' 和 'modify' 都进入编辑模式，不能走 default 分支直接发请求
                // 否则会向 /api/confirmations/{sessionId}/edit 发送无 body 的 POST，导致后端报 Required request body is missing
                toggleEditMode(sessionId);
                break;
            default:
                handleCustomAction(actionId, sessionId, userId);
                break;
        }
    }

    /**
     * 确认卡片
     */
    function confirm(sessionId, userId) {
        stopCountdown(sessionId);

        // 禁用所有操作按钮防止重复点击
        var cardEl = document.getElementById('card-' + sessionId);
        if (cardEl) {
            var btns = cardEl.querySelectorAll('.action-btn');
            btns.forEach(function(btn) { btn.disabled = true; btn.style.pointerEvents = 'none'; });
        }

        fetch('/api/confirmations/' + sessionId + '/confirm?userId=' + userId, {
            method: 'POST',
            headers: getHeaders()
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                // 清理引导语（临时消息）
                if (typeof window.removeMessagesByGroup === 'function') {
                    window.removeMessagesByGroup('card-' + sessionId);
                } else if (typeof window.removeTempMessages === 'function') {
                    window.removeTempMessages();
                }

                // 设置会话标记，防止 WebSocket card_confirmed 重复显示
                if (window.processedSessionIds) {
                    window.processedSessionIds[sessionId] = 'done';
                }

                // 使用全局 addMessage 显示确认回复（保存到后端，触发副作用）
                if (typeof window.addMessage === 'function') {
                    window.addMessage('bot', result.message || '✅ 已确认', { skipSave: false });
                } else {
                    addBotMessage(result.message || '✅ 已确认');
                }

                removeCard(sessionId);
            } else {
                // 恢复按钮
                if (cardEl) {
                    var btns = cardEl.querySelectorAll('.action-btn');
                    btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
                }
                showError(result.message || '确认失败');
            }
        }).catch(function(err) {
            // 恢复按钮
            if (cardEl) {
                var btns = cardEl.querySelectorAll('.action-btn');
                btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
            }
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 取消卡片
     */
    function cancel(sessionId, userId) {
        stopCountdown(sessionId);

        // 禁用所有操作按钮防止重复点击
        var cardEl = document.getElementById('card-' + sessionId);
        if (cardEl) {
            var btns = cardEl.querySelectorAll('.action-btn');
            btns.forEach(function(btn) { btn.disabled = true; btn.style.pointerEvents = 'none'; });
        }

        fetch('/api/confirmations/' + sessionId + '/cancel?userId=' + userId, {
            method: 'POST',
            headers: getHeaders()
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                // 清理引导语（临时消息）
                if (typeof window.removeMessagesByGroup === 'function') {
                    window.removeMessagesByGroup('card-' + sessionId);
                } else if (typeof window.removeTempMessages === 'function') {
                    window.removeTempMessages();
                }

                // 设置会话标记
                if (window.processedSessionIds) {
                    window.processedSessionIds[sessionId] = 'done';
                }

                // 使用全局 addMessage 显示取消回复
                if (typeof window.addMessage === 'function') {
                    window.addMessage('bot', result.message || '❌ 已取消', { skipSave: false });
                } else {
                    addBotMessage(result.message || '❌ 已取消');
                }

                removeCard(sessionId);
            } else {
                if (cardEl) {
                    var btns = cardEl.querySelectorAll('.action-btn');
                    btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
                }
                showError(result.message || '取消失败');
            }
        }).catch(function(err) {
            if (cardEl) {
                var btns = cardEl.querySelectorAll('.action-btn');
                btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
            }
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 切换编辑模式
     */
    function toggleEditMode(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;

        var card = cardEntry.card;
        card.editMode = !card.editMode;

        if (card.editMode) {
            showEditForm(sessionId);
        } else {
            hideEditForm(sessionId);
        }
    }

    /**
     * 显示编辑表单
     */
    function showEditForm(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;

        var card = cardEntry.card;
        var formHtml = '';
        formHtml += '<div class="card-edit-form">';
        formHtml += '<div class="form-group"><label>标题</label><input type="text" class="edit-title" value="' + escapeHtml(card.data.title || '') + '"></div>';
        formHtml += '<div class="form-group"><label>时间</label><input type="text" class="edit-remindTime" value="' + escapeHtml(card.data.remindTime || '') + '"></div>';
        formHtml += '<div class="form-group"><label>地点</label><input type="text" class="edit-location" value="' + escapeHtml(card.data.location || '') + '"></div>';
        formHtml += '<div class="form-group"><label>优先级</label>';
        formHtml += '<select class="edit-priority">';
        formHtml += '<option value="LOW"' + (card.data.priority === 'LOW' ? ' selected' : '') + '>低</option>';
        formHtml += '<option value="NORMAL"' + (card.data.priority === 'NORMAL' ? ' selected' : '') + '>普通</option>';
        formHtml += '<option value="HIGH"' + (card.data.priority === 'HIGH' ? ' selected' : '') + '>高</option>';
        formHtml += '<option value="URGENT"' + (card.data.priority === 'URGENT' ? ' selected' : '') + '>紧急</option>';
        formHtml += '</select></div>';
        formHtml += '<div class="form-actions">';
        formHtml += '<button class="action-btn primary edit-submit" data-session-id="' + sessionId + '">修改</button>';
        formHtml += '<button class="action-btn secondary edit-cancel" data-session-id="' + sessionId + '">取消修改</button>';
        formHtml += '</div>';
        formHtml += '</div>';

        var cardInfo = card.element.querySelector('.card-info');
        if (cardInfo) {
            cardInfo.insertAdjacentHTML('afterend', formHtml);
        }

        var submitBtn = card.element.querySelector('.edit-submit');
        var cancelBtn = card.element.querySelector('.edit-cancel');

        if (submitBtn) {
            submitBtn.addEventListener('click', function() {
                submitEditForm(sessionId);
            });
        }
        if (cancelBtn) {
            cancelBtn.addEventListener('click', function() {
                toggleEditMode(sessionId);
            });
        }
    }

    /**
     * 隐藏编辑表单
     */
    function hideEditForm(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;

        var form = cardEntry.card.element.querySelector('.card-edit-form');
        if (form) {
            form.remove();
        }
    }

    /**
     * 提交编辑表单
     */
    function submitEditForm(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry) return;

        var card = cardEntry.card;
        var userId = localStorage.getItem('userId');

        var editData = {
            title: card.element.querySelector('.edit-title').value,
            remindTime: card.element.querySelector('.edit-remindTime').value,
            location: card.element.querySelector('.edit-location').value,
            priority: card.element.querySelector('.edit-priority').value
        };

        fetch('/api/confirmations/' + sessionId + '/reparse?userId=' + userId, {
            method: 'POST',
            headers: getHeaders(),
            body: JSON.stringify(editData)
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                fetchCardData(sessionId, userId);
            } else {
                showError(result.message || '修改失败');
            }
        }).catch(function(err) {
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 获取最新卡片数据并刷新
     */
    function fetchCardData(sessionId, userId) {
        fetch('/api/confirmations/' + sessionId + '/data?userId=' + userId, {
            headers: getHeaders()
        })
            .then(function(res) { return res.json(); })
            .then(function(result) {
                // 后端 getSessionData 返回扁平结构（直接含 sessionId/title/remindTime 等），没有 success/data 包装
                if (result && result.sessionId) {
                    var cardEntry = cards[sessionId];
                    if (cardEntry) {
                        cardEntry.card.data = result;
                        refreshCard(sessionId);
                    }
                } else {
                    showError(result.message || '获取数据失败');
                }
            }).catch(function(err) {
                showError('请求失败: ' + err.message);
            });
    }

    /**
     * 刷新卡片显示
     */
    function refreshCard(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;

        var card = cardEntry.card;
        var parent = card.element.parentNode;

        var newElement = createCardElement(card);

        if (parent) {
            parent.replaceChild(newElement, card.element);
        }

        card.element = newElement;
        card.editMode = false;

        if (card.expiresAt) {
            startCountdown(card.expiresAt, sessionId);
        }
    }

    /**
     * 启动倒计时（针对特定卡片）
     */
    function startCountdown(expiresAt, sessionId) {
        stopCountdown(sessionId);

        var targetTime = new Date(expiresAt).getTime();
        var cardEntry = cards[sessionId];
        if (!cardEntry) return;

        cardEntry.timerInterval = setInterval(function() {
            var now = Date.now();
            var remaining = Math.max(0, Math.floor((targetTime - now) / 1000));

            updateTimerDisplay(sessionId, remaining);

            if (remaining <= 0) {
                stopCountdown(sessionId);
                autoConfirm(sessionId);
            }
        }, 1000);

        updateTimerDisplay(sessionId, Math.max(0, Math.floor((targetTime - Date.now()) / 1000)));
    }

    /**
     * 停止倒计时（针对特定卡片）
     */
    function stopCountdown(sessionId) {
        var cardEntry = cards[sessionId];
        if (!cardEntry) return;

        if (cardEntry.timerInterval) {
            clearInterval(cardEntry.timerInterval);
            cardEntry.timerInterval = null;
        }
        if (cardEntry.timerTimer) {
            clearTimeout(cardEntry.timerTimer);
            cardEntry.timerTimer = null;
        }
    }

    /**
     * 更新倒计时显示（针对特定卡片）
     */
    function updateTimerDisplay(sessionId, seconds) {
        var timerEl = document.getElementById('timer-' + sessionId);
        if (!timerEl) return;

        var minutes = Math.floor(seconds / 60);
        var secs = seconds % 60;

        var text = '⏳ ';
        if (minutes > 0) {
            text += minutes + '分';
        }
        text += secs.toString().padStart(2, '0') + '秒后自动确认';

        timerEl.textContent = text;

        if (seconds <= 10 && seconds > 0) {
            timerEl.classList.add('warning');
        } else {
            timerEl.classList.remove('warning');
        }
    }

    /**
     * 超时自动确认（针对特定卡片）
     */
    function autoConfirm(sessionId) {
        // 清理引导语
        if (typeof window.removeMessagesByGroup === 'function') {
            window.removeMessagesByGroup('card-' + sessionId);
        } else if (typeof window.removeTempMessages === 'function') {
            window.removeTempMessages();
        }

        if (window.processedSessionIds) {
            window.processedSessionIds[sessionId] = 'done';
        }

        // 使用全局 addMessage
        if (typeof window.addMessage === 'function') {
            window.addMessage('bot', '⏰ 超时未操作，已自动确认', { skipSave: false });
        } else {
            addBotMessage('⏰ 超时未操作，已自动确认');
        }
        removeCard(sessionId);

        var userId = localStorage.getItem('userId');
        fetch('/api/confirmations/' + sessionId + '/confirm?userId=' + userId, {
            method: 'POST',
            headers: getHeaders()
        }).catch(function(err) {
            console.warn('超时自动确认失败:', err.message);
        });
    }

    /**
     * 处理自定义操作
     */
    function handleCustomAction(actionId, sessionId, userId) {
        var endpoint = actionId === 'retry' ? 'reparse' : actionId;

        // 禁用按钮
        var cardEl = document.getElementById('card-' + sessionId);
        if (cardEl) {
            var btns = cardEl.querySelectorAll('.action-btn');
            btns.forEach(function(btn) { btn.disabled = true; btn.style.pointerEvents = 'none'; });
        }

        fetch('/api/confirmations/' + sessionId + '/' + endpoint + '?userId=' + userId, {
            method: 'POST',
            headers: getHeaders()
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                // 所有"终结类"操作（创建/覆盖/取消等）都清理引导语、显示回复、移除卡片
                var terminalActions = ['confirm', 'cancel', 'overwrite', 'overwrite_all',
                    'confirm_direct', 'confirm_skip', 'schedule_direct', 'schedule_skip', 'schedule_overwrite'];
                var isTerminal = terminalActions.indexOf(actionId) !== -1
                    || (result.message && !result.data);

                if (isTerminal) {
                    if (typeof window.removeMessagesByGroup === 'function') {
                        window.removeMessagesByGroup('card-' + sessionId);
                    } else if (typeof window.removeTempMessages === 'function') {
                        window.removeTempMessages();
                    }

                    if (window.processedSessionIds) {
                        window.processedSessionIds[sessionId] = 'done';
                    }

                    if (typeof window.addMessage === 'function') {
                        window.addMessage('bot', result.message || '操作成功', { skipSave: false });
                    } else {
                        addBotMessage(result.message || '操作成功');
                    }
                    removeCard(sessionId);
                } else {
                    fetchCardData(sessionId, userId);
                }
            } else {
                if (cardEl) {
                    var btns = cardEl.querySelectorAll('.action-btn');
                    btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
                }
                showError(result.message || '操作失败');
            }
        }).catch(function(err) {
            if (cardEl) {
                var btns = cardEl.querySelectorAll('.action-btn');
                btns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
            }
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 添加机器人消息
     */
    function addBotMessage(text) {
        if (!chatHistory) {
            chatHistory = document.getElementById('chatHistory');
        }
        if (!chatHistory) return;

        var messageDiv = document.createElement('div');
        messageDiv.className = 'chat-message bot';
        messageDiv.textContent = text;
        chatHistory.appendChild(messageDiv);
        chatHistory.scrollTop = chatHistory.scrollHeight;
    }

    /**
     * 显示错误
     */
    function showError(message) {
        console.error(message);
        addBotMessage('❌ ' + message);
    }

    /**
     * 移除指定卡片
     */
    function removeCard(sessionId) {
        stopCountdown(sessionId);

        var cardEntry = cards[sessionId];
        if (cardEntry && cardEntry.card.element) {
            var wrapper = cardEntry.card.element.parentNode;
            if (wrapper) {
                wrapper.remove();
            }
        }

        delete cards[sessionId];
    }

    /**
     * 清除所有卡片
     */
    function clear() {
        Object.keys(cards).forEach(function(sessionId) {
            removeCard(sessionId);
        });
    }

    /**
     * 获取请求头
     */
    function getHeaders() {
        var headers = { 'Content-Type': 'application/json' };
        var token = localStorage.getItem('token');
        if (token) {
            headers['Authorization'] = 'Bearer ' + token;
        }
        return headers;
    }

    /**
     * HTML 转义
     */
    function escapeHtml(str) {
        if (!str) return '';
        var div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    /**
     * 获取当前卡片数量
     */
    function getCardCount() {
        return Object.keys(cards).length;
    }

    // 暴露公共接口
    return {
        init: init,
        show: show,
        clear: clear,
        removeCard: removeCard,
        getCardCount: getCardCount
    };
})();

// 兼容全局访问
window.CardSystem = CardSystem;
