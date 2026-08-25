/**
 * 主脚本 - Main JavaScript
 * 集成卡片系统(CardSystem)、通知系统(NotificationSystem)、音效系统(SoundSystem)
 * 本文件必须在 sound.js、notification.js、card.js 之后加载
 */

// ===== 应用初始化 =====
function initApp() {
    if (window.SoundSystem) { SoundSystem.init(); }
    if (window.NotificationSystem) { NotificationSystem.init(); }
    if (typeof connectWebSocket === 'function') { connectWebSocket(); }
    var chatInput = document.getElementById('chatInput');
    if (chatInput) {
        chatInput.addEventListener('keypress', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                if (typeof sendMessage === 'function') { sendMessage(); }
            }
        });
    }
    if (typeof startPromptRotation === 'function') { startPromptRotation(); }
}

function startPromptRotation() {
    var promptText = document.getElementById('promptText');
    if (!promptText) return;
    if (window._promptRotationTimer) { clearInterval(window._promptRotationTimer); }
    var rotations = ['你可以这样问我：明天下午3点提醒我还书', '试试说：下周一到周五早上8点叫我起床', '或：周五下午5点提醒我开会', '也可以上传Excel批量导入课表'];
    var idx = 0;
    window._promptRotationTimer = setInterval(function() {
        idx = (idx + 1) % rotations.length;
        if (promptText) { promptText.textContent = rotations[idx]; }
    }, 5000);
}

function getAuthHeaders() {
    var headers = { 'Content-Type': 'application/json' };
    var token = localStorage.getItem('token');
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    return headers;
}

// ===== 增强版 WebSocket 消息处理 =====
function handleWebSocketMessageV2(msg) {
    if (!msg || !msg.type) {
        return false;
    }
    // Agent 状态流事件（状态流气泡优先处理，不参与普通消息）
    if (msg.type === 'AGENT_STATUS') {
        if (window.AgentStatusFlow) {
            window.AgentStatusFlow.onStatusEvent(msg);
        }
        return true;
    }
    if (msg.type === 'confirmation_card' || msg.type === 'single_confirm_card') {
        // 清理临时消息
        if (typeof removeTempMessages === 'function') removeTempMessages();

        // 显示引导语（如果有）
        var leadMsg = msg.leadMessage || msg.naturalMessage || msg.message;
        var wsSessionId = msg.data ? msg.data.sessionId : msg.sessionId;
        if (leadMsg && typeof addMessage === 'function') {
            addMessage('bot', leadMsg, { skipSave: true, isTemp: true, groupId: 'card-' + wsSessionId });
        }

        // 去重检查
        if (wsSessionId) {
            var existingWsCard = document.querySelector('.confirmation-card[data-session-id="' + wsSessionId + '"]');
            if (existingWsCard) {
                return true; // 已存在，跳过
            }
        }

        // 保存待确认会话状态
        if (wsSessionId) {
            try {
                savePendingSession(wsSessionId, leadMsg || '', msg);
            } catch (_) {}
        }

        if (window.CardSystem) {
            CardSystem.show({
                message: msg.message || '请确认信息',
                data: msg.data || {},
                actions: msg.actions || [{ id: 'confirm', label: '✅ 确认', style: 'primary' }, { id: 'cancel', label: '❌ 取消', style: 'danger' }],
                expiresAt: msg.expiresAt || null
            });
            return true;
        }
        return false;
    }

    // 多项事务确认卡片：与单条 confirmation_card 同样的交互骨架，但数据结构含 items 列表
    if (msg.type === 'multi_confirmation_card') {
        // 清理临时消息
        if (typeof removeTempMessages === 'function') removeTempMessages();

        // 显示引导语（如果有），打上 groupId 便于后续按会话清理
        var mLeadMsg = msg.leadMessage || msg.naturalMessage || msg.message;
        var mWsSessionId = msg.data ? msg.data.sessionId : msg.sessionId;
        if (mLeadMsg && typeof addMessage === 'function') {
            addMessage('bot', mLeadMsg, { skipSave: true, isTemp: true, groupId: 'card-' + mWsSessionId });
        }

        // 去重检查：同 sessionId 的卡片已存在则跳过
        if (mWsSessionId) {
            var existingMultiCard = document.querySelector('.confirmation-card[data-session-id="' + mWsSessionId + '"]');
            if (existingMultiCard) return true;
        }

        // 保存待确认会话状态
        if (mWsSessionId) {
            try {
                savePendingSession(mWsSessionId, mLeadMsg || '', msg);
            } catch (_) {}
        }

        // 调用 CardSystem 显示多项卡片（CardSystem.show 内部会根据 data.isMulti 走专用渲染路径）
        if (window.CardSystem) {
            CardSystem.show({
                message: msg.message || '📋 检测到多项事务，请确认',
                data: msg.data || {},
                actions: msg.actions || [
                    { id: 'confirm_selected', label: '✅ 确认选中', style: 'primary' },
                    { id: 'cancel_selected', label: '❌ 取消选中', style: 'danger' },
                    { id: 'overwrite_selected', label: '📝 覆盖选中', style: 'warning' }
                ],
                expiresAt: null  // 多项事务卡片无超时
            });
            return true;
        }
        return false;
    }

    if (msg.type === 'card_confirmed' || msg.type === 'card_cancelled') {
        var confirmSessionId = msg.data ? msg.data.sessionId : msg.sessionId;
        
        // 去重：如果 HTTP 响应已处理过该会话，跳过消息显示（仅清理卡片）
        if (confirmSessionId && processedSessionIds[confirmSessionId] === 'done') {
            // 仅清理卡片和引导语，不显示重复消息
            if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + confirmSessionId);
            if (window.CardSystem) CardSystem.removeCard(confirmSessionId);
            if (typeof clearPendingSession === 'function') clearPendingSession(confirmSessionId);
            return true;
        }

        // 按 sessionId 清理对应的引导语和卡片
        if (confirmSessionId) {
            if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + confirmSessionId);
            if (window.CardSystem) CardSystem.removeCard(confirmSessionId);
            if (typeof clearPendingSession === 'function') clearPendingSession(confirmSessionId);
        } else {
            if (typeof removeTempMessages === 'function') removeTempMessages();
            if (typeof clearPendingSession === 'function') clearPendingSession();
            if (window.CardSystem) CardSystem.clear();
        }

        var replyMsg = msg.message || (msg.type === 'card_confirmed' ? '好的，提醒已确认啦～' : '好的，已取消提醒。');
        if (typeof addMessage === 'function') {
            addMessage('bot', replyMsg, { skipSave: false });
        }
        if (typeof renderCalendar === 'function') renderCalendar();
        return true;
    }

    if (msg.type === 'execute_card') {
        // 阶段二执行提醒走通知栏（NotificationSystem），而不是当作普通文本消息显示
        if (window.NotificationSystem && msg.data) {
            NotificationSystem.add({
                title: msg.data.title || msg.message || '提醒',
                time: msg.data.remindTime || '',
                location: msg.data.location || '',
                remark: msg.data.weatherSuggestion || msg.data.note || '',
                priority: msg.data.priority || 'NORMAL',
                sessionId: msg.data.sessionId || null,
                attempt: msg.data.attempt || 1
            });
            return true;
        }
        return false;
    }

    if (msg.type === 'timeout_auto_create') {
        var timeoutSessionId = msg.data ? msg.data.sessionId : msg.sessionId;
        
        // 去重：如果已处理过，跳过消息显示
        if (timeoutSessionId && processedSessionIds[timeoutSessionId] === 'done') {
            if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + timeoutSessionId);
            if (window.CardSystem) CardSystem.removeCard(timeoutSessionId);
            if (typeof clearPendingSession === 'function') clearPendingSession(timeoutSessionId);
            return true;
        }
        if (timeoutSessionId) processedSessionIds[timeoutSessionId] = 'done';

        // 按 sessionId 清理对应的引导语和卡片
        if (timeoutSessionId) {
            if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + timeoutSessionId);
            if (window.CardSystem) CardSystem.removeCard(timeoutSessionId);
            if (typeof clearPendingSession === 'function') clearPendingSession(timeoutSessionId);
        } else {
            if (typeof removeTempMessages === 'function') removeTempMessages();
            if (typeof clearPendingSession === 'function') clearPendingSession();
            if (window.CardSystem) CardSystem.clear();
        }

        var autoMsg = msg.message || '⏰ 时间到啦，已帮你自动确认提醒～';
        if (typeof addMessage === 'function') {
            addMessage('bot', autoMsg, { skipSave: false });
        }
        if (typeof renderCalendar === 'function') renderCalendar();
        return true;
    }

    return false;
}

function handleConfirmationActionV2(actionId, sessionId) {
    var userId = localStorage.getItem('userId');
    var headers = getAuthHeaders();
    var endpoint = '/api/confirmations/' + sessionId + '/' + (actionId === 'confirm' ? 'confirm' : actionId === 'cancel' ? 'cancel' : actionId);
    fetch(endpoint + '?userId=' + userId, { method: 'POST', headers: headers })
        .then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                // 按 sessionId 清理对应的引导语和卡片
                if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + sessionId);
                var v2CardEl = document.querySelector('.confirmation-card[data-session-id="' + sessionId + '"]');
                if (v2CardEl) v2CardEl.remove();
                if (typeof clearPendingSession === 'function') clearPendingSession(sessionId);
                if (window.CardSystem) CardSystem.clear();
                var replyMsg = result.message || (actionId === 'confirm' ? '好的，提醒已确认啦～' : '好的，已取消提醒。');
                addMessage('bot', replyMsg, { skipSave: false });
                if (typeof renderCalendar === 'function') renderCalendar();
            } else {
                addMessage('bot', '❌ ' + (result.message || '操作失败'));
            }
        }).catch(function(err) {
            if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + sessionId);
            if (typeof clearPendingSession === 'function') clearPendingSession(sessionId);
            addMessage('bot', '❌ 请求失败，请重试', { skipSave: false });
        });
}

// ===== 多项事务卡片批量操作处理 =====
// 由 card.js 的批量按钮和冲突弹窗调用；itemIds 缺省时从卡片复选框收集
function handleMultiCardAction(actionId, sessionId, itemIds) {
    var userId = localStorage.getItem('userId');
    var headers = getAuthHeaders();
    var cardEl = document.getElementById('card-' + sessionId);

    // 收集选中的条目索引（复选框 data-item-id）
    if (!itemIds || !itemIds.length) {
        itemIds = [];
        if (cardEl) {
            var boxes = cardEl.querySelectorAll('.multi-item-checkbox:checked');
            for (var i = 0; i < boxes.length; i++) {
                var id = boxes[i].getAttribute('data-item-id');
                if (id != null) itemIds.push(parseInt(id, 10));
            }
        }
    }
    if (!itemIds || !itemIds.length) {
        if (typeof addMessage === 'function') {
            addMessage('bot', '⚠️ 请先勾选要操作的条目～', { skipSave: false });
        }
        return;
    }

    // 操作 → 端点映射
    var endpointMap = {
        confirm_selected: '/api/confirmations/multi/' + sessionId + '/confirm-selected',
        cancel_selected: '/api/confirmations/multi/' + sessionId + '/cancel-selected',
        overwrite_selected: '/api/confirmations/multi/' + sessionId + '/overwrite-selected'
    };
    var endpoint = endpointMap[actionId];
    if (!endpoint) {
        if (typeof addMessage === 'function') {
            addMessage('bot', '❌ 未知操作：' + actionId, { skipSave: false });
        }
        return;
    }

    // 覆盖操作按项目约束直接执行（不弹 confirm 窗），所有操作统一 loading 反馈
    setMultiCardButtonsLoading(cardEl, actionId);

    fetch(endpoint + '?userId=' + userId, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify({ itemIds: itemIds })
    }).then(function(res) { return res.json(); })
    .then(function(result) {
        if (result.success) {
            var data = result.data || result || {};
            // 后端多项操作返回 allProcessed 标记是否全部处理完成（兼容 allDone 命名）
            var allDone = data.allProcessed === true || data.allDone === true;

            if (allDone) {
                // 全部处理完成：终结清理（引导语、卡片、会话状态）
                // 顺序：先 removeCard（移除 wrapper+卡片），再清引导语和会话状态
                if (typeof removeMessagesByGroup === 'function') removeMessagesByGroup('card-' + sessionId);
                if (window.CardSystem) CardSystem.removeCard(sessionId);
                if (typeof clearPendingSession === 'function') clearPendingSession(sessionId);
                if (sessionId && processedSessionIds) processedSessionIds[sessionId] = 'done';
                if (typeof addMessage === 'function') {
                    addMessage('bot', result.message || '✅ 多项事务已处理完成', { skipSave: false });
                }
                if (typeof renderCalendar === 'function') renderCalendar();
            } else {
                // 仍有未处理条目：刷新卡片数据，显示进度提示
                if (window.CardSystem && typeof CardSystem.updateMultiCardStatus === 'function' && data.multiItems) {
                    CardSystem.updateMultiCardStatus(sessionId, data);
                } else if (window.CardSystem && typeof CardSystem.fetchCardData === 'function') {
                    CardSystem.fetchCardData(sessionId, userId);
                }
                if (typeof addMessage === 'function') {
                    addMessage('bot', result.message || '✅ 已处理选中条目', { skipSave: false });
                }
                if (typeof renderCalendar === 'function') renderCalendar();
            }
        } else {
            // 失败：恢复按钮，提示错误
            restoreMultiCardButtons(cardEl);
            if (typeof addMessage === 'function') {
                addMessage('bot', '❌ ' + (result.message || '操作失败'), { skipSave: false });
            }
        }
    }).catch(function(err) {
        restoreMultiCardButtons(cardEl);
        if (typeof addMessage === 'function') {
            addMessage('bot', '❌ 请求失败：' + (err && err.message ? err.message : '请重试'), { skipSave: false });
        }
    });
}

// 多项卡片批量按钮统一 loading 反馈：禁用所有按钮，触发按钮显示加载文案
function setMultiCardButtonsLoading(cardEl, actionId) {
    if (!cardEl) return;
    var btns = cardEl.querySelectorAll('.multi-card-actions .action-btn');
    var loadingText = {
        confirm_selected: '⏳ 确认中...',
        cancel_selected: '⏳ 取消中...',
        overwrite_selected: '⏳ 覆盖中...'
    };
    for (var i = 0; i < btns.length; i++) {
        btns[i].disabled = true;
        if (btns[i].getAttribute('data-action-id') === actionId) {
            btns[i].setAttribute('data-original-text', btns[i].textContent);
            btns[i].textContent = loadingText[actionId] || '⏳ 处理中...';
        }
    }
}

// 恢复多项卡片批量按钮状态
function restoreMultiCardButtons(cardEl) {
    if (!cardEl) return;
    var btns = cardEl.querySelectorAll('.multi-card-actions .action-btn');
    for (var i = 0; i < btns.length; i++) {
        btns[i].disabled = false;
        var orig = btns[i].getAttribute('data-original-text');
        if (orig) {
            btns[i].textContent = orig;
            btns[i].removeAttribute('data-original-text');
        }
    }
}

// ===== 导出全局接口 =====
window.initApp = initApp;
window.handleWebSocketMessageV2 = handleWebSocketMessageV2;
// 兼容：WS订阅处调用 handleWebSocketMessage
window.handleWebSocketMessage = handleWebSocketMessageV2;
window.handleConfirmationActionV2 = handleConfirmationActionV2;
window.handleMultiCardAction = handleMultiCardAction;
window.removeTempMessages = removeTempMessages;
window.removeMessagesByGroup = removeMessagesByGroup;
window.addMessage = addMessage;
window.restorePendingSession = restorePendingSession;
window.saveMessageToBackend = saveMessageToBackend;

// ===== 以下为原有内联 JavaScript（已从 index.html 迁移）=====
        let currentUserId = parseInt(localStorage.getItem('userId')) || 1;
        var chatHistoryLoaded = false;
        // 待保存消息队列
        var pendingSaveKeys = new Set();
        var currentYear, currentMonth;
        var selectedDate = null;
        var viewMode = 'month';
        var currentWeekOffset = 0;
        var allScheduleData = [];

        var stompClient = null;
        var isProcessing = false;
        var processedSessionIds = {}; // 已处理的 sessionId -> true，用于去重
        window.processedSessionIds = processedSessionIds;
        var lastUserInput = '';
        var conflictStates = {};
        var currentConflictList = [];

        // 城市搜索相关状态
        var selectedCityValue = null;   // 选中的城市值（用于保存，如 "广东省-广州市-天河区"）
        var citySearchTimer = null;     // 搜索防抖计时器

        // Toast 提示
        var toastTimer = null;
        function showToast(message, isSuccess) {
            var toast = document.getElementById('toast');
            toast.textContent = message;
            // 清除内联样式和旧 class，改用 CSS class
            toast.style.background = '';
            toast.style.color = '';
            toast.classList.remove('toast-success', 'toast-error');
            toast.classList.add(isSuccess ? 'toast-success' : 'toast-error');
            toast.style.display = 'block';
            if (toastTimer) clearTimeout(toastTimer);
            toastTimer = setTimeout(function() {
                toast.style.display = 'none';
                toast.classList.remove('toast-success', 'toast-error');
            }, 3000);
        }

        function getActiveSessionId() {
            var cards = document.querySelectorAll('.confirmation-card[data-session-id]:not([data-session-id=""])');
            if (cards.length > 0) {
                return cards[0].getAttribute('data-session-id');
            }
            return null;
        }

        function connectWebSocket() {
            var socket = new SockJS('/ws?user-id=' + currentUserId);
            stompClient = Stomp.over(socket);
            var headers = {};
                var token = localStorage.getItem('token');
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }
                stompClient.connect(headers, function(frame) {
                console.log('WebSocket连接成功: ' + frame);
                stompClient.subscribe('/user/queue/confirmations', function(message) {
                    var card = JSON.parse(message.body);
                    // 清理对应的引导语临时消息
                    var sid = card.data && card.data.sessionId;
                    if (sid) {
                        removeMessagesByGroup('card-' + sid);
                    }
                    showConfirmationCard(card);
                    // 保存卡片数据到 sessionStorage
                    if (sid) {
                        savePendingSession(sid, null, card);
                    }
                });
                stompClient.subscribe('/user/queue/messages', function(message) {
                    var msg;
                    try {
                        msg = JSON.parse(message.body);
                    } catch (e) {
                        msg = { type: 'text', message: message.body };
                    }
                    // 优先调用增强版V2（包含AGENT_STATUS状态流处理、卡片处理等）
                    var handled = false;
                    if (typeof handleWebSocketMessageV2 === 'function') {
                        handled = handleWebSocketMessageV2(msg) === true;
                    }
                    // V2未处理 → 按普通bot文本消息显示
                    if (!handled && typeof addMessage === 'function') {
                        var text = msg.message || msg.content || msg.text || '';
                        if (!text && typeof msg === 'string') text = msg;
                        if (text) {
                            // 跳过WS推送来的重复最终回复（HTTP响应已经显示过了）
                            if (msg.type === 'text' || !msg.type) {
                                addMessage('bot', text, { skipSave: msg.type != null });
                            }
                        }
                    }
                });
            }, function(error) {
                console.error('WebSocket连接失败:', error);
                setTimeout(connectWebSocket, 5000);
            });
        }

        /**
         * 根据标签名查找确认卡片中对应的值
         * @param {string} label - 标签文本（如"标题"、"时间"、"地点"、"优先级"）
         * @returns {string|null} 对应的值，未找到返回 null
         */
        function getCardFieldValue(label) {
            var card = document.getElementById('confirmationCard');
            if (!card) return null;

            var infoItems = card.querySelectorAll('.info-item');
            for (var i = 0; i < infoItems.length; i++) {
                var labelEl = infoItems[i].querySelector('.info-label');
                if (labelEl && labelEl.textContent === label) {
                    var valueEl = infoItems[i].querySelector('.info-value');
                    return valueEl ? valueEl.textContent : null;
                }
            }
            return null;
        }

        /**
         * 从当前显示的确认卡片中提取完整数据
         * @returns {Object|null} 包含 title/remindTime/location/priority/sessionId 的对象，卡片不存在返回 null
         */
        function getCurrentCardData() {
            var card = document.getElementById('confirmationCard');
            if (!card) return null;

            var data = {
                title: getCardFieldValue('标题'),
                remindTime: getCardFieldValue('时间'),
                location: getCardFieldValue('地点'),
                priority: getCardFieldValue('优先级'),
                sessionId: card.getAttribute('data-session-id'),
                importId: card.getAttribute('data-import-id'),
                isBatch: card.querySelector('.batch-items-list') !== null
            };
            return data;
        }

        function showConfirmationCard(card) {
            // 清理临时消息
            if (typeof removeTempMessages === 'function') removeTempMessages();

            var container = document.getElementById('confirmationContainer');

            // 使用 CardSystem 处理确认卡片（如可用）
            // multi_confirmation_card 走同一入口，CardSystem.show 内部按 data.isMulti 路由到专用渲染
            if (window.CardSystem && (card.type === 'confirmation_card' || card.type === 'single_confirm_card' || card.type === 'multi_confirmation_card')) {
                CardSystem.show({
                    message: card.message || '请确认信息',
                    data: card.data || {},
                    actions: card.actions || [{ id: 'confirm', label: '✅ 确认', style: 'primary' }, { id: 'cancel', label: '❌ 取消', style: 'danger' }],
                    expiresAt: card.expiresAt || null
                });
                if (card.data && card.data.sessionId) {
                    // sessionId 已通过 data-session-id 属性存储在卡片元素上
                }
                return;
            }

            if (card.type === 'success') {
                addMessage('bot', card.message);
                container.innerHTML = '';
                originalConfirmationCard = null;
                return;
            }
            
            if (card.type === 'cancelled') {
                addMessage('bot', card.message);
                container.innerHTML = '';
                originalConfirmationCard = null;
                return;
            }
            
            if (card.type === 'timeout') {
                addMessage('bot', card.message);
                container.innerHTML = '';
                originalConfirmationCard = null;
                return;
            }
            
            if (card.type === 'timeout_auto_create') {
                addMessage('bot', card.message);
                container.innerHTML = '';
                originalConfirmationCard = null;
                renderCalendar();
                return;
            }
            
            if (card.type === 'confirmation_card') {
                originalConfirmationCard = card;
                var expiresAt = card.expiresAt ? new Date(card.expiresAt) : null;
                var hasConflict = card.data.hasConflict === true;
                var isBatch = card.data.isBatch === true;
                var importId = card.data.importId || null;
                
                var sessionId = card.data.sessionId || 'import-' + (importId || Date.now());
                // 去重：如果同 sessionId 的卡片已存在，跳过
                var existingCard = document.querySelector('.confirmation-card[data-session-id="' + sessionId + '"]');
                if (existingCard) {
                    console.log('卡片已存在，跳过重复渲染: sessionId=' + sessionId);
                    return;
                }
                conflictStates = {};
                currentConflictList = [];
                
                if (hasConflict && card.data.conflictList) {
                    // conflictList 现在是数组对象（后端改为 List<ConflictItem>），无需 JSON.parse
                    currentConflictList = Array.isArray(card.data.conflictList) ? card.data.conflictList : [];

                    currentConflictList.forEach(function(c) {
                        conflictStates[c.id] = 'pending';
                    });
                }
                
                var dataAttrs = 'data-session-id="' + sessionId + '"';
                if (importId) {
                    dataAttrs += ' data-import-id="' + importId + '"';
                }
                dataAttrs += ' data-has-conflict="' + (hasConflict ? 'true' : 'false') + '"';
                
                var html = '<div class="confirmation-card ' + (hasConflict ? 'conflict-mode' : '') + '" id="confirmationCard-' + sessionId + '" ' + dataAttrs + '>';
                html += '<div class="card-title">' + card.message + '</div>';
                html += '<div class="card-info">';
                
                if (isBatch) {
                    html += '<div class="info-item"><span class="info-label">来源</span><span class="info-value">' + (card.data.sourceFile || '文件') + '</span></div>';
                    html += '<div class="info-item"><span class="info-label">共</span><span class="info-value">' + card.data.totalCount + ' 条日程</span></div>';
                    
                    if (card.data.items && Array.isArray(card.data.items) && card.data.items.length > 0) {
                        html += '<div class="batch-items-list">';
                        card.data.items.forEach(function(item, idx) {
                            var timeStr = '';
                            if (item.date) timeStr += item.date;
                            if (item.time) timeStr += ' ' + item.time;
                            var locStr = item.location ? ' · ' + item.location : '';
                            html += '<div class="batch-item-row">';
                            html += '<span class="batch-item-num">' + (idx + 1) + '.</span>';
                            html += '<span class="batch-item-title">' + (item.title || '未命名') + '</span>';
                            html += '<span class="batch-item-time">' + timeStr + locStr + '</span>';
                            html += '</div>';
                        });
                        html += '</div>';
                    }
                } else {
                    html += '<div class="info-item"><span class="info-label">标题</span><span class="info-value">' + card.data.title + '</span></div>';
                    if (card.data.endTime) {
                        // 时间段显示
                        var startTime = card.data.remindTime ? card.data.remindTime.slice(11, 16) : '';
                        var endTime = card.data.endTime.slice(11, 16);
                        html += '<div class="info-item"><span class="info-label">时间</span><span class="info-value">' + startTime + ' → ' + endTime + '</span></div>';
                    } else {
                        html += '<div class="info-item"><span class="info-label">时间</span><span class="info-value">' + card.data.remindTime + '</span></div>';
                    }
                    if (card.data.location) {
                        html += '<div class="info-item"><span class="info-label">地点</span><span class="info-value">' + card.data.location + '</span></div>';
                    }
                    if (card.data.note) {
                        html += '<div class="info-item"><span class="info-label">备注</span><span class="info-value">' + card.data.note + '</span></div>';
                    }
                    html += '<div class="info-item"><span class="info-label">优先级</span><span class="info-value">' + card.data.priority + '</span></div>';
                }
                html += '</div>';
                
                if (hasConflict && currentConflictList.length > 0) {
                    html += '<div class="conflict-section">';
                    html += '<div class="conflict-section-title">⚠️ 冲突的提醒：</div>';
                    html += '<div class="conflict-select-all">';
                    html += '<input type="checkbox" id="selectAllCheckbox" onchange="toggleSelectAll()" ' + (currentConflictList.length === currentConflictList.filter(function(c) { return conflictStates[c.id] === 'selected'; }).length ? 'checked' : '') + '>';
                    html += '<label for="selectAllCheckbox">全选（覆盖所有冲突）</label>';
                    html += '</div>';
                    html += '<div class="conflict-items" id="conflictItems">';
                    currentConflictList.forEach(function(c) {
                        var cid = c.id;
                        var state = conflictStates[cid] || 'pending';
                        var time = c.remindTime || '';
                        if (time && time.length > 16) time = time.substring(5, 16);
                        var rowClass = 'conflict-item-row';
                        if (state === 'selected') {
                            rowClass += ' row-selected';
                        }
                        html += '<div class="' + rowClass + '" id="conflict-row-' + cid + '">';
                        html += '<div class="conflict-checkbox-wrap">';
                        html += '<input type="checkbox" class="conflict-checkbox" id="conflict-checkbox-' + cid + '" data-conflict-id="' + cid + '" ' + (state === 'selected' ? 'checked' : '') + ' onchange="toggleConflictItem(' + cid + ', this)">';
                        html += '</div>';
                        html += '<div class="conflict-info">📌 ' + (c.title || '未命名') + ' - ' + time + '</div>';
                        html += '</div>';
                    });
                    html += '</div>';
                    if (card.data.suggestedTime) {
                        html += '<div class="suggested-time-inline">💡 建议可以改到: ' + card.data.suggestedTime + '</div>';
                    }
                    html += '</div>';
                }
                
                html += '<div class="card-actions">';
                card.actions.forEach(function(action) {
                    html += '<button class="action-btn ' + action.style + '" onclick="handleConfirmationAction(\'' + action.id + '\', \'' + sessionId + '\')">' + action.label + '</button>';
                });
                html += '</div>';
                var noTimeout = card.data.noTimeout === true;
                if (isBatch || noTimeout) {
                    html += '<div class="card-timer" id="timerText-' + sessionId + '">📋 请确认以上信息</div>';
                } else {
                    html += '<div class="card-timer" id="timerText-' + sessionId + '">⏳ 5分钟后未确认自动确认</div>';
                }
                html += '</div>';

                container.insertAdjacentHTML('beforeend', html);
                
                if (!isBatch && !noTimeout && expiresAt) {
                    startTimer(expiresAt, card.data.sessionId);
                }
            }
            
            if (card.type === 'execute_card') {
                var html = '<div class="confirmation-card execute-card" id="confirmationCard" data-session-id="' + card.data.sessionId + '">';
                html += '<div class="card-title">' + card.message + '</div>';
                html += '<div class="card-info">';
                html += '<div class="info-item"><span class="info-label">标题</span><span class="info-value">' + card.data.title + '</span></div>';
                html += '<div class="info-item"><span class="info-label">时间</span><span class="info-value">' + card.data.remindTime + '</span></div>';
                if (card.data.location) {
                    html += '<div class="info-item"><span class="info-label">地点</span><span class="info-value">' + card.data.location + '</span></div>';
                }
                if (card.data.note) {
                    html += '<div class="info-item"><span class="info-label">备注</span><span class="info-value">' + card.data.note + '</span></div>';
                }
                html += '</div>';
                html += '<div class="card-actions">';
                card.actions.forEach(function(action) {
                    html += '<button class="action-btn ' + action.style + '" onclick="handleExecuteAction(\'' + action.id + '\', \'' + card.data.sessionId + '\')">' + action.label + '</button>';
                });
                html += '</div>';
                html += '<div class="card-timer" id="timerText">第 ' + (card.data.attempt || 1) + ' 次提醒，还剩 ' + (3 - (card.data.attempt || 1)) + ' 次机会</div>';
                html += '</div>';
                
                container.innerHTML = html;
            }
            
            if (card.type === 'schedule_table_confirm') {
                var container = document.getElementById('confirmationContainer');
                var importId = (card.data && card.data.importId) ? card.data.importId : '';
                var totalCount = (card.data && card.data.totalCount) ? card.data.totalCount : 0;
                var html = '<div class="confirmation-card schedule-table-card" id="confirmationCard" data-import-id="' + importId + '">';
                html += '<div class="card-title">' + (card.message || '📚 课表识别完成') + '</div>';
                
                // 课程列表
                if (card.data && card.data.items && card.data.items.length > 0) {
                    html += '<div class="course-list">';
                    html += '<div class="course-list-header"><span>📖 识别到 ' + totalCount + ' 门课程</span></div>';
                    card.data.items.forEach(function(item, idx) {
                        html += '<div class="course-item">';
                        html += '<span class="course-index">' + (idx + 1) + '.</span>';
                        html += '<span class="course-title">' + (item.title || '未知课程') + '</span>';
                        if (item.time) {
                            var startShort = item.time.length >= 16 ? item.time.slice(11, 16) : item.time;
                            var timeDisplay = startShort;
                            if (item.endTime && item.endTime.length >= 16) {
                                timeDisplay = startShort + ' - ' + item.endTime.slice(11, 16);
                            }
                            html += '<span class="course-info">' + timeDisplay + '</span>';
                        }
                        if (item.location) html += '<span class="course-info">📍' + item.location + '</span>';
                        html += '</div>';
                    });
                    html += '</div>';
                }

                // 推断的时间表
                if (card.data && card.data.timeSections && card.data.timeSections.length > 0) {
                    html += '<div style="margin-bottom:14px;">';
                    html += '<div style="font-size:14px;color:var(--brown-mid);margin-bottom:8px;">⏰ 推断的课程时间表</div>';
                    html += '<div style="background:var(--cream-bg);border-radius:12px;padding:12px;">';
                    card.data.timeSections.forEach(function(ts) {
                        if (ts.startTime && ts.endTime) {
                            html += '<div style="display:flex;align-items:center;gap:12px;padding:3px 0;font-size:13px;border-bottom:1px solid var(--peach-pale);">';
                            html += '<span style="width:50px;color:var(--brown-mid);font-weight:bold;">第' + ts.index + '节</span>';
                            html += '<span style="color:var(--brown-deep);">' + ts.startTime + ' - ' + ts.endTime + '</span>';
                            html += '</div>';
                        }
                    });
                    html += '</div></div>';
                } else {
                    html += '<div style="font-size:13px;color:var(--brown-light);padding:8px 0;">⏰ 暂无时间表，请点击"手动调整"配置</div>';
                }

                // 学期开始日期
                if (card.data && card.data.semesterStartDate) {
                    html += '<div class="info-item"><span class="info-label">学期开始</span><span class="info-value">' + card.data.semesterStartDate + '</span></div>';
                }

                // 学期总周数
                if (card.data && card.data.totalWeeks) {
                    html += '<div class="info-item"><span class="info-label">学期周数</span><span class="info-value">' + card.data.totalWeeks + ' 周</span></div>';
                }
                
                html += '<div class="card-actions">';
                html += '<button class="action-btn primary" onclick="handleScheduleConfirm(\'' + importId + '\', \'confirm\')">✅ 确认生成</button>';
                html += '<button class="action-btn secondary" onclick="handleScheduleConfirm(\'' + importId + '\', \'adjust\')">✏️ 手动调整</button>';
                html += '<button class="action-btn danger" onclick="handleScheduleConfirm(\'' + importId + '\', \'cancel\')">❌ 取消</button>';
                html += '</div>';
                html += '</div>';
                container.innerHTML = html;
            }
            
            if (card.type === 'time_config_card') {
                var container = document.getElementById('confirmationContainer');
                var importId = (card.data && card.data.importId) ? card.data.importId : '';
                var html = '<div class="confirmation-card time-config-card" id="confirmationCard" data-import-id="' + importId + '">';
                html += '<div class="card-title">⏰ 课程时间配置</div>';
                
                html += '<div class="info-item"><span class="info-label">学期开始日期</span>';
                html += '<input type="date" class="date-input" id="semesterStartDate" value="' + (card.data && card.data.semesterStartDate ? card.data.semesterStartDate : '') + '">';
                html += '</div>';

                html += '<div class="info-item"><span class="info-label">学期总周数</span>';
                html += '<div style="display:flex;align-items:center;gap:10px;">';
                html += '<input type="number" id="configTotalWeeks" value="' + (card.data && card.data.totalWeeks ? card.data.totalWeeks : 16) + '" min="1" max="30" style="width:80px;padding:6px 10px;border:2px solid var(--brown-pale);border-radius:8px;font-size:14px;">';
                html += '<span style="font-size:13px;color:var(--brown-light);">周</span>';
                if (card.data && card.data.suggestedWeeks) {
                    html += '<span style="font-size:12px;color:var(--brown-light);margin-left:4px;">（建议 ' + card.data.suggestedWeeks + ' 周）</span>';
                }
                html += '</div></div>';
                
                html += '<div class="link-mode-group">';
                html += '<div class="link-mode-label">联动模式：</div>';
                html += '<div class="link-mode-buttons">';
                html += '<button class="link-mode-btn active" onclick="selectLinkMode(this, \'TOP_DOWN\')">⬇️ 向下联动</button>';
                html += '<button class="link-mode-btn" onclick="selectLinkMode(this, \'BOTTOM_UP\')">⬆️ 向上联动</button>';
                html += '<button class="link-mode-btn" onclick="selectLinkMode(this, \'BOTH\')">⬅️➡️ 双向联动</button>';
                html += '<button class="link-mode-btn" onclick="selectLinkMode(this, \'NONE\')">❌ 互不影响</button>';
                html += '</div>';
                html += '</div>';
                
                html += '<div class="time-slots-container">';
                html += '<div class="time-slots-header"><span>节次</span><span>开始时间</span><span>结束时间</span></div>';
                if (card.data && card.data.timeSections) {
                    card.data.timeSections.forEach(function(ts) {
                        html += '<div class="time-slot-row" data-index="' + ts.index + '">';
                        html += '<span class="slot-index">第' + ts.index + '节</span>';
                        html += '<input type="time" class="slot-start" value="' + (ts.startTime || '') + '" onchange="onTimeSlotChange(this, \'start\')">';
                        html += '<input type="time" class="slot-end" value="' + (ts.endTime || '') + '" onchange="onTimeSlotChange(this, \'end\')">';
                        html += '</div>';
                    });
                }
                html += '</div>';
                
                html += '<div class="inference-result" id="inferenceResult"></div>';
                
                html += '<div class="card-actions">';
                html += '<button class="action-btn primary" onclick="submitTimeConfig(\'' + importId + '\')">✅ 确认配置</button>';
                html += '<button class="action-btn danger" onclick="handleScheduleConfirm(\'' + importId + '\', \'cancel\')">❌ 取消</button>';
                html += '</div>';
                html += '</div>';
                container.innerHTML = html;
                // 默认向下联动，重置状态避免上次选择残留
                currentLinkMode = 'TOP_DOWN';
                // 初始推理
                inferTimeSlots();
            }
            
            if (card.type === 'manual_create_card') {
                var container = document.getElementById('confirmationContainer');
                var importId = (card.data && card.data.importId) ? card.data.importId : '';
                var html = '<div class="confirmation-card manual-create-card" id="confirmationCard" data-import-id="' + importId + '">';
                html += '<div class="card-title">✏️ 手动创建课程</div>';
                
                html += '<div class="info-item"><span class="info-label">学期开始日期</span>';
                html += '<input type="date" class="date-input" id="manualSemesterStart" value="' + (card.data && card.data.semesterStartDate ? card.data.semesterStartDate : '') + '">';
                html += '</div>';

                html += '<div class="info-item"><span class="info-label">学期总周数</span>';
                html += '<div style="display:flex;align-items:center;gap:10px;">';
                html += '<input type="number" id="manualTotalWeeks" value="' + (card.data && card.data.totalWeeks ? card.data.totalWeeks : 16) + '" min="1" max="30" style="width:80px;padding:6px 10px;border:2px solid var(--brown-pale);border-radius:8px;font-size:14px;">';
                html += '<span style="font-size:13px;color:var(--brown-light);">周</span>';
                html += '</div></div>';
                
                html += '<div class="manual-course-list" id="manualCourseList"></div>';
                
                html += '<button class="action-btn secondary" onclick="addManualCourseRow()">➕ 添加课程</button>';
                
                html += '<div class="card-actions">';
                html += '<button class="action-btn primary" onclick="submitManualCourses(\'' + importId + '\')">📚 生成日程</button>';
                html += '<button class="action-btn danger" onclick="handleScheduleConfirm(\'' + importId + '\', \'cancel\')">❌ 取消</button>';
                html += '</div>';
                html += '</div>';
                container.innerHTML = html;
                // 初始添加一行
                addManualCourseRow();
            }
        }

        // ===== 课表相关函数 =====
        
        var currentLinkMode = 'TOP_DOWN';
        var manualCourseRowCount = 0;

        function getImportIdFromCard() {
            var card = document.getElementById('confirmationCard');
            if (card) {
                var importId = card.getAttribute('data-import-id');
                if (importId && importId !== 'undefined' && importId !== 'null' && importId !== '') return importId;
                if (card.dataset && card.dataset.importId) return card.dataset.importId;
            }
            // 尝试从容器中查找
            var container = document.getElementById('confirmationContainer');
            if (container) {
                var innerCard = container.querySelector('[data-import-id]');
                if (innerCard) {
                    var id = innerCard.getAttribute('data-import-id');
                    if (id && id !== 'undefined' && id !== 'null' && id !== '') return id;
                }
            }
            // 尝试从 originalConfirmationCard 中获取
            if (typeof originalConfirmationCard !== 'undefined' && originalConfirmationCard && originalConfirmationCard.data) {
                if (originalConfirmationCard.data.importId) return originalConfirmationCard.data.importId;
                if (originalConfirmationCard.data.sessionId) return originalConfirmationCard.data.sessionId;
            }
            console.warn('⚠️ 无法获取 importId');
            return null;
        }

        function handleScheduleConfirm(importId, action) {
            if (action === 'cancel') {
                var container = document.getElementById('confirmationContainer');
                if (container) container.innerHTML = '';
                return;
            }
            
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) headers['Authorization'] = 'Bearer ' + token;
            var userId = localStorage.getItem('userId');

            if (action === 'confirm') {
                fetch('/api/schedule/confirm-ai?importId=' + importId + '&userId=' + userId, {
                    method: 'POST',
                    headers: headers
                })
                .then(function(res) { return res.json(); })
                .then(function(result) {
                    if (result.success) {
                        showSchedulePreview(result);
                    } else {
                        addMessage('bot', '❌ ' + result.message);
                    }
                })
                .catch(function(err) {
                    addMessage('bot', '❌ 请求失败: ' + err.message);
                });
            } else if (action === 'adjust') {
                fetch('/api/schedule/get-time-config?importId=' + importId + '&userId=' + userId, {
                    headers: headers
                })
                .then(function(res) { return res.json(); })
                .then(function(result) {
                    if (result.success) {
                        showConfirmationCard({
                            type: 'time_config_card',
                            message: '⏰ 配置课程时间',
                            data: result.data || {}
                        });
                    } else {
                        addMessage('bot', '❌ ' + result.message);
                    }
                })
                .catch(function(err) {
                    addMessage('bot', '❌ 请求失败: ' + err.message);
                });
            }
        }

        function getTimeSlotsFromUI() {
            var slots = [];
            var rows = document.querySelectorAll('.time-slot-row');
            rows.forEach(function(row) {
                var index = parseInt(row.getAttribute('data-index'));
                var startInput = row.querySelector('.slot-start');
                var endInput = row.querySelector('.slot-end');
                slots.push({
                    index: index,
                    startTime: startInput ? startInput.value : '',
                    endTime: endInput ? endInput.value : '',
                    _userFilled: row.dataset.userFilled === 'true'
                });
            });
            return slots;
        }

        function updateTimeSlotsUI(slots) {
            slots.forEach(function(slot) {
                var row = document.querySelector('.time-slot-row[data-index="' + slot.index + '"]');
                if (row) {
                    var startInput = row.querySelector('.slot-start');
                    var endInput = row.querySelector('.slot-end');
                    if (startInput) startInput.value = slot.startTime || '';
                    if (endInput) endInput.value = slot.endTime || '';
                    if (slot._userFilled) row.dataset.userFilled = 'true';
                }
            });
            inferTimeSlots();
        }

        function selectLinkMode(el, mode) {
            currentLinkMode = mode;
            var buttons = document.querySelectorAll('.link-mode-btn');
            buttons.forEach(function(btn) {
                btn.classList.remove('active');
            });
            el.classList.add('active');
            inferTimeSlots();
        }

        function onTimeSlotChange(input, field) {
            var row = input.closest('.time-slot-row');
            var index = parseInt(row.dataset.index);
            var slots = getTimeSlotsFromUI();
            var idx = slots.findIndex(function(s) { return s.index === index; });
            if (idx < 0) return;

            var current = slots[idx];

            current._userFilled = true;

            function getFirstClassDuration(slotList) {
                // 跳过 current（正在被修改的节次），避免用 start 新值 / end 旧值的过渡态推时长
                var first = slotList.find(function(s) {
                    return s !== current && s.startTime && s.endTime && s.startTime.length > 0 && s.endTime.length > 0;
                });
                if (first) {
                    var dur = calculateDuration(first.startTime, first.endTime);
                    return (dur > 0) ? dur : 45;
                }
                return 45;
            }

            var classDuration = getFirstClassDuration(slots);

            if (field === 'start') {
                current.endTime = addMinutes(current.startTime, classDuration);
            }

            if (field === 'end') {
                var newDuration = calculateDuration(current.startTime, current.endTime);
                if (newDuration > 0) {
                    classDuration = newDuration;
                }
            }

            // 根据 currentLinkMode 控制联动方向
            // TOP_DOWN：影响 fromIdx 之后；BOTTOM_UP：影响 fromIdx 之前；BOTH：双向
            if (currentLinkMode === 'TOP_DOWN' || currentLinkMode === 'BOTH') {
                propagateDownward(slots, idx, classDuration);
            }
            if (currentLinkMode === 'BOTTOM_UP' || currentLinkMode === 'BOTH') {
                propagateUpward(slots, idx, classDuration);
            }

            // === 再次检查，确保所有节次时间合法 ===
            slots = validateAllSlots(slots, classDuration);

            updateTimeSlotsUI(slots);
            inferTimeSlots();
        }

        // 向下联动：更新 fromIdx 之后的所有节次
        function propagateDownward(slots, fromIdx, classDuration) {
            for (var i = fromIdx + 1; i < slots.length; i++) {
                var target = slots[i];
                var prev = slots[i - 1];
                if (target._userFilled) continue;

                // startTime 为空时，基于前一节 endTime + 课间自动推断
                if (!target.startTime || target.startTime.length === 0) {
                    if (prev && prev.endTime && prev.endTime.length > 0) {
                        var autoBreak = getDefaultBreakTime(slots, i);
                        target.startTime = addMinutes(prev.endTime, autoBreak);
                        target.endTime = addMinutes(target.startTime, classDuration);
                    }
                    continue;
                }

                // startTime 有值：若时间倒退（早于前一节 endTime）则前移
                if (prev && prev.endTime && prev.endTime.length > 0) {
                    var gap = calculateDuration(prev.endTime, target.startTime);
                    if (gap < 0) {
                        var breakTime = getDefaultBreakTime(slots, i);
                        target.startTime = addMinutes(prev.endTime, breakTime);
                    }
                }
                target.endTime = addMinutes(target.startTime, classDuration);
            }
        }

        // 向上联动：更新 fromIdx 之前的所有节次（基于后续节次反推）
        function propagateUpward(slots, fromIdx, classDuration) {
            var standardBreak = getStandardBreak(slots);
            for (var i = fromIdx - 1; i >= 0; i--) {
                var target = slots[i];
                var next = slots[i + 1];
                if (target._userFilled) continue;
                if (!next || !next.startTime || next.startTime.length === 0) continue;

                // endTime = 下一节 startTime - 标准课间；startTime = endTime - 课时长
                target.endTime = subtractMinutes(next.startTime, standardBreak);
                target.startTime = subtractMinutes(target.endTime, classDuration);
            }
        }

        // 获取标准课间时长：统计所有相邻节次间隔的众数，无数据时默认 10 分钟
        function getStandardBreak(slots) {
            var breaks = [];
            for (var i = 0; i < slots.length - 1; i++) {
                var a = slots[i];
                var b = slots[i + 1];
                if (a.endTime && a.endTime.length > 0 && b.startTime && b.startTime.length > 0) {
                    var g = calculateDuration(a.endTime, b.startTime);
                    if (g > 0 && g < 120) {
                        breaks.push(g);
                    }
                }
            }
            var mostCommon = getMostCommon(breaks);
            return (mostCommon && mostCommon > 0) ? mostCommon : 10;
        }

        function getDefaultBreakTime(slots, index) {
            var prev = slots[index - 1];
            var next = slots[index];
            if (prev && prev.endTime && next && next.startTime) {
                var gap = calculateDuration(prev.endTime, next.startTime);
                if (gap > 0 && gap < 120) {
                    return gap;
                }
            }
            var breaks = [];
            for (var i = 0; i < slots.length - 1; i++) {
                var a = slots[i];
                var b = slots[i + 1];
                if (a.endTime && a.endTime.length > 0 && b.startTime && b.startTime.length > 0) {
                    var g = calculateDuration(a.endTime, b.startTime);
                    if (g > 0 && g < 120) {
                        breaks.push(g);
                    }
                }
            }
            var mostCommon = getMostCommon(breaks);
            return (mostCommon && mostCommon > 0) ? mostCommon : 10;
        }

        function validateAllSlots(slots, classDuration) {
            for (var i = 1; i < slots.length; i++) {
                var prev = slots[i - 1];
                var current = slots[i];

                if (!prev.endTime || prev.endTime.length === 0) continue;
                if (!current.startTime || current.startTime.length === 0) continue;

                var gap = calculateDuration(prev.endTime, current.startTime);
                if (gap < 0) {
                    var breakTime = 10;
                    if (current._userFilled) {
                        if (!prev._userFilled) {
                            prev.endTime = subtractMinutes(current.startTime, 10);
                        }
                    } else {
                        var newStart = addMinutes(prev.endTime, 10);
                        current.startTime = newStart;
                        if (current.endTime && current.endTime.length > 0) {
                            var dur = calculateDuration(current.startTime, current.endTime);
                            if (dur < 0) {
                                current.endTime = addMinutes(current.startTime, classDuration);
                            }
                        }
                    }
                }
            }
            return slots;
        }

        function subtractMinutes(time, minutes) {
            if (!time) return '';
            return addMinutes(time, -minutes);
        }

        function parseTimeToMinutes(timeStr) {
            if (!timeStr) return 0;
            var parts = timeStr.split(':');
            return parseInt(parts[0]) * 60 + parseInt(parts[1]);
        }

        function minutesToTime(minutes) {
            var h = Math.floor(minutes / 60) % 24;
            var m = minutes % 60;
            return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
        }

        function calculateDuration(start, end) {
            if (!start || !end) return 45;
            // 返回真实差值（含负数），让"时间倒退"检测生效；
            // 计算课时长等正向场景的调用方均有 > 0 守卫，负数不会影响
            return parseTimeToMinutes(end) - parseTimeToMinutes(start);
        }

        function getMostCommon(arr) {
            if (!arr || arr.length === 0) return null;
            var map = {};
            arr.forEach(function(v) {
                if (v > 0) {
                    map[v] = (map[v] || 0) + 1;
                }
            });
            var maxCount = 0;
            var maxVal = null;
            for (var key in map) {
                if (map[key] > maxCount) {
                    maxCount = map[key];
                    maxVal = parseInt(key);
                }
            }
            return maxVal;
        }

        function addMinutes(time, minutes) {
            if (!time || !minutes) return time;
            var parts = time.split(':').map(Number);
            var total = parts[0] * 60 + parts[1] + minutes;
            var h = String(Math.floor(total / 60)).padStart(2, '0');
            var m = String(total % 60).padStart(2, '0');
            return h + ':' + m;
        }

        function inferTimeSlots() {
            var slots = getTimeSlotsFromUI();
            var resultEl = document.getElementById('inferenceResult');
            if (!resultEl) return;

            var completeSlots = slots.filter(function(s) {
                return s.startTime && s.endTime && s.startTime.length > 0 && s.endTime.length > 0;
            });

            if (completeSlots.length === 0) {
                resultEl.innerHTML = '⏳ 请填写至少一个节次的开始和结束时间，系统将自动推理';
                return;
            }

            var classDurations = completeSlots.map(function(s) {
                var dur = calculateDuration(s.startTime, s.endTime);
                return { index: s.index, duration: dur };
            });

            var breakInfos = [];
            for (var i = 0; i < slots.length - 1; i++) {
                var current = slots[i];
                var next = slots[i + 1];
                if (current.endTime && current.endTime.length > 0 &&
                    next.startTime && next.startTime.length > 0) {
                    var gap = calculateDuration(current.endTime, next.startTime);
                    if (gap > 0 && gap < 180) {
                        breakInfos.push({
                            from: current.index,
                            to: next.index,
                            gap: gap,
                            fromEnd: current.endTime,
                            toStart: next.startTime
                        });
                    }
                }
            }

            var durations = classDurations.map(function(d) { return d.duration; });
            var inferredClassDuration = getMostCommon(durations);
            if (!inferredClassDuration || inferredClassDuration <= 0) {
                inferredClassDuration = 45;
            }

            var gaps = breakInfos.map(function(b) { return b.gap; });
            var inferredBreak = getMostCommon(gaps);
            if (!inferredBreak || inferredBreak <= 0) {
                inferredBreak = 10;
            }

            var longBreaks = breakInfos.filter(function(b) {
                return b.gap > inferredBreak * 1.5;
            });

            var html = '';
            html += '<div style="font-size:14px;color:var(--brown-deep);font-weight:bold;margin-bottom:8px;">📊 推理结果</div>';
            html += '<div style="font-size:13px;color:var(--brown-mid);margin-bottom:4px;">⏱ 每节课时长：<strong style="color:var(--brown-deep);">' + inferredClassDuration + ' 分钟</strong></div>';

            if (breakInfos.length > 0) {
                html += '<div style="font-size:13px;color:var(--brown-mid);margin-top:4px;">☕ 课间休息：</div>';
                html += '<div style="font-size:12px;color:var(--brown-light);padding-left:12px;">';
                breakInfos.forEach(function(b) {
                    var isLong = b.gap > inferredBreak * 1.5;
                    var label = isLong ? ' 🔸 大课间' : ' ✅ 普通';
                    html += '第' + b.from + '→' + b.to + '节：' + b.gap + ' 分钟' + label + '<br>';
                });
                html += '</div>';
            }

            var totalClasses = classDurations.length;
            var totalMinutes = classDurations.reduce(function(sum, d) { return sum + d.duration; }, 0);
            var totalBreak = breakInfos.reduce(function(sum, b) { return sum + b.gap; }, 0);

            html += '<div style="font-size:12px;color:var(--brown-light);margin-top:6px;border-top:1px solid var(--peach-pale);padding-top:6px;">';
            html += '已填写 ' + totalClasses + ' 节课，共 ' + totalMinutes + ' 分钟';
            if (breakInfos.length > 0) {
                html += '，课间共 ' + totalBreak + ' 分钟';
            }
            html += '</div>';

            if (longBreaks.length > 0) {
                html += '<div style="font-size:12px;color:#FFB347;margin-top:4px;">💡 检测到 ' + longBreaks.length + ' 个大课间（间隔 > ' + inferredBreak + ' 分钟）</div>';
            }

            resultEl.innerHTML = html;
        }

        function submitTimeConfig(importId) {
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) headers['Authorization'] = 'Bearer ' + token;
            var userId = localStorage.getItem('userId');
            
            var totalWeeks = parseInt(document.getElementById('configTotalWeeks').value) || 16;
            var config = {
                semesterStartDate: document.getElementById('semesterStartDate').value,
                linkMode: currentLinkMode,
                sectionTimes: getTimeSlotsFromUI(),
                importId: importId,
                totalWeeks: totalWeeks
            };
            console.log('📝 submitTimeConfig Request Payload:', JSON.stringify(config, null, 2));

            fetch('/api/schedule/config-time?userId=' + userId, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(config)
            })
            .then(function(res) { return res.json(); })
            .then(function(result) {
                if (result.success) {
                    showSchedulePreview(result);
                } else {
                    addMessage('bot', '❌ ' + result.message);
                }
            })
            .catch(function(err) {
                addMessage('bot', '❌ 请求失败: ' + err.message);
            });
        }

        function showSchedulePreview(result) {
            var container = document.getElementById('confirmationContainer');
            if (!container) return;
            
            var importId = result.importId || '';
            var html = '<div class="confirmation-card preview-card" id="confirmationCard" data-import-id="' + importId + '">';
            html += '<div class="card-title">' + (result.message || '📋 日程预览') + '</div>';
            
            if (result.items && result.items.length > 0) {
                html += '<div class="info-item"><span class="info-label">日程总数</span><span class="info-value">' + result.items.length + ' 条</span></div>';
            }
            if (result.totalWeeks !== undefined) {
                html += '<div class="info-item"><span class="info-label">学期周数</span><span class="info-value">' + result.totalWeeks + ' 周</span></div>';
            }
            if (result.conflictCount !== undefined) {
                html += '<div class="info-item"><span class="info-label">冲突数量</span><span class="info-value' + (result.conflictCount > 0 ? ' conflict' : '') + '">' + result.conflictCount + ' 条</span></div>';
            }
            
            html += '<div class="card-actions">';
            html += '<button class="action-btn primary" onclick="handleConfirmationAction(\'schedule_direct\', \'' + importId + '\')">✅ 直接创建</button>';
            html += '<button class="action-btn secondary" onclick="handleConfirmationAction(\'schedule_skip\', \'' + importId + '\')">⏭️ 创建无冲突部分</button>';
            html += '<button class="action-btn warning" onclick="handleConfirmationAction(\'schedule_overwrite\', \'' + importId + '\')">📝 全部覆盖</button>';
            html += '<button class="action-btn secondary" onclick="handleScheduleConfirm(\'' + importId + '\', \'adjust\')">✏️ 修改</button>';
            html += '<button class="action-btn danger" onclick="handleConfirmationAction(\'cancel\', \'' + importId + '\')">❌ 取消</button>';
            html += '</div>';
            html += '</div>';
            container.innerHTML = html;
        }

        function addManualCourseRow() {
            manualCourseRowCount++;
            var rowId = 'manualRow_' + manualCourseRowCount;
            var list = document.getElementById('manualCourseList');
            if (!list) return;
            
            var row = document.createElement('div');
            row.className = 'manual-course-row';
            row.id = rowId;
            row.innerHTML = `
                <input type="text" class="course-name-input" placeholder="课程名">
                <select class="day-of-week-select">
                    <option value="">选择星期</option>
                    <option value="星期一">星期一</option>
                    <option value="星期二">星期二</option>
                    <option value="星期三">星期三</option>
                    <option value="星期四">星期四</option>
                    <option value="星期五">星期五</option>
                    <option value="星期六">星期六</option>
                    <option value="星期日">星期日</option>
                </select>
                <input type="text" class="section-range-input" placeholder="节次(如 1-2)">
                <input type="text" class="week-range-input" placeholder="周次(如 1-17)">
                <select class="odd-even-select">
                    <option value="全部">全部</option>
                    <option value="单周">单周</option>
                    <option value="双周">双周</option>
                </select>
                <input type="text" class="location-input" placeholder="地点(可选)">
                <input type="text" class="teacher-input" placeholder="教师(可选)">
                <button class="remove-row-btn" onclick="removeManualCourseRow('${rowId}')">❌</button>
            `;
            list.appendChild(row);
        }

        function removeManualCourseRow(rowId) {
            var row = document.getElementById(rowId);
            if (row) row.remove();
        }

        function submitManualCourses(importId) {
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) headers['Authorization'] = 'Bearer ' + token;
            var userId = localStorage.getItem('userId');
            
            var rows = document.querySelectorAll('.manual-course-row');
            var courses = [];
            rows.forEach(function(row) {
                var course = {
                    courseName: row.querySelector('.course-name-input').value,
                    dayOfWeek: row.querySelector('.day-of-week-select').value,
                    sectionRange: row.querySelector('.section-range-input').value,
                    weekRange: row.querySelector('.week-range-input').value,
                    oddEvenWeek: row.querySelector('.odd-even-select').value,
                    location: row.querySelector('.location-input').value,
                    teacher: row.querySelector('.teacher-input').value
                };
                if (course.courseName) courses.push(course);
            });

            if (courses.length === 0) {
                addMessage('bot', '⚠️ 请至少添加一门课程');
                return;
            }

            var config = {
                semesterStartDate: document.getElementById('manualSemesterStart').value,
                totalWeeks: parseInt(document.getElementById('manualTotalWeeks').value) || 16,
                courses: courses,
                importId: importId
            };
            console.log('📝 submitManualCourses Request Payload:', JSON.stringify(config, null, 2));

            fetch('/api/schedule/manual?userId=' + userId, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(config)
            })
            .then(function(res) { return res.json(); })
            .then(function(result) {
                if (result.success) {
                    showSchedulePreview(result);
                } else {
                    addMessage('bot', '❌ ' + result.message);
                }
            })
            .catch(function(err) {
                addMessage('bot', '❌ 请求失败: ' + err.message);
            });
        }

        function handleWebSocketMessage(msg) {
            if (!msg || !msg.type) {
                addMessage('bot', msg.message || JSON.stringify(msg));
                return;
            }

            // 优先使用增强版处理（集成 CardSystem 和 NotificationSystem）
            if (typeof handleWebSocketMessageV2 === 'function') {
                var handled = handleWebSocketMessageV2(msg);
                if (handled) return;
            }

            if (msg.type === 'single_confirm_card') {
                // 使用 CardSystem 处理
                if (window.CardSystem) {
                    CardSystem.show({
                        message: msg.message || '请确认信息',
                        data: msg.data || {},
                        actions: msg.actions || [{ id: 'confirm', label: '✅ 确认', style: 'primary' }, { id: 'cancel', label: '❌ 取消', style: 'danger' }],
                        expiresAt: msg.expiresAt || null
                    });
                } else {
                    renderSingleConfirmCard(msg);
                }
            } else if (msg.type === 'multi_confirm_card') {
                renderMultiConfirmCard(msg);
            } else if (msg.type === 'pending_summary') {
                addMessage('bot', msg.message);
            } else if (msg.type === 'timeout_auto_create') {
                handleTimeoutAutoCreate(msg);
            } else if (msg.type === 'batch_confirm_result') {
                handleBatchResult(msg);
            } else if (msg.type === 'batch_cancel_result') {
                handleBatchResult(msg);
            } else if (msg.type === 'card_confirmed') {
                handleCardConfirmed(msg);
            } else if (msg.type === 'card_cancelled') {
                handleCardCancelled(msg);
            } else if (msg.type === 'import_result') {
                addMessage('bot', msg.message);
                renderCalendar();
            } else if (msg.type === 'AGENT_STATUS') {
                // 兜底：如果 V2 没命中，由 AgentStatusFlow 统一处理
                if (window.AgentStatusFlow) {
                    window.AgentStatusFlow.onStatusEvent(msg);
                }
            } else {
                addMessage('bot', msg.message || JSON.stringify(msg));
            }
        }

        function handleTimeoutAutoCreate(msg) {
            if (timerInterval) clearInterval(timerInterval);

            // 清理 CardSystem 卡片
            if (window.CardSystem) { CardSystem.clear(); }
            
            addMessage('bot', msg.message || '✅ 已自动确认提醒');
            renderCalendar();
            
            // 立即清空卡片
            var container = document.getElementById('confirmationContainer');
            if (container) container.innerHTML = '';
        }

        function handleBatchResult(msg) {
            addMessage('bot', msg.message || '操作完成');
            renderCalendar();
        }

        function handleCardConfirmed(msg) {
            if (timerInterval) clearInterval(timerInterval);
            
            var sessionId = msg.sessionId;
            var title = msg.title || '';
            var remindTime = msg.remindTime || '';
            
            // 查找并移除对应的确认卡片
            if (sessionId) {
                // 先尝试按data-session-id查找单张卡片
                var card = document.querySelector('.confirmation-card[data-session-id="' + sessionId + '"]');
                if (card) {
                    card.remove();
                } else {
                    // 尝试查找multi-confirm卡片中的单个item
                    var item = document.getElementById('item-' + sessionId);
                    if (item) {
                        item.remove();
                        // 如果multi卡片中没有更多item，移除整个卡片
                        var multiCard = document.getElementById('multiConfirmCard');
                        if (multiCard && multiCard.querySelectorAll('.card-list-item').length === 0) {
                            multiCard.remove();
                        }
                    } else {
                        // 都找不到，清空整个容器
                        var container = document.getElementById('confirmationContainer');
                        if (container) container.innerHTML = '';
                    }
                }
            } else {
                // 阶段二确认，清除所有卡片
                var container = document.getElementById('confirmationContainer');
                if (container) container.innerHTML = '';
            }
            
            // 显示确认成功消息
            var message = '✅ 已确认：' + title;
            if (remindTime) {
                message += '，时间 ' + remindTime;
            }
            addMessage('bot', message);
            
            // 刷新日历
            renderCalendar();
        }

        function handleCardCancelled(msg) {
            if (timerInterval) clearInterval(timerInterval);
            
            var sessionId = msg.sessionId;
            var title = msg.title || '';
            
            // 查找并移除对应的确认卡片
            if (sessionId) {
                var card = document.querySelector('.confirmation-card[data-session-id="' + sessionId + '"]');
                if (card) {
                    card.remove();
                } else {
                    var item = document.getElementById('item-' + sessionId);
                    if (item) {
                        item.remove();
                        var multiCard = document.getElementById('multiConfirmCard');
                        if (multiCard && multiCard.querySelectorAll('.card-list-item').length === 0) {
                            multiCard.remove();
                        }
                    } else {
                        var container = document.getElementById('confirmationContainer');
                        if (container) container.innerHTML = '';
                    }
                }
            } else {
                var container = document.getElementById('confirmationContainer');
                if (container) container.innerHTML = '';
            }
            
            // 显示取消消息
            addMessage('bot', '❌ 已取消：' + title);
        }

        function renderSingleConfirmCard(card) {
            var container = document.getElementById('confirmationContainer');
            var html = '<div class="confirmation-card" id="singleConfirmCard" data-session-id="' + card.sessionId + '">';
            html += '<div class="card-title">📋 请确认：' + card.title + '</div>';
            html += '<div class="card-info">';
            html += '<div class="info-item">⏰ ' + card.remindTime + '</div>';
            if (card.location) {
                html += '<div class="info-item">📍 ' + card.location + '</div>';
            }
            html += '</div>';
            html += '<div class="card-timer">⏳ ' + card.expiresAt + ' 前未确认将自动确认</div>';
            html += '<div class="card-actions">';
            html += '<button class="action-btn primary" onclick="handleSingleConfirm(\'' + card.sessionId + '\')">✅ 确认</button>';
            html += '<button class="action-btn danger" onclick="handleSingleCancel(\'' + card.sessionId + '\')">❌ 取消</button>';
            html += '</div>';
            html += '</div>';
            container.innerHTML = html;
        }

        async function handleSingleConfirm(sessionId) {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        sessionId: sessionId,
                        action: 'confirm',
                        userId: currentUserId
                    })
                });
                const result = await response.json();

                if (!result.success) {
                    addMessage('bot', '❌ ' + result.message);
                }
                // 卡片关闭由WebSocket消息 card_confirmed 处理
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        async function handleAutoConfirm(sessionId) {
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        sessionId: sessionId,
                        action: 'confirm',
                        userId: currentUserId
                    })
                });
                const result = await response.json();

                var timerText = document.getElementById('timerText');
                if (!result.success) {
                    if (timerText) {
                        timerText.textContent = '自动确认失败';
                    }
                    showToast('自动确认失败：' + result.message, false);
                    addMessage('bot', '❌ ' + result.message);
                }
            } catch (e) {
                var timerText = document.getElementById('timerText');
                if (timerText) {
                    timerText.textContent = '网络错误';
                }
                showToast('网络错误', false);
                addMessage('bot', '❌ 网络错误');
            }
        }

        async function handleSingleCancel(sessionId) {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        sessionId: sessionId,
                        action: 'cancel',
                        userId: currentUserId
                    })
                });
                const result = await response.json();

                if (!result.success) {
                    addMessage('bot', '❌ ' + result.message);
                }
                // 卡片关闭由WebSocket消息处理
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        function renderMultiConfirmCard(card) {
            var container = document.getElementById('confirmationContainer');
            var html = '<div class="confirmation-card" id="multiConfirmCard" data-session-id="multi">';
            html += '<div class="card-title">📋 您有 ' + card.count + ' 个待确认行程，请选择：</div>';
            html += '<div class="card-list">';
            card.items.forEach(function(item, index) {
                html += '<div class="card-list-item" id="item-' + item.sessionId + '">';
                html += '<span class="item-number">' + (index + 1) + '.</span>';
                html += '<span class="item-title">' + item.title + '</span>';
                html += '<span class="item-time">，' + item.remindTime + '</span>';
                html += '<div class="item-actions">';
                html += '<button class="mini-btn confirm" onclick="handleMultiItemConfirm(\'' + item.sessionId + '\')">✅确认</button>';
                html += '<button class="mini-btn cancel" onclick="handleMultiItemCancel(\'' + item.sessionId + '\')">❌取消</button>';
                html += '</div>';
                html += '</div>';
            });
            html += '</div>';
            html += '<div class="card-actions">';
            html += '<button class="action-btn primary" onclick="handleConfirmAll()">✅ 一键确认全部</button>';
            html += '<button class="action-btn danger" onclick="handleCancelAll()">❌ 一键取消全部</button>';
            html += '</div>';
            html += '<div class="card-timer">⏳ 单个行程可在右侧操作</div>';
            html += '</div>';
            container.innerHTML = html;
        }

        async function handleMultiItemConfirm(sessionId) {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        sessionId: sessionId,
                        action: 'confirm',
                        userId: currentUserId
                    })
                });
                const result = await response.json();

                if (!result.success) {
                    addMessage('bot', '❌ ' + result.message);
                }
                // 卡片关闭由WebSocket消息 card_confirmed 处理
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        async function handleMultiItemCancel(sessionId) {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        sessionId: sessionId,
                        action: 'cancel',
                        userId: currentUserId
                    })
                });
                const result = await response.json();

                if (!result.success) {
                    addMessage('bot', '❌ ' + result.message);
                }
                // 卡片关闭由WebSocket消息处理
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        async function handleConfirmAll() {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action-all', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        userId: currentUserId,
                        action: 'confirmAll'
                    })
                });
                const result = await response.json();

                if (!result.success) {
                    addMessage('bot', '❌ ' + result.message);
                }
                // 卡片关闭由WebSocket消息 card_confirmed / batch_confirm_result 处理
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        async function handleCancelAll() {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) headers['Authorization'] = 'Bearer ' + token;

                const response = await fetch('/api/confirmations/action-all', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        userId: currentUserId,
                        action: 'cancelAll'
                    })
                });
                const result = await response.json();

                if (result.success) {
                    addMessage('bot', result.message);
                    document.getElementById('confirmationContainer').innerHTML = '';
                } else {
                    addMessage('bot', '❌ ' + result.message);
                }
            } catch (e) {
                addMessage('bot', '❌ 网络错误');
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        var timerInterval = null;
        var currentTimerSessionId = null;
        var originalConfirmationCard = null;
        function startTimer(expiresAt, sessionId) {
            if (!expiresAt || isNaN(expiresAt.getTime())) {
                console.warn('startTimer: expiresAt 无效', expiresAt);
                return;
            }
            if (timerInterval) clearInterval(timerInterval);
            currentTimerSessionId = sessionId;
            
            timerInterval = setInterval(function() {
                var now = new Date();
                var remaining = Math.ceil((expiresAt - now) / 1000);

                var timerText = document.getElementById('timerText-' + currentTimerSessionId) || document.getElementById('timerText');
                if (timerText) {
                    if (remaining <= 0) {
                        clearInterval(timerInterval);
                        timerText.textContent = '⏳ 确认已超时，请重新输入';
                        // 禁用卡片上的所有按钮
                        var buttons = document.querySelectorAll('.action-btn, .mini-btn');
                        buttons.forEach(function(btn) { btn.disabled = true; });
                    } else {
                        timerText.textContent = '⏰ ' + remaining + '秒后自动确认';
                        if (remaining <= 10) {
                            timerText.classList.add('warning');
                        }
                    }
                }
            }, 1000);
        }

        function toggleSelectAll() {
            var checkbox = document.getElementById('selectAllCheckbox');
            if (!checkbox) return;
            var newState = checkbox.checked;
            
            currentConflictList.forEach(function(c) {
                conflictStates[c.id] = newState ? 'selected' : 'pending';
                var cb = document.getElementById('conflict-checkbox-' + c.id);
                var row = document.getElementById('conflict-row-' + c.id);
                if (cb) cb.checked = newState;
                if (row) {
                    if (newState) {
                        row.classList.add('row-selected');
                    } else {
                        row.classList.remove('row-selected');
                    }
                }
            });
        }

        function toggleConflictItem(conflictId, checkbox) {
            var row = document.getElementById('conflict-row-' + conflictId);
            if (!row) return;
            
            if (checkbox.checked) {
                conflictStates[conflictId] = 'selected';
                row.classList.add('row-selected');
            } else {
                conflictStates[conflictId] = 'pending';
                row.classList.remove('row-selected');
            }
            
            var selectAllCheckbox = document.getElementById('selectAllCheckbox');
            if (selectAllCheckbox) {
                var selectedCount = currentConflictList.filter(function(c) {
                    return conflictStates[c.id] === 'selected';
                }).length;
                selectAllCheckbox.checked = selectedCount === currentConflictList.length;
            }
        }

        function getSelectedConflictIds() {
            var ids = [];
            for (var cid in conflictStates) {
                if (conflictStates[cid] === 'selected') {
                    ids.push(parseInt(cid));
                }
            }
            return ids;
        }

        async function handleConfirmationAction(action, sessionId) {
            console.log('📝 handleConfirmationAction 被调用:', action, sessionId, 'type:', typeof action);
            
            // 修改(edit)是非终结操作，不应触发会话级去重，否则修改后新卡片无法确认
            var isTerminalAction = (action === 'confirm' || action === 'cancel' || action === 'overwrite'
                || action === 'confirm_direct' || action === 'confirm_skip' || action === 'overwrite_all'
                || action === 'confirm_import' || action === 'cancel_import'
                || action === 'schedule_direct' || action === 'schedule_skip' || action === 'schedule_overwrite');
            
            // 会话级去重：同一个 sessionId 的终结操作不重复处理
            if (isTerminalAction && sessionId && processedSessionIds[sessionId]) {
                console.warn('会话已处理，忽略重复请求: sessionId=' + sessionId);
                return;
            }
            
            if (isProcessing) return;
            isProcessing = true;
            
            // 仅终结操作标记会话为已处理，修改(edit)不标记
            if (isTerminalAction && sessionId) processedSessionIds[sessionId] = true;
            
            // 立即禁用所有操作按钮（包括卡片内的）
            var allButtons = document.querySelectorAll('.action-btn, .mini-btn, .confirmation-card button, .card-action-btn');
            allButtons.forEach(function(btn) { btn.disabled = true; btn.style.pointerEvents = 'none'; });
            
            if (timerInterval) clearInterval(timerInterval);
            
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }
            
            try {
                // 复用 allButtons 作为 buttons，供各分支使用
                var buttons = allButtons;
                if (action === 'confirm') {
                    var idsToOverwrite = getSelectedConflictIds();
                    var confirmBody = idsToOverwrite.length > 0
                        ? JSON.stringify({ conflictIds: idsToOverwrite })
                        : null;
                    const response = await fetch('/api/confirmations/' + sessionId + '/confirm?userId=' + currentUserId, {
                        method: 'POST',
                        headers: headers,
                        body: confirmBody
                    });
                    const result = await response.json();
                    if (result.success) {
                        var confirmMsg = result.message;
                        if (result.overwrittenTitle) {
                            confirmMsg = '✅ 已覆盖「' + result.overwrittenTitle + '」，新提醒已创建';
                        }
                        handleConfirmFinalResponse(result, true, sessionId);
                    } else {
                        addMessage('bot', '❌ ' + result.message);
                    }
                } else if (action === 'cancel') {
                    const response = await fetch('/api/confirmations/' + sessionId + '/cancel?userId=' + currentUserId, {
                        method: 'POST',
                        headers: headers
                    });
                    const result = await response.json();
                    if (result.success) {
                        handleConfirmFinalResponse(result, false, sessionId);
                    } else {
                        addMessage('bot', '❌ ' + result.message);
                    }
                } else if (action === 'overwrite') {
                    var selectedIds = getSelectedConflictIds();
                    if (selectedIds.length === 0) {
                        addMessage('bot', '⚠️ 请先勾选要覆盖的冲突提醒（或点击全选）');
                        isProcessing = false;
                        buttons.forEach(function(btn) { btn.disabled = false; });
                        return;
                    }
                    var overwriteBtn = Array.from(buttons).find(function(btn) {
                        return btn.textContent.includes('覆盖') && !btn.textContent.includes('全');
                    });
                    if (overwriteBtn) {
                        overwriteBtn.textContent = '⏳ 覆盖中...';
                        overwriteBtn.disabled = true;
                    }
                    try {
                        var overwriteHeaders = { 'Content-Type': 'application/json' };
                        var overwriteToken = localStorage.getItem('token');
                        if (overwriteToken) overwriteHeaders['Authorization'] = 'Bearer ' + overwriteToken;

                        var overwriteUrl;
                        var overwriteBody = null;
                        if (selectedIds.length === currentConflictList.length) {
                            overwriteUrl = '/api/confirmations/' + sessionId + '/overwrite?userId=' + currentUserId;
                        } else {
                            overwriteUrl = '/api/confirmations/' + sessionId + '/batch-overwrite?userId=' + currentUserId;
                            overwriteBody = JSON.stringify({ conflictIds: selectedIds });
                        }

                        const response = await fetch(overwriteUrl, {
                            method: 'POST',
                            headers: overwriteHeaders,
                            body: overwriteBody
                        });
                        const result = await response.json();
                        if (result.success) {
                            handleConfirmFinalResponse(result, true, sessionId);
                        } else {
                            addMessage('bot', '❌ ' + result.message);
                            if (overwriteBtn) {
                                overwriteBtn.textContent = '📝 覆盖';
                                overwriteBtn.disabled = false;
                            }
                        }
                    } catch (e) {
                        removeTempMessages();
                        addMessage('bot', '❌ 网络错误，请重试', { skipSave: false });
                        if (overwriteBtn) {
                            overwriteBtn.textContent = '📝 覆盖';
                            overwriteBtn.disabled = false;
                        }
                    }
                    return;
                } else if (action === 'edit') {
                    showEditForm(sessionId);
                } else if (action === 'confirm_direct' || action === 'confirm_skip' || action === 'overwrite_all') {
                    var urlMap = {
                        'confirm_direct': '/api/confirmations/' + sessionId + '/recurring/direct?userId=' + currentUserId,
                        'confirm_skip': '/api/confirmations/' + sessionId + '/recurring/skip?userId=' + currentUserId,
                        'overwrite_all': '/api/confirmations/' + sessionId + '/recurring/overwrite-all?userId=' + currentUserId
                    };
                    var btnLabelMap = {
                        'confirm_direct': '⏳ 创建中...',
                        'confirm_skip': '⏳ 创建中...',
                        'overwrite_all': '⏳ 覆盖中...'
                    };
                    var clickedBtn = Array.from(buttons).find(function(btn) { return btn.disabled && btn.textContent.indexOf('⏳') === -1; });
                    if (clickedBtn) clickedBtn.textContent = btnLabelMap[action] || '⏳ 处理中...';

                    const response = await fetch(urlMap[action], { method: 'POST', headers: headers });
                    const result = await response.json();
                    if (result.success) {
                        handleConfirmFinalResponse(result, true, sessionId);
                    } else {
                        addMessage('bot', '❌ ' + (result.message || result.error || '操作失败'));
                        buttons.forEach(function(btn) { btn.disabled = false; });
                    }
                } else if (action === 'confirm_import') {
                    var importCardEl = document.getElementById('confirmationContainer');
                    var importCard = importCardEl ? importCardEl.querySelector('.confirmation-card') : null;
                    var importId = null;
                    if (importCard && importCard.dataset.importId) {
                        importId = importCard.dataset.importId;
                    } else {
                        var data = getCurrentCardData();
                        if (data && data.importId) importId = data.importId;
                    }
                    if (!importId) {
                        addMessage('bot', '❌ 无法获取导入ID，请重试');
                        isProcessing = false;
                        buttons.forEach(function(btn) { btn.disabled = false; });
                        return;
                    }
                    var confirmBtn = Array.from(buttons).find(function(btn) { return btn.textContent.includes('全部创建'); });
                    if (confirmBtn) {
                        confirmBtn.textContent = '⏳ 创建中...';
                    }
                    const importResponse = await fetch('/api/schedule/confirm/' + importId + '?userId=' + currentUserId, {
                        method: 'POST',
                        headers: headers
                    });
                    const importResult = await importResponse.json();
                    if (importResult.success) {
                        handleConfirmFinalResponse(importResult, true, sessionId);
                    } else {
                        addMessage('bot', '❌ ' + (importResult.message || '创建失败'));
                        if (confirmBtn) confirmBtn.textContent = '✅ 全部创建';
                    }
                } else if (action === 'cancel_import') {
                    var cancelCardEl = document.getElementById('confirmationContainer');
                    var cancelCard = cancelCardEl ? cancelCardEl.querySelector('.confirmation-card') : null;
                    var cancelImportId = null;
                    if (cancelCard && cancelCard.dataset.importId) {
                        cancelImportId = cancelCard.dataset.importId;
                    } else {
                        var cancelData = getCurrentCardData();
                        if (cancelData && cancelData.importId) cancelImportId = cancelData.importId;
                    }
                    if (cancelImportId) {
                        await fetch('/api/schedule/cancel/' + cancelImportId + '?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers
                        });
                    }
                    handleConfirmFinalResponse({ message: '已取消导入' }, false, sessionId);
                } else if (action === 'schedule_direct' || action === 'schedule_skip' || action === 'schedule_overwrite') {
                    var importId = sessionId;
                    if (!importId || importId === 'undefined' || importId === 'null' || importId === '') {
                        importId = getImportIdFromCard();
                    }
                    if (!importId) {
                        addMessage('bot', '❌ 无法获取导入ID，请重试');
                        isProcessing = false;
                        buttons.forEach(function(btn) { btn.disabled = false; });
                        return;
                    }
                    
                    var scheduleUrlMap = {
                        'schedule_direct': '/api/schedule/confirm/direct',
                        'schedule_skip': '/api/schedule/confirm/skip',
                        'schedule_overwrite': '/api/schedule/confirm/overwrite-all'
                    };
                    var scheduleBtnLabelMap = {
                        'schedule_direct': '⏳ 创建中...',
                        'schedule_skip': '⏳ 创建中...',
                        'schedule_overwrite': '⏳ 覆盖中...'
                    };
                    var scheduleBtn = Array.from(buttons).find(function(btn) {
                        return btn.textContent.indexOf('⏳') === -1 && !btn.textContent.includes('取消') && !btn.textContent.includes('修改');
                    });
                    if (scheduleBtn) scheduleBtn.textContent = scheduleBtnLabelMap[action] || '⏳ 处理中...';

                    const scheduleResponse = await fetch(scheduleUrlMap[action] + '?importId=' + importId + '&userId=' + currentUserId, {
                        method: 'POST',
                        headers: headers
                    });
                    // 先检查 HTTP 状态，避免非 JSON 响应导致 json() 解析失败
                    if (!scheduleResponse.ok) {
                        const errorText = await scheduleResponse.text();
                        console.error('课表确认请求失败:', scheduleResponse.status, errorText);
                        addMessage('bot', '❌ 创建失败（HTTP ' + scheduleResponse.status + '）：' + (errorText.substring(0, 150) || '服务器内部错误'));
                        if (scheduleBtn) {
                            scheduleBtn.textContent = action === 'schedule_overwrite' ? '📝 全部覆盖' : '✅ 直接创建';
                        }
                    } else {
                        const scheduleResult = await scheduleResponse.json();
                        if (scheduleResult.success) {
                            handleConfirmFinalResponse(scheduleResult, true, sessionId);
                            conflictStates = {};
                            currentConflictList = [];
                        } else {
                            addMessage('bot', '❌ ' + (scheduleResult.message || '操作失败'));
                            if (scheduleBtn) {
                                scheduleBtn.textContent = action === 'schedule_overwrite' ? '📝 全部覆盖' : '✅ 直接创建';
                            }
                        }
                    }
                }
            } catch (e) {
                removeTempMessages();
                clearPendingSession();
                // 失败时移除 sessionId 标记，允许重试
                if (sessionId) delete processedSessionIds[sessionId];
                addMessage('bot', '❌ 网络错误，请重试', { skipSave: false });
            } finally {
                isProcessing = false;
                // 恢复所有按钮（但已处理的会话保持标记，不会再触发）
                var allBtns = document.querySelectorAll('.action-btn, .mini-btn, .confirmation-card button, .card-action-btn');
                allBtns.forEach(function(btn) { btn.disabled = false; btn.style.pointerEvents = ''; });
            }
        }

        async function handleExecuteAction(action, sessionId) {
            if (isProcessing) return;
            isProcessing = true;
            
            // 禁用所有按钮
            var buttons = document.querySelectorAll('.action-btn, .mini-btn');
            buttons.forEach(function(btn) { btn.disabled = true; });
            
            if (timerInterval) clearInterval(timerInterval);
            
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }
            
            try {
                var url = '/api/confirmations/' + sessionId + '/execute/';
                if (action === 'confirm') {
                    url += 'confirm';
                } else if (action === 'later') {
                    url += 'later';
                } else if (action === 'cancel') {
                    url += 'cancel';
                }
                
                const response = await fetch(url + '?userId=' + currentUserId, {
                    method: 'POST',
                    headers: headers
                });
                const result = await response.json();
                
                if (result.success) {
                    if (typeof removeTempMessages === 'function') removeTempMessages();
                    if (typeof clearPendingSession === 'function') clearPendingSession();
                    var replyMsg = result.message || '操作成功';
                    addMessage('bot', replyMsg, { skipSave: false });
                    document.getElementById('confirmationContainer').innerHTML = '';
                    renderCalendar();
                } else {
                    addMessage('bot', '❌ ' + result.message);
                }
            } catch (e) {
                if (typeof removeTempMessages === 'function') removeTempMessages();
                if (typeof clearPendingSession === 'function') clearPendingSession();
                addMessage('bot', '❌ 网络错误，请重试', { skipSave: false });
            } finally {
                isProcessing = false;
                buttons.forEach(function(btn) { btn.disabled = false; });
            }
        }

        function showEditForm(sessionId) {
            if (timerInterval) clearInterval(timerInterval);

            // 先获取数据，判断是否为周期提醒
            fetch('/api/confirmations/' + sessionId + '/data?userId=' + currentUserId)
                .then(response => response.json())
                .then(data => {
                    // 判断是否为周期提醒
                    if (data.isRecurring === true) {
                        showRecurringEditForm(sessionId, data);
                    } else {
                        showSingleEditForm(sessionId, data);
                    }
                })
                .catch(e => {
                    console.error('加载数据失败', e);
                    addMessage('bot', '❌ 加载数据失败，请重试');
                });
        }

        function showSingleEditForm(sessionId, data) {
            var container = document.getElementById('confirmationContainer');
            var html = '<div class="confirmation-card edit-form" id="editForm">';
            html += '<div class="card-title">✏️ 修改提醒信息</div>';
            html += '<div class="card-info">';
            html += '<div class="form-item">';
            html += '<label class="form-label">标题</label>';
            html += '<input type="text" class="form-input" id="editTitle" placeholder="请输入标题">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">开始时间</label>';
            html += '<input type="datetime-local" class="form-input" id="editTime">';
            html += '</div>';
            html += '<div class="form-item" id="editEndTimeRow">';
            html += '<label class="form-label">结束时间（可选）</label>';
            html += '<input type="datetime-local" class="form-input" id="editEndTime">';
            html += '</div>';
            html += '<div class="form-item suggested-time-row" id="suggestedTimeRow" style="display:none;">';
            html += '<label class="form-label">💡 建议时间</label>';
            html += '<div class="suggested-time-content">';
            html += '<span id="suggestedTimeText"></span>';
            html += '<button class="action-btn success" onclick="applySuggestion()">✨ 采用建议</button>';
            html += '</div>';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">地点</label>';
            html += '<input type="text" class="form-input" id="editLocation" placeholder="请输入地点（可选）">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">优先级</label>';
            html += '<select class="form-input" id="editPriority">';
            html += '<option value="LOW">低</option>';
            html += '<option value="NORMAL" selected>中</option>';
            html += '<option value="HIGH">高</option>';
            html += '</select>';
            html += '</div>';
            html += '</div>';
            html += '<div class="card-actions">';
            html += '<button class="action-btn primary" onclick="submitEditForm(\'' + sessionId + '\')">✅ 确认修改</button>';
            html += '<button class="action-btn danger" onclick="cancelEditForm(\'' + sessionId + '\')">❌ 取消修改</button>';
            html += '</div>';
            html += '</div>';

            container.innerHTML = html;

            // 填充数据
            if (data.title) document.getElementById('editTitle').value = data.title;
            if (data.remindTime) {
                var dt = new Date(data.remindTime.replace(' ', 'T'));
                document.getElementById('editTime').value = dt.toISOString().slice(0, 16);
            }
            if (data.endTime) {
                var et = new Date(data.endTime.replace(' ', 'T'));
                document.getElementById('editEndTime').value = et.toISOString().slice(0, 16);
            }
            if (data.location) document.getElementById('editLocation').value = data.location;
            if (data.priority) document.getElementById('editPriority').value = data.priority;
            if (data.suggestedTime) {
                document.getElementById('suggestedTimeText').textContent = data.suggestedTime;
                document.getElementById('suggestedTimeRow').style.display = 'block';
                document.getElementById('suggestedTimeRow').dataset.suggestedTime = data.suggestedTime;
            }
        }

        function showRecurringEditForm(sessionId, data) {
            var container = document.getElementById('confirmationContainer');
            var html = '<div class="confirmation-card edit-form" id="editForm">';
            html += '<div class="card-title">✏️ 修改周期提醒</div>';
            html += '<div class="card-info">';
            html += '<div class="form-item">';
            html += '<label class="form-label">标题</label>';
            html += '<input type="text" class="form-input" id="editRecurringTitle" placeholder="请输入标题">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">时间</label>';
            html += '<input type="time" class="form-input" id="editRecurringTime" step="1">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">开始日期</label>';
            html += '<input type="date" class="form-input" id="editRecurringStartDate">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">结束日期</label>';
            html += '<input type="date" class="form-input" id="editRecurringEndDate">';
            html += '</div>';
            html += '<div class="form-item">';
            html += '<label class="form-label">重复类型</label>';
            html += '<select class="form-input" id="editRecurringRepeatType">';
            html += '<option value="DAILY">每天</option>';
            html += '<option value="WEEKLY">每周</option>';
            html += '<option value="MONTHLY">每月</option>';
            html += '</select>';
            html += '</div>';
            html += '</div>';
            html += '<div class="card-actions">';
            html += '<button class="action-btn primary" onclick="submitRecurringEdit(\'' + sessionId + '\')">✅ 确认修改</button>';
            html += '<button class="action-btn danger" onclick="cancelEditForm(\'' + sessionId + '\')">❌ 取消修改</button>';
            html += '</div>';
            html += '</div>';

            container.innerHTML = html;

            // 填充数据
            if (data.title) document.getElementById('editRecurringTitle').value = data.title;
            var remindTime = data.recurringRemindTime || data.remindTime;
            if (remindTime) {
                // 如果是完整时间 "2026-08-03 19:00:00"，提取时间部分
                if (remindTime.indexOf(' ') !== -1) {
                    remindTime = remindTime.split(' ')[1];
                }
                document.getElementById('editRecurringTime').value = remindTime;
            }
            if (data.startDate) document.getElementById('editRecurringStartDate').value = data.startDate;
            if (data.endDate) document.getElementById('editRecurringEndDate').value = data.endDate;
            if (data.repeatType) document.getElementById('editRecurringRepeatType').value = data.repeatType;
        }

        async function submitRecurringEdit(sessionId) {
            var title = document.getElementById('editRecurringTitle').value.trim();
            var timeStr = document.getElementById('editRecurringTime').value;
            var startDate = document.getElementById('editRecurringStartDate').value;
            var endDate = document.getElementById('editRecurringEndDate').value;
            var repeatType = document.getElementById('editRecurringRepeatType').value;

            if (!title) {
                addMessage('bot', '❌ 请输入标题');
                return;
            }
            if (!timeStr) {
                addMessage('bot', '❌ 请选择时间');
                return;
            }
            if (!startDate) {
                addMessage('bot', '❌ 请选择开始日期');
                return;
            }
            if (!endDate) {
                addMessage('bot', '❌ 请选择结束日期');
                return;
            }
            if (startDate > endDate) {
                addMessage('bot', '❌ 开始日期不能晚于结束日期');
                return;
            }

            // 拼接完整日期时间供后端解析
            var fullDateTime = startDate + ' ' + timeStr;

            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }

            try {
                // 调试日志：确认 body 构建正确
                var recurringBody = JSON.stringify({
                    title: title,
                    remindTime: fullDateTime,
                    location: null,
                    priority: 'NORMAL',
                    advanceMinutes:10,
                    isRecurring: true,
                    startDate: startDate,
                    endDate: endDate,
                    repeatType: repeatType,
                    recurringRemindTime: timeStr
                });
                console.log('📝 submitRecurringEdit 提交修改: sessionId=', sessionId, 'bodyStr=', recurringBody);
                if (!recurringBody || recurringBody === '{}') {
                    console.error('❌ recurringBody 为空，中止提交');
                    addMessage('bot', '❌ 提交数据异常，请重试');
                    return;
                }

                const response = await fetch('/api/confirmations/' + sessionId + '/reparse?userId=' + currentUserId, {
                    method: 'POST',
                    headers: headers,
                    body: recurringBody
                });
                const result = await response.json();

                if (result.success) {
                    addMessage('bot', '✅ 已修改周期提醒，请确认');
                    // 重新加载卡片
                    try {
                        const dataResponse = await fetch('/api/confirmations/' + sessionId + '/data?userId=' + currentUserId);
                        const data = await dataResponse.json();
                        var defaultActions = [
                            { id: 'confirm', label: '✅ 确认', style: 'primary' },
                            { id: 'edit', label: '✏️ 修改', style: 'warning' },
                            { id: 'cancel', label: '❌ 取消', style: 'danger' }
                        ];
                        var card = {
                            type: 'confirmation_card',
                            message: '📅 已更新周期提醒信息，请确认：',
                            data: data,
                            actions: result.actions && result.actions.length > 0 ? result.actions : defaultActions,
                            timeout: result.timeout || 30,
                            expiresAt: result.expiresAt || new Date(Date.now() + 30000).toISOString()
                        };
                        showConfirmationCard(card);
                    } catch (e) {
                        console.error('重新加载卡片失败', e);
                    }
                } else {
                    addMessage('bot', '❌ ' + (result.message || '修改失败'));
                }
            } catch (e) {
                console.error('提交周期修改失败', e);
                addMessage('bot', '❌ 提交修改失败，请稍后重试');
            }
        }

        async function submitEditForm(sessionId) {
            var title = document.getElementById('editTitle').value.trim();
            var timeStr = document.getElementById('editTime').value;
            var endTimeStr = document.getElementById('editEndTime').value;
            var location = document.getElementById('editLocation').value.trim();
            var priority = document.getElementById('editPriority').value;
            
            if (!title) {
                addMessage('bot', '❌ 请输入标题');
                return;
            }
            if (!timeStr) {
                addMessage('bot', '❌ 请选择时间');
                return;
            }
            
            var headers = { 'Content-Type': 'application/json' };
            var token = localStorage.getItem('token');
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }
            
            var requestBody = {
                title: title,
                remindTime: timeStr.replace('T', ' ') + ':00',
                location: location || null,
                priority: priority,
                advanceMinutes: 10
            };
            
            // 如果填写了结束时间，添加 endTime
            if (endTimeStr) {
                requestBody.endTime = endTimeStr.replace('T', ' ') + ':00';
            }
            
            // 调试日志：确认 requestBody 构建正确，body 不为空
            var bodyStr = JSON.stringify(requestBody);
            console.log('📝 submitEditForm 提交修改: sessionId=', sessionId, 'requestBody=', requestBody, 'bodyStr=', bodyStr);
            if (!bodyStr || bodyStr === '{}') {
                console.error('❌ requestBody 为空，中止提交');
                addMessage('bot', '❌ 提交数据异常，请重试');
                return;
            }
            
            try {
                const response = await fetch('/api/confirmations/' + sessionId + '/reparse?userId=' + currentUserId, {
                    method: 'POST',
                    headers: headers,
                    body: bodyStr
                });
                const result = await response.json();
                
                if (result.success) {
                    addMessage('bot', '✅ 已修改，请确认');
                    try {
                        const dataResponse = await fetch('/api/confirmations/' + sessionId + '/data?userId=' + currentUserId);
                        const data = await dataResponse.json();
                        // 使用后端返回的 actions，如果没有则使用默认值
                        var defaultActions = [
                            { id: 'confirm', label: '✅ 确认', style: 'primary' },
                            { id: 'edit', label: '✏️ 修改', style: 'warning' },
                            { id: 'cancel', label: '❌ 取消', style: 'danger' }
                        ];
                        var card = {
                            type: 'confirmation_card',
                            message: data.hasConflict === true ? ('⚠️ ' + data.conflictMessage) : '📋 我帮你解析了提醒信息，请确认：',
                            data: data,
                            actions: result.actions && result.actions.length > 0 ? result.actions : defaultActions,
                            timeout: result.timeout || 30,
                            expiresAt: result.expiresAt || new Date(Date.now() + 30000).toISOString()
                        };
                        showConfirmationCard(card);
                    } catch (e) {
                        console.error('重新加载卡片失败', e);
                    }
                } else {
                    addMessage('bot', '❌ ' + result.message);
                }
            } catch (e) {
                addMessage('bot', '❌ 网络错误，请重试');
            }
        }

        function applySuggestion() {
            var row = document.getElementById('suggestedTimeRow');
            var suggestedTime = row.dataset.suggestedTime;
            if (suggestedTime) {
                // 将建议时间填入时间输入框
                var dt = new Date(suggestedTime.replace(' ', 'T'));
                document.getElementById('editTime').value = dt.toISOString().slice(0, 16);
                // 视觉反馈：按钮变色并显示已采用
                var btn = row.querySelector('button');
                btn.textContent = '✅ 已采用';
                btn.classList.remove('success');
                btn.classList.add('primary');
                btn.disabled = true;
            }
        }

        function cancelEditForm(sessionId) {
            if (originalConfirmationCard && originalConfirmationCard.data.sessionId === sessionId) {
                showConfirmationCard(originalConfirmationCard);
            } else {
                var container = document.getElementById('confirmationContainer');
                var html = '<div class="confirmation-card" id="confirmationCard" data-session-id="' + sessionId + '">';
                html += '<div class="card-title">📋 我帮你解析了提醒信息，请确认：</div>';
                html += '<div class="card-info">';
                html += '<div class="info-item"><span class="info-label">优先级</span><span class="info-value">中</span></div>';
                html += '</div>';
                html += '<div class="card-actions">';
                html += '<button class="action-btn primary" onclick="handleConfirmationAction(\'confirm\', \'' + sessionId + '\')">✅ 确认</button>';
                html += '<button class="action-btn warning" onclick="handleConfirmationAction(\'edit\', \'' + sessionId + '\')">✏️ 修改</button>';
                html += '<button class="action-btn danger" onclick="handleConfirmationAction(\'cancel\', \'' + sessionId + '\')">❌ 取消</button>';
                html += '</div>';
                html += '<div class="card-timer" id="timerText">⏳ 5分钟后未确认自动确认</div>';
                html += '</div>';
                container.innerHTML = html;
            }
        }

        document.addEventListener('DOMContentLoaded', function() {
            // 初始化增强模块
            if (window.SoundSystem) { SoundSystem.init(); }
            if (window.NotificationSystem) { NotificationSystem.init(); }

            var now = new Date();
            currentYear = now.getFullYear();
            currentMonth = now.getMonth() + 1;
            var defaultTab = 'chat';
            switchTab(defaultTab);
            rotatePrompt();
            setInterval(rotatePrompt, 5000);
            connectWebSocket();

            // 回车发送消息
            var chatInput = document.getElementById('chatInput');
            if (chatInput) {
                chatInput.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        sendMessage();
                    }
                });
            }

            // 文件上传
            var fileInput = document.getElementById('fileInput');
            if (fileInput) {
                fileInput.addEventListener('change', function(e) {
                    var file = e.target.files[0];
                    if (!file) return;
                    uploadScheduleFile(file);
                    e.target.value = '';
                });
            }

            // 加载聊天历史
            setTimeout(function() {
                loadChatHistory();
                // 恢复待确认会话（刷新后恢复卡片和引导语）
                setTimeout(function() {
                    if (typeof restorePendingSession === 'function') {
                        restorePendingSession();
                    }
                }, 800);
            }, 300);
        });

        function getTimeGreeting() {
            var hour = new Date().getHours();
            if (hour >= 5 && hour < 11) return '早上好！今天有什么需要提醒的吗？';
            if (hour >= 11 && hour < 13) return '中午好！今天有什么需要提醒的吗？';
            if (hour >= 13 && hour < 18) return '下午好！今天有什么需要提醒的吗？';
            if (hour >= 18 && hour < 24) return '晚上好！今天有什么需要提醒的吗？';
            return '凌晨好！今天有什么需要提醒的吗？';
        }

        var prompts = [
            '你可以这样问我：明天下午3点提醒我还书',
            '__TIME_GREETING__',
            '想了解如何使用小助手，可以输入"使用手册"哦',
            '设置个提醒吧，我来帮你记住！',
            '日程日历中点击顶部日期标题可打开日期跳转器',
            '本项目天气相关数据均来自和风天气'
        ];
        var promptIndex = 0;
        function rotatePrompt() {
            var el = document.getElementById('promptText');
            if (el) {
                var prompt = prompts[promptIndex % prompts.length];
                if (prompt === '__TIME_GREETING__') {
                    prompt = getTimeGreeting();
                }
                el.textContent = prompt;
                promptIndex++;
            }
        }

        function switchTab(tabName) {
            document.querySelectorAll('.nav-item').forEach(function(item) {
                item.classList.remove('active');
                if (item.dataset.tab === tabName) {
                    item.classList.add('active');
                }
            });

            document.querySelectorAll('.tab-page').forEach(function(page) {
                page.classList.remove('active');
            });
            var targetPage = document.getElementById('page-' + tabName);
            if (targetPage) {
                targetPage.classList.add('active');
            }

            var titles = {
                'chat': '🍑 生活提醒助手',
                'calendar': '🌷 日程',
                'profile': '🍑 我的'
            };
            document.getElementById('headerTitle').textContent = titles[tabName] || '生活提醒助手';

            if (tabName === 'profile') loadProfile();
            if (tabName === 'calendar') {
                loadProfile();
                bindCalendarListeners();
                renderCalendar();
            }
        }

        async function loadReminders() {
            try {
                const response = await fetch(`/api/reminders?userId=${currentUserId}`);
                const reminders = await response.json();
                
                const list = document.getElementById('reminderList');
                list.innerHTML = '';
                
                reminders.forEach(r => {
                    const li = document.createElement('li');
                    li.className = 'reminder-item';
                    li.innerHTML = `
                        <div>
                            <div class="reminder-title">${r.title}</div>
                            <div class="reminder-time">${r.remindTime} ${r.location ? '· ' + r.location : ''}</div>
                        </div>
                        <div class="reminder-actions">
                            <button class="btn-success" onclick="confirmReminder(${r.id})">确认</button>
                            <button class="btn-danger" onclick="cancelReminder(${r.id})">取消</button>
                        </div>
                    `;
                    list.appendChild(li);
                });
            } catch (e) { console.error(e); }
        }

        async function confirmReminder(id) {
            await fetch(`/api/reminders/${id}/confirm?userId=${currentUserId}`, { method: 'POST' });
            loadReminders();
        }

        async function cancelReminder(id) {
            await fetch(`/api/reminders/${id}?userId=${currentUserId}`, { method: 'DELETE' });
            loadReminders();
        }

        function isAskingLocation(text) {
            var keywords = ['我在哪', '定位', '当前位置', '我在哪里', '位置', '地址', '在哪儿', '在哪'];
            return keywords.some(function(kw) { return text.includes(kw); });
        }

        async function sendChatRequest(text, latitude, longitude) {
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }

                var body = { text: text, userId: currentUserId };
                if (latitude !== undefined && longitude !== undefined) {
                    body.latitude = latitude;
                    body.longitude = longitude;
                }

                const response = await fetch('/api/chat/send', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify(body)
                });

                const result = await response.json();

                if (result.needConfirmation) {
                    // HTTP 响应只负责显示引导语，卡片由 WebSocket 推送
                    handleConfirmResponse(result);
                    return;
                } else if (result.address) {
                    // 位置查询响应：显示地址并更新设置页
                    addMessage('bot', result.message || ('📍 ' + result.address));
                    var valueCityEl = document.getElementById('valueCity');
                    if (valueCityEl) valueCityEl.textContent = result.address;
                    document.getElementById('confirmationContainer').innerHTML = '';
                    renderCalendar();
                } else if (result.success) {
                    addMessage('bot', result.message);
                    document.getElementById('confirmationContainer').innerHTML = '';
                    renderCalendar();
                    if (result.message && (result.message.indexOf('📍') !== -1 || result.message.indexOf('城市') !== -1 || result.message.indexOf('位置') !== -1)) {
                        loadProfile && loadProfile();
                    }
                } else if (result.title) {
                    var msg = '✅ 已解析：' + result.title;
                    if (result.remindTime) {
                        msg += '\n时间：' + result.remindTime;
                    } else {
                        msg += '\n（⚠️ 未识别到时间，请手动指定）';
                    }
                    if (result.location) {
                        msg += '\n地点：' + result.location;
                    }
                    if (result.intentType) {
                        msg += '\n意图：' + result.intentType;
                    }
                    addMessage('bot', msg);

                    if (result.remindTime) {
                        createReminder(result.title, result.remindTime, result.location, result.priority);
                    }
                } else {
                    var errMsg = result.message || '解析失败，请重试';
                    if (errMsg.indexOf('未设置当前位置') !== -1) {
                        errMsg = '😅 你还没有设置当前位置，请在设置页面配置哦～';
                    }
                    addMessage('bot', '❌ ' + errMsg);
                }
            } catch (e) {
                removeTempMessages();
                clearPendingSession();
                addMessage('bot', '❌ 网络错误，请重试', { skipSave: false });
            }
        }

        async function sendMessage() {
            var input = document.getElementById('chatInput');
            var text = input.value.trim();
            if (!text) return;
            lastUserInput = text; // 保存用户输入，用于重试

            // 位置问法检测
            if (isAskingLocation(text)) {
                addMessage('user', text);
                input.value = '';

                if (!navigator.geolocation) {
                    addMessage('bot', '😅 你的浏览器不支持定位功能哦～');
                    await sendChatRequest(text);
                    return;
                }

                navigator.geolocation.getCurrentPosition(
                    async function(position) {
                        var lat = position.coords.latitude;
                        var lng = position.coords.longitude;
                        await sendChatRequest(text, lat, lng);
                    },
                    async function(err) {
                        var msg = '定位失败';
                        if (err.code === 1) msg = '😢 定位权限被拒绝，将使用默认位置';
                        else if (err.code === 2) msg = '⚠️ 无法获取位置，将使用默认位置';
                        else if (err.code === 3) msg = '⚠️ 定位超时，将使用默认位置';
                        addMessage('bot', msg);
                        await sendChatRequest(text);
                    }
                );
                return;
            }

            addMessage('user', text);
            input.value = '';

            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }

                if (getActiveSessionId()) {
                    if (/^(确认|是的|好|ok|OK|嗯|行|可以|确定|是)$/.test(text)) {
                        const response = await fetch('/api/confirmations/' + getActiveSessionId() + '/confirm?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers
                        });
                        const result = await response.json();
                        if (result.success) {
                            addMessage('bot', result.message);
                            document.getElementById('confirmationContainer').innerHTML = '';
                            renderCalendar();
                        } else {
                            addMessage('bot', '❌ ' + result.message);
                        }
                        return;
                    }

                    if (/^(取消|算了|不|不要|否)$/.test(text)) {
                        const response = await fetch('/api/confirmations/' + getActiveSessionId() + '/cancel?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers
                        });
                        const result = await response.json();
                        if (result.success) {
                            addMessage('bot', result.message);
                            document.getElementById('confirmationContainer').innerHTML = '';
                        } else {
                            addMessage('bot', '❌ ' + result.message);
                        }
                        return;
                    }

                    var editKeywords = ['修改', '改成', '改为', '调整为', '变更为', '换成', '改一下', '调整'];
                    var isEditIntent = editKeywords.some(function(keyword) {
                        return text.includes(keyword);
                    });

                    if (isEditIntent) {
                        // 从当前确认卡片中提取完整数据
                        var currentData = getCurrentCardData();
                        if (!currentData) {
                            addMessage('bot', '❌ 当前没有可修改的确认卡片，请先创建提醒');
                            return;
                        }

                        // 确保必要字段存在
                        if (!currentData.title) {
                            addMessage('bot', '❌ 无法提取卡片数据，请重试');
                            return;
                        }

                        // 调用智能修改接口
                        const editResponse = await fetch('/api/confirmations/' + getActiveSessionId() + '/smart-edit?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers,
                            body: JSON.stringify({
                                userInput: text,
                                currentData: currentData
                            })
                        });
                        const editResult = await editResponse.json();
                        if (editResult.success) {
                            addMessage('bot', editResult.message);
                        } else {
                            addMessage('bot', '❌ ' + editResult.message);
                        }
                        return;
                    }

                    // 非修改指令的输入，直接发送到聊天接口处理
                }

                await sendChatRequest(text);
            } catch (e) {
                addMessage('bot', '❌ 网络错误，请重试');
            }
        }

        async function uploadScheduleFile(file) {
            var formData = new FormData();
            formData.append('file', file);
            formData.append('userId', currentUserId);

            addMessage('user', '📎 上传文件：' + file.name);

            try {
                var token = localStorage.getItem('token');
                var headers = {};
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }

                addMessage('bot', '⏳ 正在解析文件并识别日程...');

                var response = await fetch('/api/schedule/upload', {
                    method: 'POST',
                    headers: headers,
                    body: formData
                });
                var result = await response.json();

                if (result.success) {
                    if (result.needTimeConfig) {
                        // 课表场景：卡片由 WebSocket 推送
                        addMessage('bot', '✅ ' + (result.message || '课表识别完成'));
                        // 5 秒兜底：用 HTTP 响应渲染
                        setTimeout(function() {
                            var container = document.getElementById('confirmationContainer');
                            if (container && container.innerHTML === '') {
                                var items = [];
                                if (result.courses && Array.isArray(result.courses)) {
                                    result.courses.forEach(function(c) {
                                        items.push({
                                            title: c.title,
                                            time: c.dayOfWeek,
                                            location: c.location
                                        });
                                    });
                                }
                                var fallbackCard = {
                                    type: 'schedule_table_confirm',
                                    message: '📚 课表识别完成，请配置时间后确认',
                                    data: {
                                        importId: result.importId || '',
                                        totalCount: result.totalCount || 0,
                                        items: items,
                                        semesterStartDate: result.semesterStartDate
                                    }
                                };
                                showConfirmationCard(fallbackCard);
                            }
                        }, 5000);
                    } else {
                        // 普通日程场景
                        var card = {
                            type: 'confirmation_card',
                            message: '📋 已识别 ' + result.totalCount + ' 个日程，请确认：',
                            data: {
                                isBatch: true,
                                sourceFile: file.name,
                                items: result.items,
                                totalCount: result.totalCount,
                                hasConflict: result.hasConflict,
                                importId: result.importId
                            },
                            actions: [
                                { id: 'confirm_import', label: '✅ 全部创建', style: 'primary' },
                                { id: 'cancel_import', label: '❌ 取消', style: 'danger' }
                            ]
                        };
                        showConfirmationCard(card);
                    }
                } else {
                    addMessage('bot', '❌ 识别失败：' + (result.message || '请重试'));
                }
            } catch (e) {
                addMessage('bot', '❌ 上传失败：网络错误，请重试');
            }
        }

        async function createReminder(title, remindTime, location, priority) {
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }

                const response = await fetch('/api/reminders', {
                    method: 'POST',
                    headers: headers,
                    body: JSON.stringify({
                        userId: currentUserId,
                        title: title,
                        remindTime: remindTime,
                        location: location || '',
                        priority: priority || 'NORMAL',
                        advanceMinutes: 10
                    })
                });
                
                const data = await response.json();
                console.log('📝 createReminder 响应:', data);
                
                if (data.success && data.sessionId) {
                    addMessage('bot', data.message || '⏳ 请确认提醒信息');
                } else if (data.success) {
                    addMessage('bot', '📌 提醒已设置成功！');
                } else {
                    addMessage('bot', '⚠️ 设置失败：' + (data.message || '请重试'));
                }
            } catch (e) {
                addMessage('bot', '⚠️ 设置失败：网络错误');
            }
        }

        function addMessage(role, text, options) {
            const history = document.getElementById('chatHistory');
            if (!history) return;

            // 兼容旧的 skipSave 参数（第三个参数为 boolean 时）
            var opts = {};
            if (typeof options === 'boolean') {
                opts.skipSave = options;
            } else if (typeof options === 'object' && options !== null) {
                opts = options;
            }

            var msgId = opts.replaceId || ('msg-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5));

            // 替换已有消息
            if (opts.replaceId) {
                var existing = document.getElementById(opts.replaceId);
                if (existing) {
                    existing.innerHTML = text.replace(/\n/g, '<br>');
                    existing.classList.remove('fade-in');
                    // 触发重新动画
                    void existing.offsetWidth;
                    existing.classList.add('fade-in');
                    history.scrollTop = history.scrollHeight;
                    if (!opts.skipSave && text && text.trim().length > 0 && currentUserId) {
                        saveMessageToBackend(role, text);
                    }
                    return msgId;
                }
            }

            // 检测是否为网络错误消息，添加重试按钮（按钮在气泡外面）
            if (role === 'bot' && (text.indexOf('网络错误') !== -1 || text.indexOf('请重试') !== -1)) {
                var wrapper = document.createElement('div');
                wrapper.className = 'error-msg-wrapper';

                var msg = document.createElement('div');
                msg.className = 'chat-message ' + role + (opts.className ? ' ' + opts.className : '');
                msg.id = msgId;
                msg.innerHTML = text.replace(/\n/g, '<br>');

                if (opts.isTemp) msg.setAttribute('data-temp', 'true');
                if (opts.groupId) msg.setAttribute('data-group', opts.groupId);

                var btn = document.createElement('button');
                btn.className = 'retry-btn';
                btn.setAttribute('onclick', 'retryLastMessage(\'' + msgId + '\')');
                btn.setAttribute('title', '重新发送');
                btn.innerHTML = '↻ <span class="retry-text">重试</span>';

                wrapper.appendChild(msg);
                wrapper.appendChild(btn);
                history.appendChild(wrapper);
            } else {
                var msg = document.createElement('div');
                msg.className = 'chat-message ' + role + (opts.className ? ' ' + opts.className : '');
                msg.id = msgId;
                msg.innerHTML = text.replace(/\n/g, '<br>');
                if (opts.isTemp) msg.setAttribute('data-temp', 'true');
                if (opts.groupId) msg.setAttribute('data-group', opts.groupId);
                history.appendChild(msg);
            }

            history.scrollTop = history.scrollHeight;

            // Bot 正式回复出现 → 标记状态流气泡已结束（防止被 removeTempMessages 误删）
            if (role === 'bot' && !opts.isTemp && window.AgentStatusFlow) {
                window.AgentStatusFlow.onFinalBotMessageArrived();
            }

            // 非跳过存储场景：异步保存消息到后端
            if (!opts.skipSave && text && text.trim().length > 0 && currentUserId) {
                saveMessageToBackend(role, text);
            }

            return msgId;
        }

        function saveMessageToBackend(role, text) {
            var saveKey = role + '|' + text.length + '|' + text.slice(0, 30) + '|' + Date.now();
            if (pendingSaveKeys.has(saveKey)) return;
            pendingSaveKeys.add(saveKey);
            setTimeout(function() { pendingSaveKeys.delete(saveKey); }, 5000);

            fetch('/api/chat/save-message', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUserId,
                    role: role,
                    content: text.trim()
                })
            }).catch(function(err) {
                // 静默失败
            });
        }

        // 清理所有临时消息（data-temp="true"）
        function removeTempMessages() {
            var history = document.getElementById('chatHistory');
            if (!history) return;
            // 排除 AgentStatusFlow 气泡（.as-flow-bubble），它们有独立的生命周期管理
            var tempMsgs = history.querySelectorAll('[data-temp="true"]:not(.as-flow-bubble)');
            tempMsgs.forEach(function(el) {
                // 也不清理 as-flow-wrapper（状态流气泡的外层 wrapper）
                var wrapper = el.closest('.as-flow-wrapper');
                if (wrapper) return;
                wrapper = el.closest('.error-msg-wrapper');
                if (wrapper) {
                    wrapper.remove();
                } else {
                    el.remove();
                }
            });
        }

        // 按 groupId 批量移除消息
        function removeMessagesByGroup(groupId) {
            var history = document.getElementById('chatHistory');
            if (!history || !groupId) return;
            var groupMsgs = history.querySelectorAll('[data-group="' + groupId + '"]');
            groupMsgs.forEach(function(el) {
                var wrapper = el.closest('.error-msg-wrapper');
                if (wrapper) {
                    wrapper.remove();
                } else {
                    el.remove();
                }
            });
        }

        // ============ 会话状态持久化与恢复 ============
        var PENDING_SESSION_KEY = 'remindme_pending_sessions';
        var PENDING_SESSION_TTL = 5 * 60 * 1000; // 5分钟有效期

        // 保存/更新单个待确认会话（支持多卡片）
        function savePendingSession(sessionId, leadMessage, cardData) {
            try {
                var sessions = loadAllPendingSessions();
                var existing = sessions.find(function(s) { return s.sessionId === sessionId; });
                if (existing) {
                    // 更新已有会话
                    if (leadMessage !== null) existing.leadMessage = leadMessage;
                    if (cardData !== null) existing.cardData = cardData;
                    existing.timestamp = Date.now();
                } else {
                    sessions.push({
                        sessionId: sessionId,
                        leadMessage: leadMessage || '',
                        cardData: cardData || null,
                        timestamp: Date.now()
                    });
                }
                sessionStorage.setItem(PENDING_SESSION_KEY, JSON.stringify(sessions));
            } catch (e) {
                console.warn('保存待确认会话失败:', e.message);
            }
        }

        // 加载所有待确认会话（过滤已过期的）
        function loadAllPendingSessions() {
            try {
                var raw = sessionStorage.getItem(PENDING_SESSION_KEY);
                if (!raw) return [];
                var sessions = JSON.parse(raw);
                if (!Array.isArray(sessions)) return [];
                // 过滤已过期的
                var valid = sessions.filter(function(s) {
                    return Date.now() - s.timestamp <= PENDING_SESSION_TTL;
                });
                if (valid.length !== sessions.length) {
                    sessionStorage.setItem(PENDING_SESSION_KEY, JSON.stringify(valid));
                }
                return valid;
            } catch (e) {
                return [];
            }
        }

        // 兼容旧接口：返回第一个会话
        function loadPendingSession() {
            var sessions = loadAllPendingSessions();
            return sessions.length > 0 ? sessions[0] : null;
        }

        // 清理指定会话（或全部）
        function clearPendingSession(sessionId) {
            try {
                if (!sessionId) {
                    sessionStorage.removeItem(PENDING_SESSION_KEY);
                    return;
                }
                var sessions = loadAllPendingSessions();
                var filtered = sessions.filter(function(s) { return s.sessionId !== sessionId; });
                sessionStorage.setItem(PENDING_SESSION_KEY, JSON.stringify(filtered));
            } catch (e) {}
        }

        // 恢复所有待确认会话（多卡片独立恢复）
        function restorePendingSession() {
            var userId = localStorage.getItem('userId');
            if (!userId) return;
            // 先调用后端获取有效 pending 会话列表，只恢复后端确认有效的会话
            // 避免 sessionStorage 堆积已超时/已确认的旧会话导致刷新后冒出无效卡片
            fetch('/api/confirmations/pending?userId=' + userId, { headers: getHeaders() })
                .then(function(res) { return res.json(); })
                .then(function(result) {
                    var validSessionIds = (result && result.sessionIds) ? result.sessionIds : [];
                    var sessions = loadAllPendingSessions();
                    if (!sessions || sessions.length === 0) return;

                    sessions.forEach(function(state) {
                        if (!state.sessionId) return;
                        // 后端已无此会话（超时/确认/取消）→ 清除 sessionStorage 并跳过
                        if (validSessionIds.indexOf(state.sessionId) === -1) {
                            clearPendingSession(state.sessionId);
                            return;
                        }

                        var groupId = 'card-' + state.sessionId;

                        // 恢复引导语（临时消息，检查是否已存在）
                        if (state.leadMessage) {
                            var history = document.getElementById('chatHistory');
                            if (history) {
                                var existingMsgs = history.querySelectorAll('[data-group="' + groupId + '"]');
                                if (existingMsgs.length === 0) {
                                    addMessage('bot', state.leadMessage, { skipSave: true, isTemp: true, groupId: groupId });
                                }
                            }
                        }

                        // 恢复卡片（如果有卡片数据）
                        if (state.cardData) {
                            var cardData = state.cardData;
                            if (typeof cardData === 'object' && !cardData.type) {
                                cardData.type = 'confirmation_card';
                            }
                            showConfirmationCard(cardData);
                        }
                    });
                }).catch(function(err) {
                    console.warn('获取pending会话失败:', err.message);
                });
        }

        // ============ 确认流程增强 ============
        function handleConfirmResponse(result) {
            if (!result) return;

            var leadMsg = result.leadMessage || result.message;
            var sessionId = result.sessionId;
            var groupId = 'card-' + sessionId;

            // 显示引导语为临时消息（确认后自动消失），不保存到后端
            if (leadMsg) {
                addMessage('bot', leadMsg, { skipSave: true, isTemp: true, groupId: groupId });
            }

            // 保存待确认会话状态（仅引导语，卡片数据待 WebSocket 推送后补充）
            if (sessionId) {
                savePendingSession(sessionId, leadMsg || '', null);
            }
        }

        // ============ 最终回复处理 ============
        function handleConfirmFinalResponse(result, confirmed, sessionId) {
            var sid = sessionId || (result && result.sessionId);

            // 去重：如果该会话已完成处理，跳过
            if (sid && processedSessionIds[sid] === 'done') {
                return;
            }
            if (sid) processedSessionIds[sid] = 'done';

            // 清理对应的临时引导语
            if (sid) {
                removeMessagesByGroup('card-' + sid);
            }
            // 兼容：如果没有 sessionId，清理所有临时消息
            if (!sid) {
                removeTempMessages();
            }

            // 清理对应的卡片（优先用 CardSystem.removeCard 移除 wrapper+卡片，避免孤儿）
            if (sid) {
                if (window.CardSystem) {
                    CardSystem.removeCard(sid);
                }
                clearPendingSession(sid);
            } else {
                // 兼容旧调用：清空所有卡片
                removeTempMessages();
                clearPendingSession();
                if (window.CardSystem) CardSystem.clear();
            }

            var defaultMsg = confirmed ? '好的，提醒已确认啦～' : '好的，已取消提醒。';
            var replyMsg = (result && result.message) ? result.message : defaultMsg;

            addMessage('bot', replyMsg, { skipSave: false });
            if (typeof renderCalendar === 'function') renderCalendar();
        }

        /**
         * 加载用户聊天历史消息
         */
        function loadChatHistory() {
            if (chatHistoryLoaded) return;
            if (!currentUserId) return;
            chatHistoryLoaded = true;

            var history = document.getElementById('chatHistory');
            if (history) {
                var loading = document.createElement('div');
                loading.className = 'chat-message bot loading-msg';
                loading.textContent = '加载中...';
                history.appendChild(loading);
            }

            fetch('/api/chat/history?userId=' + currentUserId + '&limit=50')
                .then(function(res) { return res.json(); })
                .then(function(resp) {
                    // 先清空加载提示和默认空状态
                    if (history) {
                        var loadingEl = history.querySelector('.loading-msg');
                        if (loadingEl) loadingEl.remove();
                    }
                    if (!resp || !resp.success || !resp.data || resp.data.length === 0) {
                        return;
                    }
                    // 后端返回倒序（新→旧），前端需正序渲染（旧→新）
                    var list = resp.data.slice().reverse();
                    list.forEach(function(item) {
                        var role = item.role === 'user' ? 'user' : 'bot';
                        addMessage(role, item.content || '', true);
                    });
                })
                .catch(function(err) {
                    // 加载失败：移除加载提示即可，不打扰用户
                    if (history) {
                        var loadingEl = history.querySelector('.loading-msg');
                        if (loadingEl) loadingEl.remove();
                    }
                });
        }

        async function retryLastMessage(errorMsgId) {
            if (!lastUserInput) {
                addMessage('bot', '❌ 没有可重试的消息');
                return;
            }

            // 清除之前的错误消息
            if (errorMsgId) {
                var errorEl = document.getElementById(errorMsgId);
                if (errorEl) errorEl.remove();
            }

            // 重新发送请求
            try {
                var headers = { 'Content-Type': 'application/json' };
                var token = localStorage.getItem('token');
                if (token) {
                    headers['Authorization'] = 'Bearer ' + token;
                }

                if (getActiveSessionId()) {
                    if (/^(确认|是的|好|ok|OK|嗯|行|可以|确定|是)$/.test(lastUserInput)) {
                        const response = await fetch('/api/confirmations/' + getActiveSessionId() + '/confirm?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers
                        });
                        const result = await response.json();
                        if (result.success) {
                            addMessage('bot', result.message);
                            document.getElementById('confirmationContainer').innerHTML = '';
                            renderCalendar();
                        } else {
                            addMessage('bot', '❌ ' + result.message);
                        }
                        return;
                    }

                    if (/^(取消|算了|不|不要|否)$/.test(lastUserInput)) {
                        const response = await fetch('/api/confirmations/' + getActiveSessionId() + '/cancel?userId=' + currentUserId, {
                            method: 'POST',
                            headers: headers
                        });
                        const result = await response.json();
                        if (result.success) {
                            addMessage('bot', result.message);
                            document.getElementById('confirmationContainer').innerHTML = '';
                        } else {
                            addMessage('bot', '❌ ' + result.message);
                        }
                        return;
                    }
                }

                await sendChatRequest(lastUserInput);
            } catch (e) {
                addMessage('bot', '❌ 网络错误，请重试');
            }
        }

        function getTodayStr() {
            var now = new Date();
            var y = now.getFullYear();
            var m = String(now.getMonth() + 1).padStart(2, '0');
            var d = String(now.getDate()).padStart(2, '0');
            return y + '-' + m + '-' + d;
        }

        function isValidDate(dateStr) {
            if (!dateStr || dateStr === 'null' || dateStr === 'undefined') return false;
            var date = new Date(dateStr);
            return !isNaN(date.getTime());
        }

        function renderCalendar() {
            var today = getTodayStr();
            var userId = localStorage.getItem('userId');
            if (!userId) {
                window.location.href = '/login';
                return;
            }

            if (!currentYear || !currentMonth || isNaN(currentYear) || isNaN(currentMonth)) {
                var now = new Date();
                currentYear = now.getFullYear();
                currentMonth = now.getMonth() + 1;
            }

            // 动态切换表头
            var header = document.getElementById('weekdayHeader');
            if (header) {
                if (viewMode === 'week') {
                    header.classList.add('week-header-mode');
                    header.innerHTML = '<div class="week-placeholder"> </div><div class="week-days-wrap"><div class="weekday">一</div><div class="weekday">二</div><div class="weekday">三</div><div class="weekday">四</div><div class="weekday">五</div><div class="weekday">六</div><div class="weekday">日</div></div>';
                } else {
                    header.classList.remove('week-header-mode');
                    header.innerHTML = '<div class="weekday">日</div><div class="weekday">一</div><div class="weekday">二</div><div class="weekday">三</div><div class="weekday">四</div><div class="weekday">五</div><div class="weekday">六</div>';
                }
            }

            if (viewMode === 'month') {
                var firstDay = new Date(currentYear, currentMonth - 1, 1).getDay();
                var daysInMonth = new Date(currentYear, currentMonth, 0).getDate();

                fetch('/api/calendar/month?userId=' + userId + '&year=' + currentYear + '&month=' + currentMonth, {
                    headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
                })
                .then(function(res) { return res.json(); })
                .then(function(data) {
                    var dayInfo = {};
                    if (data && data.days) {
                        data.days.forEach(function(day) {
                            var dayDate = day.date;
                            var dateStr;
                            if (typeof dayDate === 'string') {
                                dateStr = dayDate;
                            } else {
                                dateStr = currentYear + '-' + String(currentMonth).padStart(2, '0') + '-' + String(day.date).padStart(2, '0');
                            }
                            dayInfo[dateStr] = {
                                hasReminder: day.hasReminder,
                                hasRecurring: day.hasRecurring,
                                festival: day.festival,
                                lunarDate: day.lunarDate,
                                periodStatus: day.periodStatus
                            };
                        });
                    }
                    buildCalendarGrid(currentYear, currentMonth, firstDay, daysInMonth, today, dayInfo);
                    loadScheduleData();
                })
                .catch(function() {
                    buildCalendarGrid(currentYear, currentMonth, firstDay, daysInMonth, today, {});
                    loadScheduleData();
                });
            } else {
                var weekRange = getTodayWeekRange(currentWeekOffset);
                var tempDate = new Date(weekRange.monday);
                var dayInfo = {};
                var fetchPromises = [];

                for (var i = 0; i < 7; i++) {
                    var dYear = tempDate.getFullYear();
                    var dMonth = tempDate.getMonth() + 1;
                    var dDay = tempDate.getDate();
                    var dateStr = dYear + '-' + String(dMonth).padStart(2, '0') + '-' + String(dDay).padStart(2, '0');
                    (function(date) {
                        fetchPromises.push(
                            fetch('/api/calendar/day?userId=' + userId + '&date=' + date, {
                                headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
                            })
                            .then(function(res) { return res.json(); })
                            .then(function(data) {
                                if (data && data.days && data.days[0]) {
                                    var d = data.days[0];
                                    dayInfo[date] = {
                                        hasReminder: d.hasReminder,
                                        hasRecurring: d.hasRecurring,
                                        festival: d.festival,
                                        lunarDate: d.lunarDate,
                                        periodStatus: d.periodStatus
                                    };
                                }
                            })
                        );
                    })(dateStr);
                    tempDate.setDate(tempDate.getDate() + 1);
                }

                Promise.all(fetchPromises).then(function() {
                    buildWeekGridWithReminders(today, dayInfo);
                    loadScheduleData();
                }).catch(function() {
                    buildWeekGridWithReminders(today, {});
                    loadScheduleData();
                });
            }
        }

        function buildCalendarGrid(year, month, firstDay, daysInMonth, today, dayInfo) {
            var container = document.getElementById('calendarDays');
            container.innerHTML = '';
            container.classList.remove('week-header-mode');

            for (var i = 0; i < firstDay; i++) {
                var empty = document.createElement('div');
                empty.className = 'day empty';
                container.appendChild(empty);
            }

            for (var d = 1; d <= daysInMonth; d++) {
                var dayDiv = document.createElement('div');
                var dateStr = year + '-' + String(month).padStart(2, '0') + '-' + String(d).padStart(2, '0');
                dayDiv.className = 'day';
                if (dateStr === today) { dayDiv.classList.add('today'); }
                if (dateStr === selectedDate) { dayDiv.classList.add('selected'); }

                var info = dayInfo[dateStr] || {};
                if (info.hasReminder) { dayDiv.classList.add('has-reminder'); }


                var num = document.createElement('span');
                num.className = 'day-number';
                num.textContent = d;
                dayDiv.appendChild(num);

                // 节日（红色）
                if (info.festival) {
                    var festivalEl = document.createElement('span');
                    festivalEl.className = 'day-festival';
                    festivalEl.textContent = info.festival;
                    dayDiv.appendChild(festivalEl);
                } else if (info.lunarDate) {
                    // 农历日期（灰色小字）
                    var lunarEl = document.createElement('span');
                    lunarEl.className = 'day-lunar';
                    lunarEl.textContent = info.lunarDate;
                    dayDiv.appendChild(lunarEl);
                }

                // 经期标记（buildCalendarGrid处）
                if (info.periodStatus) {
                    var periodEl = document.createElement('span');
                    periodEl.className = 'day-period';
                    periodEl.textContent = info.periodStatus === '经期' ? '🌙' : '🌸';
                    dayDiv.appendChild(periodEl);
                }

                // 周期提醒标记
                if (info.hasRecurring) {
                    var recurEl = document.createElement('span');
                    recurEl.className = 'day-recurring';
                    recurEl.textContent = '⟳';
                    recurEl.title = '周期提醒';
                    dayDiv.appendChild(recurEl);
                }

                if (info.hasReminder) {
                    var dot = document.createElement('span');
                    dot.className = 'red-dot';
                    dayDiv.appendChild(dot);
                }

                dayDiv.addEventListener('click', (function(date) {
                    return function() {
                        var validDate = date;
                        if (!isValidDate(date)) {
                            validDate = getTodayStr();
                        }
                        if (selectedDate === validDate) {
                            selectedDate = null;
                            document.querySelectorAll('.day').forEach(function(el) { el.classList.remove('selected'); });
                        } else {
                            selectedDate = validDate;
                            document.querySelectorAll('.day').forEach(function(el) { el.classList.remove('selected'); });
                            this.classList.add('selected');
                        }
                        loadScheduleData();
                    };
                })(dateStr));

                container.appendChild(dayDiv);
            }
            // end of buildCalendarGrid
        }

        function buildWeekGridWithReminders(today, dayInfo) {
            var container = document.getElementById('calendarDays');
            container.innerHTML = '';
            container.classList.add('week-header-mode');

            var weekRange = getTodayWeekRange(currentWeekOffset);

            var mondayYear = weekRange.monday.getFullYear();
            var mondayMonth = weekRange.monday.getMonth() + 1;
            var mondayDay = weekRange.monday.getDate();

            if (isNaN(mondayYear) || isNaN(mondayMonth) || isNaN(mondayDay)) {
                var now = new Date();
                mondayYear = now.getFullYear();
                mondayMonth = now.getMonth() + 1;
                mondayDay = now.getDate();
            }

            // 占位列（与日程表的时间标签对齐）
            var placeholder = document.createElement('div');
            placeholder.className = 'week-placeholder';
            container.appendChild(placeholder);

            // 7天日期容器
            var daysWrap = document.createElement('div');
            daysWrap.className = 'week-days-wrap';

            var currentDate = new Date(mondayYear, mondayMonth - 1, mondayDay);

            for (var i = 0; i < 7; i++) {
                var dYear = currentDate.getFullYear();
                var dMonth = currentDate.getMonth() + 1;
                var dDay = currentDate.getDate();
                var dateStr = dYear + '-' +
                    String(dMonth).padStart(2, '0') + '-' +
                    String(dDay).padStart(2, '0');

                var dayDiv = document.createElement('div');
                dayDiv.className = 'day';
                if (dateStr === today) { dayDiv.classList.add('today'); }
                if (dateStr === selectedDate) { dayDiv.classList.add('selected'); }

                var info = dayInfo[dateStr] || {};
                if (info.hasReminder) { dayDiv.classList.add('has-reminder'); }


                var num = document.createElement('span');
                num.className = 'day-number';
                num.textContent = currentDate.getDate();
                dayDiv.appendChild(num);

                // 节日（红色）
                if (info.festival) {
                    var festivalEl = document.createElement('span');
                    festivalEl.className = 'day-festival';
                    festivalEl.textContent = info.festival;
                    dayDiv.appendChild(festivalEl);
                } else if (info.lunarDate) {
                    // 农历日期（灰色小字）
                    var lunarEl = document.createElement('span');
                    lunarEl.className = 'day-lunar';
                    lunarEl.textContent = info.lunarDate;
                    dayDiv.appendChild(lunarEl);
                }

                // 经期标记
                if (info.periodStatus) {
                    var periodEl = document.createElement('span');
                    periodEl.className = 'day-period';
                    periodEl.textContent = info.periodStatus === '经期' ? '🌙' : '🌸';
                    dayDiv.appendChild(periodEl);
                }

                // 周期提醒标记
                if (info.hasRecurring) {
                    var recurEl = document.createElement('span');
                    recurEl.className = 'day-recurring';
                    recurEl.textContent = '⟳';
                    recurEl.title = '周期提醒';
                    dayDiv.appendChild(recurEl);
                }

                if (info.hasReminder) {
                    var dot = document.createElement('span');
                    dot.className = 'red-dot';
                    dayDiv.appendChild(dot);
                }

                dayDiv.addEventListener('click', (function(date) {
                    return function() {
                        var validDate = date;
                        if (!isValidDate(date)) {
                            validDate = getTodayStr();
                        }
                        if (selectedDate === validDate) {
                            selectedDate = null;
                            document.querySelectorAll('.day').forEach(function(el) { el.classList.remove('selected'); });
                        } else {
                            selectedDate = validDate;
                            document.querySelectorAll('.day').forEach(function(el) { el.classList.remove('selected'); });
                            this.classList.add('selected');
                        }
                        loadScheduleData();
                    };
                })(dateStr));

                daysWrap.appendChild(dayDiv);
                currentDate.setDate(currentDate.getDate() + 1);
            }

            container.appendChild(daysWrap);
        }

        function getWeekDateRange(year, month, offset) {
            var now = new Date();
            var referenceDate;
            
            if (year && month && !isNaN(year) && !isNaN(month)) {
                referenceDate = new Date(year, month - 1, 1);
            } else {
                referenceDate = new Date();
            }
            
            var dayOfWeek = referenceDate.getDay();
            var monday = new Date(referenceDate);
            if (dayOfWeek !== 1) {
                monday.setDate(referenceDate.getDate() - (dayOfWeek === 0 ? 6 : dayOfWeek - 1));
            }
            monday.setDate(monday.getDate() + offset * 7);
            var sunday = new Date(monday);
            sunday.setDate(monday.getDate() + 6);
            return { monday: monday, sunday: sunday };
        }

        function getTodayWeekRange(offset) {
            var now = new Date();
            var dayOfWeek = now.getDay();
            var monday = new Date(now);
            if (dayOfWeek !== 1) {
                monday.setDate(now.getDate() - (dayOfWeek === 0 ? 6 : dayOfWeek - 1));
            }
            monday.setDate(monday.getDate() + offset * 7);
            var sunday = new Date(monday);
            sunday.setDate(monday.getDate() + 6);
            return { monday: monday, sunday: sunday };
        }

        function loadScheduleData() {
            var userId = localStorage.getItem('userId');
            if (!userId) {
                window.location.href = '/login';
                return;
            }

            if (!currentYear || !currentMonth || isNaN(currentYear) || isNaN(currentMonth)) {
                var now = new Date();
                currentYear = now.getFullYear();
                currentMonth = now.getMonth() + 1;
            }

            if (selectedDate && isValidDate(selectedDate)) {
                loadDaySchedule(userId, selectedDate);
            } else if (viewMode === 'month') {
                loadMonthSchedule(userId);
            } else {
                loadWeekSchedule(userId);
            }
        }

        function loadDaySchedule(userId, dateStr) {
            document.getElementById('calendarTitle').textContent = dateStr;

            fetch('/api/calendar/day?userId=' + userId + '&date=' + dateStr, {
                headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                var allReminders = [];
                if (data && data.days && data.days[0] && data.days[0].reminders) {
                    data.days[0].reminders.forEach(function(r) {
                        var dayDate = data.days[0].date;
                        if (dayDate instanceof Date) {
                            var d = new Date(dayDate);
                            r.dateStr = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
                        } else if (typeof dayDate === 'string') {
                            r.dateStr = dayDate;
                        } else {
                            r.dateStr = dateStr;
                        }
                        allReminders.push(r);
                    });
                }
                allScheduleData = allReminders;
                renderDayScheduleList(allReminders);
            })
            .catch(function() {
                allScheduleData = [];
                renderDayScheduleList([]);
            });
        }

        function loadMonthSchedule(userId) {
            document.getElementById('calendarTitle').textContent = currentYear + '年' + currentMonth + '月';

            fetch('/api/calendar/month?userId=' + userId + '&year=' + currentYear + '&month=' + currentMonth, {
                headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                var allReminders = [];
                if (data && data.days) {
                    data.days.forEach(function(day) {
                        if (day.reminders) {
                            day.reminders.forEach(function(r) {
                                var dayDate = day.date;
                                if (dayDate instanceof Date) {
                                    var d = new Date(dayDate);
                                    r.dateStr = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
                                } else if (typeof dayDate === 'string') {
                                    r.dateStr = dayDate;
                                } else {
                                    r.dateStr = currentYear + '-' + String(currentMonth).padStart(2, '0') + '-' + String(day.day).padStart(2, '0');
                                }
                                allReminders.push(r);
                            });
                        }
                    });
                }
                allScheduleData = allReminders;
                renderMonthScheduleList(allReminders);
            })
            .catch(function() {
                allScheduleData = [];
                renderMonthScheduleList([]);
            });
        }

        function loadWeekSchedule(userId) {
            var weekRange = getTodayWeekRange(currentWeekOffset);

            var mondayYear = weekRange.monday.getFullYear();
            var mondayMonth = weekRange.monday.getMonth() + 1;
            var mondayDay = weekRange.monday.getDate();
            var sundayYear = weekRange.sunday.getFullYear();
            var sundayMonth = weekRange.sunday.getMonth() + 1;
            var sundayDay = weekRange.sunday.getDate();

            if (isNaN(mondayYear) || isNaN(mondayMonth) || isNaN(mondayDay)) {
                var now = new Date();
                mondayYear = now.getFullYear();
                mondayMonth = now.getMonth() + 1;
                mondayDay = now.getDate();
                sundayYear = mondayYear;
                sundayMonth = mondayMonth;
                sundayDay = mondayDay + 6;
            }

            var mondayStr = mondayYear + '-' + String(mondayMonth).padStart(2, '0') + '-' + String(mondayDay).padStart(2, '0');
            var sundayStr = sundayYear + '-' + String(sundayMonth).padStart(2, '0') + '-' + String(sundayDay).padStart(2, '0');

            document.getElementById('calendarTitle').textContent = mondayStr + ' ~ ' + sundayStr;

            var allReminders = [];
            var fetchPromises = [];

            var tempDate = new Date(mondayYear, mondayMonth - 1, mondayDay);

            for (var i = 0; i < 7; i++) {
                var dYear = tempDate.getFullYear();
                var dMonth = tempDate.getMonth() + 1;
                var dDay = tempDate.getDate();
                var dateStr = dYear + '-' + String(dMonth).padStart(2, '0') + '-' + String(dDay).padStart(2, '0');
                (function(date) {
                    fetchPromises.push(
                        fetch('/api/calendar/day?userId=' + userId + '&date=' + date, {
                            headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
                        })
                        .then(function(res) { return res.json(); })
                        .then(function(data) {
                            if (data && data.days && data.days[0] && data.days[0].reminders) {
                                data.days[0].reminders.forEach(function(r) {
                                    var dayDate = data.days[0].date;
                                    if (dayDate instanceof Date) {
                                        var d = new Date(dayDate);
                                        r.dateStr = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
                                    } else if (typeof dayDate === 'string') {
                                        r.dateStr = dayDate;
                                    } else {
                                        r.dateStr = date;
                                    }
                                    allReminders.push(r);
                                });
                            }
                        })
                    );
                })(dateStr);
                tempDate.setDate(tempDate.getDate() + 1);
            }

            Promise.all(fetchPromises).then(function() {
                allScheduleData = allReminders;
                renderScheduleList(allReminders);
            });
        }

        function determineBlockType(reminder) {
            // 1. 检查是否是课程
            if (reminder.source === 'COURSE' || reminder.type === 'course') {
                return 'course';
            }
            // 标题包含"课程"关键词作为备用识别
            if (reminder.title && (reminder.title.indexOf('课程') !== -1 ||
                                   reminder.title.indexOf('课') !== -1 ||
                                   reminder.title.indexOf('上课') !== -1)) {
                return 'course';
            }
            // 2. 根据优先级
            var priority = (reminder.priority || 'NORMAL').toUpperCase();
            var map = {
                'URGENT': 'urgent',
                'HIGH': 'high',
                'NORMAL': 'normal',
                'LOW': 'low'
            };
            return map[priority] || 'default';
        }

        function getWeekDays() {
            var weekRange = getTodayWeekRange(currentWeekOffset);
            var monday = weekRange.monday;
            var days = [];
            for (var i = 0; i < 7; i++) {
                var d = new Date(monday);
                d.setDate(monday.getDate() + i);
                var year = d.getFullYear();
                var month = String(d.getMonth() + 1).padStart(2, '0');
                var day = String(d.getDate()).padStart(2, '0');
                days.push(year + '-' + month + '-' + day);
            }
            return days;
        }

        function renderMonthScheduleList(reminders) {
            var items = document.getElementById('scheduleItems');
            items.innerHTML = '';

            if (!reminders || reminders.length === 0) {
                items.innerHTML = '<div class="empty-schedule">该时段暂无日程安排</div>';
                return;
            }

            var sorted = reminders.slice().sort(function(a, b) {
                return (a.remindTime || '').localeCompare(b.remindTime || '');
            });

            var timeline = document.createElement('div');
            timeline.className = 'schedule-timeline';

            // 记录已显示过日期前缀的天，每天只在第一个事件前展示日期
            var shownDates = {};

            sorted.forEach(function(r) {
                var row = document.createElement('div');
                row.className = 'schedule-row';

                var timeLabel = document.createElement('div');
                timeLabel.className = 'schedule-time-label with-date';
                if (r.remindTime) {
                    var datePart = r.remindTime.slice(5, 10); // MM-dd
                    var timePart = r.remindTime.slice(11, 16); // HH:mm
                    var isFirstOfDay = !shownDates[datePart];
                    if (isFirstOfDay) shownDates[datePart] = true;

                    if (r.endTime) {
                        // 每天第一个事件：显示 MM-dd HH:mm-HH:mm；同日后续事件：只显示 HH:mm-HH:mm，日期位留空对齐
                        timeLabel.textContent = isFirstOfDay
                            ? datePart + ' ' + timePart + '-' + r.endTime.slice(11, 16)
                            : '       ' + timePart + '-' + r.endTime.slice(11, 16);
                    } else {
                        timeLabel.textContent = isFirstOfDay
                            ? datePart + ' ' + timePart
                            : '       ' + timePart;
                    }
                } else {
                    timeLabel.textContent = '';
                }
                row.appendChild(timeLabel);

                var blocks = document.createElement('div');
                blocks.className = 'schedule-blocks';

                var block = document.createElement('div');
                var type = determineBlockType(r);
                block.className = 'schedule-block ' + type + ' single';

                if (r.status === 'CANCELLED') {
                    block.classList.add('cancelled');
                }

                var blockText = '';
                if (r.isRecurring) {
                    var repeatLabel = '每天';
                    if (r.repeatType === 'WEEKLY') repeatLabel = '每周';
                    else if (r.repeatType === 'MONTHLY') repeatLabel = '每月';
                    blockText += '<span class="recurring-tag" title="' + (r.recurringRange || '') + '">⟳' + repeatLabel + '</span> ';
                }
                if (type === 'course') {
                    blockText += '📖 ';
                }
                blockText += r.title;
                if (r.isRecurring && r.recurringRange) {
                    blockText += '<div class="recurring-range" title="' + r.recurringRange + '">' + r.recurringRange + '</div>';
                }
                block.innerHTML = blockText;

                block.title = r.title + (r.location ? ' @ ' + r.location : '')
                    + (r.isRecurring && r.recurringRange ? ' · 周期提醒：' + r.recurringRange : '');
                blocks.appendChild(block);

                row.appendChild(blocks);
                timeline.appendChild(row);
            });

            items.appendChild(timeline);
        }

        // 选中具体日期时的日程展示：单行单列，时间标签纯 HH:mm，不带日期前缀
        function renderDayScheduleList(reminders) {
            var items = document.getElementById('scheduleItems');
            items.innerHTML = '';

            if (!reminders || reminders.length === 0) {
                items.innerHTML = '<div class="empty-schedule">该时段暂无日程安排</div>';
                return;
            }

            var sorted = reminders.slice().sort(function(a, b) {
                return (a.remindTime || '').localeCompare(b.remindTime || '');
            });

            var timeline = document.createElement('div');
            timeline.className = 'schedule-timeline';

            sorted.forEach(function(r) {
                var row = document.createElement('div');
                row.className = 'schedule-row';

                var timeLabel = document.createElement('div');
                timeLabel.className = 'schedule-time-label';
                if (r.remindTime) {
                    var timePart = r.remindTime.slice(11, 16);
                    if (r.endTime) {
                        timeLabel.textContent = timePart + '-' + r.endTime.slice(11, 16);
                    } else {
                        timeLabel.textContent = timePart;
                    }
                } else {
                    timeLabel.textContent = '';
                }
                row.appendChild(timeLabel);

                var blocks = document.createElement('div');
                blocks.className = 'schedule-blocks';

                var block = document.createElement('div');
                var type = determineBlockType(r);
                block.className = 'schedule-block ' + type + ' single';

                if (r.status === 'CANCELLED') {
                    block.classList.add('cancelled');
                }

                var blockText = '';
                if (r.isRecurring) {
                    var repeatLabel = '每天';
                    if (r.repeatType === 'WEEKLY') repeatLabel = '每周';
                    else if (r.repeatType === 'MONTHLY') repeatLabel = '每月';
                    blockText += '<span class="recurring-tag" title="' + (r.recurringRange || '') + '">⟳' + repeatLabel + '</span> ';
                }
                if (type === 'course') {
                    blockText += '📖 ';
                }
                blockText += r.title;
                if (r.isRecurring && r.recurringRange) {
                    blockText += '<div class="recurring-range" title="' + r.recurringRange + '">' + r.recurringRange + '</div>';
                }
                block.innerHTML = blockText;

                block.title = r.title + (r.location ? ' @ ' + r.location : '')
                    + (r.isRecurring && r.recurringRange ? ' · 周期提醒：' + r.recurringRange : '');
                blocks.appendChild(block);

                row.appendChild(blocks);
                timeline.appendChild(row);
            });

            items.appendChild(timeline);
        }

        function renderScheduleList(reminders) {
            var items = document.getElementById('scheduleItems');
            items.innerHTML = '';

            if (!reminders || reminders.length === 0) {
                items.innerHTML = '<div class="empty-schedule">该时段暂无日程安排</div>';
                return;
            }

            // 获取本周 7 天的日期（从周一到周日）
            var weekDays = getWeekDays();

            // 按时间+日期分组
            var timeGroups = {};
            reminders.forEach(function(r) {
                var time = r.remindTime.slice(11, 16);
                var dateStr = r.remindTime.slice(0, 10);
                if (!timeGroups[time]) timeGroups[time] = {};
                if (!timeGroups[time][dateStr]) timeGroups[time][dateStr] = [];
                timeGroups[time][dateStr].push(r);
            });

            var sortedTimes = Object.keys(timeGroups).sort();
            var timeline = document.createElement('div');
            timeline.className = 'schedule-timeline';

            sortedTimes.forEach(function(time) {
                var row = document.createElement('div');
                row.className = 'schedule-row';

                // 时间标签：显示时间段（起始-结束），与每日日程一致；结束时间取该时间组内首个带 endTime 的提醒
                var timeLabel = document.createElement('div');
                timeLabel.className = 'schedule-time-label';
                var endTimeStr = '';
                var dayGroups = timeGroups[time] || {};
                for (var ds in dayGroups) {
                    for (var ri = 0; ri < dayGroups[ds].length; ri++) {
                        if (dayGroups[ds][ri].endTime) {
                            endTimeStr = dayGroups[ds][ri].endTime.slice(11, 16);
                            break;
                        }
                    }
                    if (endTimeStr) break;
                }
                timeLabel.textContent = endTimeStr ? (time + '-' + endTimeStr) : time;
                row.appendChild(timeLabel);

                // 7 列对应 7 天
                var blocks = document.createElement('div');
                blocks.className = 'schedule-blocks-week';

                weekDays.forEach(function(dateStr) {
                    var cell = document.createElement('div');
                    cell.className = 'schedule-cell';

                    var reminderList = timeGroups[time] ? timeGroups[time][dateStr] : null;

                    if (reminderList && reminderList.length > 0) {
                        reminderList.forEach(function(reminder) {
                            var block = document.createElement('div');
                            var type = determineBlockType(reminder);
                            block.className = 'schedule-block ' + type + ' week-single';

                            if (reminder.status === 'CANCELLED') {
                                block.classList.add('cancelled');
                            }

                            var weekBlockText = '';
                            if (reminder.isRecurring) {
                                var weekRepeatLabel = '每天';
                                if (reminder.repeatType === 'WEEKLY') weekRepeatLabel = '每周';
                                else if (reminder.repeatType === 'MONTHLY') weekRepeatLabel = '每月';
                                weekBlockText += '<span class="recurring-tag-sm" title="' + (reminder.recurringRange || '') + '">⟳</span>';
                            }
                            if (type === 'course') {
                                weekBlockText += '📖 ';
                            }
                            weekBlockText += reminder.title;
                            block.innerHTML = weekBlockText;

                            var blockTitle = reminder.title;
                            if (reminder.endTime && reminder.remindTime) {
                                blockTitle += ' · ' + reminder.remindTime.slice(11, 16) + '-' + reminder.endTime.slice(11, 16);
                            }
                            if (reminder.location) blockTitle += ' @ ' + reminder.location;
                            if (reminder.isRecurring && reminder.recurringRange) blockTitle += ' · 周期提醒：' + reminder.recurringRange;
                            block.title = blockTitle;
                            cell.appendChild(block);
                        });
                        if (reminderList.length > 1) {
                            cell.classList.add('multi-item');
                        }
                    } else {
                        // 无日程：空白占位
                        var empty = document.createElement('div');
                        empty.className = 'schedule-block empty';
                        cell.appendChild(empty);
                    }

                    blocks.appendChild(cell);
                });

                row.appendChild(blocks);
                timeline.appendChild(row);
            });

            items.appendChild(timeline);
        }

        function switchMode(mode, btn) {
            viewMode = mode;
            currentWeekOffset = 0;
            selectedDate = null;
            document.querySelectorAll('.mode-btn').forEach(function(b) { b.classList.remove('active'); });
            if (btn) btn.classList.add('active');
            renderCalendar();
        }

        function prevPeriod() {
            if (viewMode === 'month') {
                if (currentYear <= DATE_PICKER_MIN_YEAR && currentMonth <= 1) {
                    showToast('已到达最早可查看日期', false);
                    return;
                }
                if (currentMonth === 1) { currentMonth = 12; currentYear--; }
                else { currentMonth--; }
            } else {
                var prevWeekRange = getTodayWeekRange(currentWeekOffset - 1);
                if (prevWeekRange.monday.getFullYear() < DATE_PICKER_MIN_YEAR) {
                    showToast('已到达最早可查看日期', false);
                    return;
                }
                currentWeekOffset--;
            }
            selectedDate = null;
            renderCalendar();
        }

        function nextPeriod() {
            if (viewMode === 'month') {
                if (currentYear >= DATE_PICKER_MAX_YEAR && currentMonth >= 12) {
                    showToast('已到达最晚可查看日期', false);
                    return;
                }
                if (currentMonth === 12) { currentMonth = 1; currentYear++; }
                else { currentMonth++; }
            } else {
                var nextWeekRange = getTodayWeekRange(currentWeekOffset + 1);
                if (nextWeekRange.sunday.getFullYear() > DATE_PICKER_MAX_YEAR) {
                    showToast('已到达最晚可查看日期', false);
                    return;
                }
                currentWeekOffset++;
            }
            selectedDate = null;
            renderCalendar();
        }

        function bindCalendarListeners() {
            document.querySelectorAll('.mode-btn').forEach(function(btn) {
                btn.onclick = function() { switchMode(this.getAttribute('data-mode'), this); };
            });
            document.getElementById('prevBtn').onclick = prevPeriod;
            document.getElementById('nextBtn').onclick = nextPeriod;
        }

        var currentEditField = '';
        var currentEditType = '';

        function loadProfile() {
            var userId = localStorage.getItem('userId');
            if (!userId) return;

            fetch('/api/user/' + userId, {
                headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                document.getElementById('profileNickname').textContent = data.nickname || '用户';
                document.getElementById('valueNickname').textContent = data.nickname || '用户';
                document.getElementById('profilePhone').textContent = data.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2');

                // 加载头像
                if (data.avatar) {
                    updateAvatarDisplay('/uploads/' + data.avatar);
                }

                var genderMap = { 'MALE': '男', 'FEMALE': '女', 'PRIVATE': '不公开' };
                document.getElementById('valueGender').textContent = genderMap[data.gender] || '不公开';

                document.getElementById('valueCity').textContent = data.city || '广州';
                document.getElementById('valueAdvance').textContent = (data.defaultAdvanceMinutes || 10) + '分钟';

                if (data.gender === 'FEMALE') {
                    document.getElementById('periodSettings').style.display = 'block';
                    document.getElementById('periodHelpIcon').style.display = 'inline-flex';
                    document.getElementById('valueCycle').textContent = (data.cycleDays || 28) + '天';
                    document.getElementById('valuePeriodDuration').textContent = (data.periodDuration || 5) + '天';
                    if (data.lastPeriodStart) {
                        document.getElementById('valueLastPeriodStart').textContent = data.lastPeriodStart;
                    } else {
                        document.getElementById('valueLastPeriodStart').textContent = '--';
                    }

                    var toggle = document.getElementById('togglePeriodReminder');
                    if (data.enablePeriodReminder) {
                        toggle.classList.add('active');
                    }

                    loadPeriodStatus(userId);
                }
            });
        }

        /**
         * 触发头像文件选择
         */
        function triggerAvatarUpload() {
            document.getElementById('avatarInput').click();
        }

        /** cropper 实例 */
        var cropper = null;

        /**
         * 选择图片后弹出裁剪窗口
         */
        function uploadAvatar(input) {
            var file = input.files[0];
            if (!file) return;

            // 校验文件大小（5MB）
            if (file.size > 5 * 1024 * 1024) {
                showToast('图片大小不能超过5MB', false);
                input.value = '';
                return;
            }

            // 读取图片到裁剪弹窗
            var reader = new FileReader();
            reader.onload = function(e) {
                var cropImage = document.getElementById('cropImage');
                cropImage.src = e.target.result;

                // 显示裁剪弹窗
                document.getElementById('cropOverlay').classList.add('show');

                // 销毁旧实例，创建新 cropper
                if (cropper) {
                    cropper.destroy();
                    cropper = null;
                }
                // 等图片渲染后再初始化
                setTimeout(function() {
                    cropper = new Cropper(cropImage, {
                        aspectRatio: 1,
                        viewMode: 1,
                        dragMode: 'move',
                        movable: true,
                        zoomable: true,
                        rotatable: false,
                        scalable: false,
                        cropBoxResizable: false,
                        cropBoxMovable: false,
                        background: false,
                        guides: false,
                        center: true,
                        highlight: false
                    });
                }, 100);
            };
            reader.readAsDataURL(file);

            // 重置 input
            input.value = '';
        }

        /**
         * 确认裁剪并上传
         */
        function confirmCrop() {
            if (!cropper) return;

            var canvas = cropper.getCroppedCanvas({ width: 400, height: 400 });
            if (!canvas) {
                showToast('裁剪失败，请重试', false);
                return;
            }

            canvas.toBlob(function(blob) {
                if (!blob) {
                    showToast('图片处理失败，请重试', false);
                    return;
                }

                // 构造上传文件
                var file = new File([blob], 'avatar_' + Date.now() + '.png', { type: 'image/png' });
                var formData = new FormData();
                formData.append('userId', localStorage.getItem('userId'));
                formData.append('file', file);

                var token = localStorage.getItem('token');

                fetch('/api/user/avatar', {
                    method: 'POST',
                    headers: { 'Authorization': 'Bearer ' + token },
                    body: formData
                })
                .then(function(res) { return res.json(); })
                .then(function(data) {
                    if (data.success) {
                        updateAvatarDisplay(data.avatarUrl);
                        showToast('头像更新成功', true);
                        cancelCrop();
                    } else {
                        showToast(data.message || '头像上传失败', false);
                    }
                })
                .catch(function() {
                    showToast('网络错误，请重试', false);
                });
            }, 'image/png');
        }

        /**
         * 取消裁剪，关闭弹窗并销毁 cropper
         */
        function cancelCrop() {
            document.getElementById('cropOverlay').classList.remove('show');
            if (cropper) {
                cropper.destroy();
                cropper = null;
            }
        }

        /**
         * 更新页面上的头像显示
         */
        function updateAvatarDisplay(avatarUrl) {
            var circle = document.getElementById('avatarCircle');
            circle.innerHTML = '<img src="' + avatarUrl + '" alt="头像">';
        }

        function loadPeriodStatus(userId) {
            fetch('/api/calendar/period-status?userId=' + userId, {
                headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') }
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.nextPeriodDate) {
                    document.getElementById('valueNextPeriod').textContent = data.nextPeriodDate;
                }
                if (data.statusName) {
                    document.getElementById('valuePeriodStatus').textContent = data.statusName;
                }
            });
        }

        function showEditModal(field, title) {
            currentEditField = field;
            currentEditType = 'input';
            document.getElementById('modalTitle').textContent = title;
            document.getElementById('modalInput').style.display = 'block';
            document.getElementById('modalOptions').style.display = 'none';
            document.getElementById('modalCityPicker').style.display = 'none';
            document.getElementById('modalDatePicker').style.display = 'none';

            var value = '';
            if (field === 'nickname') value = document.getElementById('valueNickname').textContent;
            else if (field === 'city') value = document.getElementById('valueCity').textContent;
            else if (field === 'cycleDays') value = document.getElementById('valueCycle').textContent.replace('天', '');
            else if (field === 'periodDuration') value = document.getElementById('valuePeriodDuration').textContent.replace('天', '');

            document.getElementById('modalInput').value = value;
            document.getElementById('modalOverlay').classList.add('show');
            document.getElementById('modalInput').focus();
        }

        function showLastPeriodStartModal() {
            currentEditField = 'lastPeriodStart';
            currentEditType = 'date';
            document.getElementById('modalTitle').textContent = '上次经期开始日期';
            document.getElementById('modalInput').style.display = 'none';
            document.getElementById('modalOptions').style.display = 'none';
            document.getElementById('modalCityPicker').style.display = 'none';
            document.getElementById('modalDatePicker').style.display = 'block';

            var dateInput = document.getElementById('modalDateInput');
            // 回显当前值
            var currentVal = document.getElementById('valueLastPeriodStart').textContent.trim();
            if (currentVal && currentVal !== '--') {
                dateInput.value = currentVal;
            } else {
                dateInput.value = '';
            }
            // 限制最大日期为今天
            var today = new Date();
            var todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
            dateInput.max = todayStr;

            document.getElementById('modalOverlay').classList.add('show');
        }

        function showGenderModal() {
            currentEditField = 'gender';
            currentEditType = 'options';
            document.getElementById('modalTitle').textContent = '性别';
            document.getElementById('modalInput').style.display = 'none';
            document.getElementById('modalOptions').style.display = 'flex';
            document.getElementById('modalCityPicker').style.display = 'none';
            document.getElementById('modalDatePicker').style.display = 'none';

            var currentValue = document.getElementById('valueGender').textContent;
            var options = [
                { value: 'FEMALE', label: '女' },
                { value: 'MALE', label: '男' },
                { value: 'PRIVATE', label: '不公开' }
            ];

            var html = '';
            options.forEach(function(opt) {
                var active = (opt.label === currentValue) ? ' active' : '';
                html += '<div class="modal-option' + active + '" data-value="' + opt.value + '" onclick="selectOption(this)">' + opt.label + '</div>';
            });
            document.getElementById('modalOptions').innerHTML = html;
            document.getElementById('modalOverlay').classList.add('show');
        }

        function showAdvanceModal() {
            currentEditField = 'defaultAdvanceMinutes';
            currentEditType = 'options';
            document.getElementById('modalTitle').textContent = '默认提前量';
            document.getElementById('modalInput').style.display = 'none';
            document.getElementById('modalOptions').style.display = 'flex';
            document.getElementById('modalCityPicker').style.display = 'none';
            document.getElementById('modalDatePicker').style.display = 'none';

            var currentValue = parseInt(document.getElementById('valueAdvance').textContent);
            var options = [5, 10, 15, 30, 60];

            var html = '';
            options.forEach(function(opt) {
                var active = (opt === currentValue) ? ' active' : '';
                html += '<div class="modal-option' + active + '" data-value="' + opt + '" onclick="selectOption(this)">' + opt + '分钟</div>';
            });
            document.getElementById('modalOptions').innerHTML = html;
            document.getElementById('modalOverlay').classList.add('show');
        }

        function showCityModal() {
            currentEditField = 'city';
            currentEditType = 'city';
            document.getElementById('modalTitle').textContent = '选择当前位置';
            document.getElementById('modalInput').style.display = 'none';
            document.getElementById('modalOptions').style.display = 'none';
            document.getElementById('modalCityPicker').style.display = 'flex';
            document.getElementById('modalDatePicker').style.display = 'none';

            // 重置选中状态
            selectedCityValue = null;
            var searchInput = document.getElementById('citySearchInput');
            var searchResults = document.getElementById('citySearchResults');
            searchInput.value = '';
            searchResults.innerHTML = '';
            searchResults.classList.remove('show');

            // 回显已保存的城市（作为选中状态）
            var currentCity = document.getElementById('valueCity').textContent.trim();
            if (currentCity && currentCity !== '未设置') {
                searchInput.value = currentCity;
                selectedCityValue = currentCity;
            }

            // 绑定搜索输入事件（防抖 300ms）
            searchInput.oninput = function() {
                var keyword = this.value.trim();
                // 用户手动修改了输入框，清除选中状态
                if (selectedCityValue && this.value !== selectedCityValue) {
                    selectedCityValue = null;
                }
                // 防抖搜索
                if (citySearchTimer) clearTimeout(citySearchTimer);
                if (!keyword) {
                    searchResults.innerHTML = '';
                    searchResults.classList.remove('show');
                    return;
                }
                citySearchTimer = setTimeout(function() {
                    searchCities(keyword);
                }, 300);
            };

            // 点击其他地方收起下拉列表
            searchInput.onblur = function() {
                setTimeout(function() {
                    searchResults.classList.remove('show');
                }, 200);
            };
            searchInput.onfocus = function() {
                if (searchResults.innerHTML) {
                    searchResults.classList.add('show');
                }
            };

            document.getElementById('modalOverlay').classList.add('show');
            // 自动聚焦输入框
            setTimeout(function() { searchInput.focus(); }, 100);
        }

        /**
         * 调用后端城市搜索API
         */
        function searchCities(keyword) {
            var searchResults = document.getElementById('citySearchResults');

            // 显示加载中
            searchResults.innerHTML = '<div class="city-search-loading">🔍 搜索中...</div>';
            searchResults.classList.add('show');

            fetch('/api/city/search?keyword=' + encodeURIComponent(keyword))
                .then(function(resp) { return resp.json(); })
                .then(function(data) {
                    if (!data || data.length === 0) {
                        searchResults.innerHTML = '<div class="city-search-empty">未找到相关城市，请尝试其他关键词</div>';
                        return;
                    }
                    // 渲染搜索结果列表
                    var html = '';
                    data.forEach(function(item) {
                        var displayName = item.displayName || item.name || '';
                        // 保存值：省-市-区 或 省-市 或 直辖市-区
                        var value = displayName;
                        html += '<div class="city-search-item" onclick="selectCity(\'' + escapeQuotes(value) + '\', this)" data-value="' + escapeQuotes(value) + '">' +
                                escapeHtml(displayName) + '</div>';
                    });
                    searchResults.innerHTML = html;
                })
                .catch(function(err) {
                    searchResults.classList.remove('show');
                    showToast('搜索失败，请稍后重试', false);
                });
        }

        /**
         * 选择城市（从下拉列表点击）
         */
        function selectCity(value, el) {
            var searchInput = document.getElementById('citySearchInput');
            var searchResults = document.getElementById('citySearchResults');

            // 填入输入框并设为选中状态
            searchInput.value = value;
            selectedCityValue = value;

            // 高亮选中项
            document.querySelectorAll('.city-search-item').forEach(function(e) { e.classList.remove('selected'); });
            if (el) el.classList.add('selected');

            // 收起下拉列表
            searchResults.classList.remove('show');
        }

        // HTML转义工具函数
        function escapeHtml(str) {
            if (!str) return '';
            return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        }
        function escapeQuotes(str) {
            if (!str) return '';
            return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
        }

        function getLocationInModal() {
            var btn = document.getElementById('getLocationBtn');

            if (!navigator.geolocation) {
                showToast('你的浏览器不支持定位功能', false);
                return;
            }

            btn.disabled = true;
            btn.textContent = '📍 定位中...';

            navigator.geolocation.getCurrentPosition(async function(position) {
                try {
                    var lat = position.coords.latitude;
                    var lng = position.coords.longitude;
                    var token = localStorage.getItem('token');
                    var userId = localStorage.getItem('userId');
                    var headers = { 'Content-Type': 'application/json' };
                    if (token) headers['Authorization'] = 'Bearer ' + token;

                    var resp = await fetch('/api/user/location?userId=' + userId, {
                        method: 'PUT',
                        headers: headers,
                        body: JSON.stringify({ latitude: lat, longitude: lng })
                    });
                    var data = await resp.json();

                    if (data.success && data.address) {
                        // 定位成功：将地址填入搜索框并设为选中状态
                        var searchInput = document.getElementById('citySearchInput');
                        searchInput.value = data.address;
                        selectedCityValue = data.address;
                    } else {
                        showToast('定位失败：' + (data.message || '未知错误'), false);
                    }
                } catch (err) {
                    showToast('请求出错：' + err.message, false);
                } finally {
                    btn.disabled = false;
                    btn.textContent = '📍 获取当前位置';
                }
            }, function(err) {
                var msg = '定位失败';
                if (err.code === 1) msg = '你拒绝了定位权限，请在浏览器设置中允许';
                else if (err.code === 2) msg = '无法获取位置信息，请稍后再试';
                else if (err.code === 3) msg = '定位超时，请稍后再试';
                showToast(msg, false);
                btn.disabled = false;
                btn.textContent = '📍 获取当前位置';
            });
        }

        function selectOption(el) {
            document.querySelectorAll('.modal-option').forEach(function(e) { e.classList.remove('active'); });
            el.classList.add('active');
        }

        function hideModal() {
            document.getElementById('modalOverlay').classList.remove('show');
            document.getElementById('modalCityPicker').style.display = 'none';
        }

        function confirmModal() {
            var token = localStorage.getItem('token');
            var userId = localStorage.getItem('userId');
            var value = '';

            if (currentEditType === 'input') {
                value = document.getElementById('modalInput').value.trim();
                if (!value) return;

                // 月经周期校验：大于0的整数，且大于当前经期持续天数
                if (currentEditField === 'cycleDays') {
                    var cycleNum = parseInt(value, 10);
                    if (isNaN(cycleNum) || String(cycleNum) !== value || cycleNum <= 0) {
                        showToast('月经周期必须为大于0的整数', false);
                        return;
                    }
                    var currentDuration = parseInt(document.getElementById('valuePeriodDuration').textContent.replace('天', ''), 10) || 5;
                    if (cycleNum <= currentDuration) {
                        showToast('月经周期必须大于经期持续天数（' + currentDuration + '天）', false);
                        return;
                    }
                    value = cycleNum;
                }

                // 经期持续天数校验：大于0且小于月经周期的整数
                if (currentEditField === 'periodDuration') {
                    var durNum = parseInt(value, 10);
                    if (isNaN(durNum) || String(durNum) !== value || durNum <= 0) {
                        showToast('经期持续天数必须为大于0的整数', false);
                        return;
                    }
                    var currentCycle = parseInt(document.getElementById('valueCycle').textContent.replace('天', ''), 10) || 28;
                    if (durNum >= currentCycle) {
                        showToast('经期持续天数必须小于月经周期（' + currentCycle + '天）', false);
                        return;
                    }
                    value = durNum;
                }
            } else if (currentEditType === 'date') {
                value = document.getElementById('modalDateInput').value.trim();
                if (!value) {
                    showToast('请选择日期', false);
                    return;
                }
                // 校验不晚于今天
                var today = new Date();
                var todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
                if (value > todayStr) {
                    showToast('上次经期开始日期不能晚于今天', false);
                    return;
                }
            } else if (currentEditType === 'city') {
                // 必须从列表选择城市
                if (!selectedCityValue) {
                    showToast('请从列表中选择城市', false);
                    return;
                }
                value = selectedCityValue;
            } else {
                var selected = document.querySelector('.modal-option.active');
                if (!selected) return;
                // options 类型：根据字段决定是否转数字
                if (currentEditField === 'defaultAdvanceMinutes') {
                    value = parseInt(selected.getAttribute('data-value'), 10);
                } else {
                    value = selected.getAttribute('data-value');
                }
            }

            var updateData = {};
            updateData[currentEditField] = value;

            fetch('/api/user/' + userId, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + token
                },
                body: JSON.stringify(updateData)
            })
            .then(function(response) {
                if (response.ok) {
                    return response.json().then(function(data) {
                        // 经期三个字段：用后端返回的最新值更新显示
                        var u = data.user || {};
                        var periodValFields = ['cycleDays', 'lastPeriodStart', 'periodDuration'];
                        if (periodValFields.indexOf(currentEditField) !== -1) {
                            if (u.cycleDays != null) {
                                document.getElementById('valueCycle').textContent = u.cycleDays + '天';
                            }
                            if (u.periodDuration != null) {
                                document.getElementById('valuePeriodDuration').textContent = u.periodDuration + '天';
                            }
                            document.getElementById('valueLastPeriodStart').textContent = u.lastPeriodStart || '--';
                        } else {
                            updateProfileDisplay(currentEditField, value);
                            if (u.lastPeriodStart != null) {
                                document.getElementById('valueLastPeriodStart').textContent = u.lastPeriodStart;
                            }
                        }
                        hideModal();
                        showToast('修改成功', true);

                        // 经期相关字段改了 → 立刻同步「下次预计经期」和「经期状态」，无需手动刷新
                        var periodFields = ['cycleDays', 'lastPeriodStart', 'periodDuration', 'enablePeriodReminder', 'gender'];
                        if (periodFields.indexOf(currentEditField) !== -1) {
                            refreshPeriodDisplay(data, userId);
                        }
                    });
                } else {
                    // 后端校验失败：显示后端返回的错误消息
                    return response.json().then(function(errData) {
                        var msg = (errData && errData.message) ? errData.message : '修改失败，请重试';
                        showToast(msg, false);
                    }).catch(function() {
                        showToast('修改失败，请重试', false);
                    });
                }
            })
            .catch(function() {
                showToast('网络错误，请重试', false);
            });
        }

        /**
         * 用 PUT /api/user 返回里的 periodStatus 立刻刷新「下次预计经期」和「经期状态」；
         * 响应里没有 periodStatus 就回退到 loadPeriodStatus 重新查询一次。
         * 这样在「我的」页面改了经期周期等字段后，下次预计经期无需刷新页面就能同步更新。
         */
        function refreshPeriodDisplay(respData, userId) {
            var ps = respData && respData.periodStatus;
            if (ps) {
                if (ps.nextPeriodDate) {
                    document.getElementById('valueNextPeriod').textContent = ps.nextPeriodDate;
                }
                if (ps.statusName) {
                    document.getElementById('valuePeriodStatus').textContent = ps.statusName;
                }
            } else {
                loadPeriodStatus(userId);
            }
        }

        function updateProfileDisplay(field, value) {
            if (field === 'nickname') {
                document.getElementById('profileNickname').textContent = value;
                document.getElementById('valueNickname').textContent = value;
            } else if (field === 'gender') {
                var genderMap = { 'MALE': '男', 'FEMALE': '女', 'PRIVATE': '不公开' };
                document.getElementById('valueGender').textContent = genderMap[value] || '不公开';
                if (value === 'FEMALE') {
                    document.getElementById('periodSettings').style.display = 'block';
                    document.getElementById('periodHelpIcon').style.display = 'inline-flex';
                } else {
                    document.getElementById('periodSettings').style.display = 'none';
                    document.getElementById('periodHelpIcon').style.display = 'none';
                }
            } else if (field === 'city') {
                document.getElementById('valueCity').textContent = value;
            } else if (field === 'defaultAdvanceMinutes') {
                document.getElementById('valueAdvance').textContent = value + '分钟';
            } else if (field === 'cycleDays') {
                document.getElementById('valueCycle').textContent = value + '天';
            } else if (field === 'periodDuration') {
                document.getElementById('valuePeriodDuration').textContent = value + '天';
            } else if (field === 'lastPeriodStart') {
                document.getElementById('valueLastPeriodStart').textContent = value || '--';
            }
        }

        function togglePeriodReminder() {
            var toggle = document.getElementById('togglePeriodReminder');
            var enabled = !toggle.classList.contains('active');
            toggle.classList.toggle('active', enabled);

            var token = localStorage.getItem('token');
            var userId = localStorage.getItem('userId');

            fetch('/api/user/' + userId, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + token
                },
                body: JSON.stringify({ enablePeriodReminder: enabled })
            });
        }

        function logout() {
            document.getElementById('logoutModal').classList.add('show');
        }

        function hideLogoutModal() {
            document.getElementById('logoutModal').classList.remove('show');
        }

        function confirmLogout() {
            localStorage.clear();
            window.location.href = '/login';
        }

        // ===== 日期跳转弹窗 =====
        var DATE_PICKER_MIN_YEAR = 1900;
        var DATE_PICKER_MAX_YEAR = 3000;
        var datePickerState = {
            year: 2026,
            month: 7,
            day: 1
        };

        function daysInMonth(year, month) {
            return new Date(year, month, 0).getDate();
        }

        function showJumpDateModal() {
            var now = new Date();
            datePickerState.year = now.getFullYear();
            datePickerState.month = now.getMonth() + 1;
            datePickerState.day = now.getDate();
            renderDatePicker();
            document.getElementById('jumpDateModal').classList.add('show');
        }

        function hideJumpDateModal() {
            document.getElementById('jumpDateModal').classList.remove('show');
        }

        function renderDatePicker() {
            var yearSelect = document.getElementById('pickerYearSelect');
            var monthSelect = document.getElementById('pickerMonthSelect');
            var daySelect = document.getElementById('pickerDaySelect');

            // 年
            var yearHtml = '';
            for(var y = DATE_PICKER_MIN_YEAR; y <= DATE_PICKER_MAX_YEAR; y++) {
                yearHtml += '<option value="' + y + '"' + (y===datePickerState.year?' selected':'') + '>' + y + '</option>';
            }
            yearSelect.innerHTML = yearHtml;
            yearSelect.value = datePickerState.year;

            // 月
            var monthHtml = '';
            for(var m = 1; m <= 12; m++) {
                monthHtml += '<option value="' + m + '"' + (m===datePickerState.month?' selected':'') + '>' + m + '月</option>';
            }
            monthSelect.innerHTML = monthHtml;
            monthSelect.value = datePickerState.month;

            // 日
            var maxDay = getMaxDay();
            if(datePickerState.day > maxDay) datePickerState.day = maxDay;
            var dayHtml = '';
            for(var d = 1; d <= maxDay; d++) {
                dayHtml += '<option value="' + d + '"' + (d===datePickerState.day?' selected':'') + '>' + d + '日</option>';
            }
            daySelect.innerHTML = dayHtml;
            daySelect.value = datePickerState.day;
        }

        function getMaxDay() {
            return daysInMonth(datePickerState.year, datePickerState.month);
        }

        function onYearChange() {
            datePickerState.year = parseInt(document.getElementById('pickerYearSelect').value);
            renderDatePicker();
        }

        function onMonthChange() {
            datePickerState.month = parseInt(document.getElementById('pickerMonthSelect').value);
            var maxDay = getMaxDay();
            if(datePickerState.day > maxDay) {
                datePickerState.day = maxDay;
            }
            renderDatePicker();
        }

        function confirmJumpDate() {
            datePickerState.year = parseInt(document.getElementById('pickerYearSelect').value);
            datePickerState.month = parseInt(document.getElementById('pickerMonthSelect').value);
            datePickerState.day = parseInt(document.getElementById('pickerDaySelect').value);

            jumpToDate(datePickerState.year, datePickerState.month, datePickerState.day);
        }

        function jumpToDate(y, m, d) {
            // 更新当前视图
            currentYear = y;
            currentMonth = m;
            selectedDate = y + '-' + String(m).padStart(2,'0') + '-' + String(d).padStart(2,'0');

            hideJumpDateModal();

            // 刷新视图
            var userId = localStorage.getItem('userId');
            renderCalendar();
            if(viewMode === 'week') {
                loadWeekSchedule(userId);
            } else {
                loadMonthSchedule(userId);
            }
            loadDaySchedule(userId);
        }

        // 绑定日历标题点击
        document.getElementById('calendarTitle').addEventListener('click', showJumpDateModal);

        // ====================================================================
        // AgentStatusFlow - Agent 状态流气泡渲染与生命周期管理
        // 功能：
        //   1) 收到 START_THINKING 时创建「状态流气泡」
        //   2) 按步骤序号追加 THINKING / CALLING_* / TOOL_RESULT / GENERATING_REPLY 行
        //   3) 终态 DONE：折叠为摘要行（如「3 步 · 2.4 秒」）；点击可展开回看
        //   4) 终态 ERROR：保留最后一条，显示错误文案；高亮红色
        //   5) 300ms 跳过阈值：若从 START 到 DONE 时长 < 300ms，直接销毁气泡不展示
        //   6) 最终回复（普通 TEXT 消息）到达后，把状态流气泡标记为「已结束」
        // ====================================================================
        (function() {
            var STATUS_DISPLAY_MAP = {
                START_THINKING:     { icon: '🧠', label: '开始处理请求' },
                THINKING:           { icon: '💭', label: '正在理解意图' },
                GENERATING_REPLY:   { icon: '✍️', label: '正在生成回复' },
                DONE:               { icon: '✨', label: '处理完成' },
                CALLING_WEATHER:            { icon: '🌤️', label: '查询天气' },
                CALLING_REMINDER_CREATE:    { icon: '📝', label: '创建提醒' },
                CALLING_REMINDER_QUERY:     { icon: '📋', label: '查询提醒' },
                CALLING_REMINDER_CANCEL:    { icon: '🗑️', label: '取消提醒' },
                CALLING_REMINDER_UPDATE:    { icon: '✏️', label: '修改提醒' },
                CALLING_RECURRING_CREATE:   { icon: '📅', label: '创建周期提醒' },
                CALLING_MEMORY_RETRIEVE:    { icon: '🔍', label: '检索个人记忆' },
                CALLING_MEMORY_ADD:         { icon: '🧺', label: '添加个人记忆' },
                CALLING_MEMORY_UPDATE:      { icon: '🔧', label: '更新个人记忆' },
                CALLING_MEMORY_DELETE:      { icon: '🧹', label: '删除个人记忆' },
                CALLING_PREFERENCES_GET:    { icon: '⚙️', label: '读取用户偏好' },
                CALLING_PREFERENCES_SET:    { icon: '💾', label: '保存用户偏好' },
                CALLING_LOCATION:           { icon: '📍', label: '解析位置信息' },
                CALLING_TIME:               { icon: '🕒', label: '获取当前时间' },
                CALLING_DATE_CALC:          { icon: '📆', label: '日期计算' },
                CALLING_SCHEDULE_IMPORT:    { icon: '📚', label: '导入课表' },
                CALLING_PERIOD_STATUS:         { icon: '🌸', label: '查询经期状态' },
                CALLING_PERIOD_NEXT:           { icon: '🌷', label: '预测下次经期' },
                CALLING_PERIOD_UPDATE_START:   { icon: '🌹', label: '记录经期开始' },
                CALLING_PERIOD_UPDATE_SETTINGS:{ icon: '🌺', label: '更新经期设置' },
                CALLING_TOOL:       { icon: '🔧', label: '执行工具' },
                TOOL_RESULT:        { icon: '✅', label: '工具执行完成' },
                ERROR:              { icon: '⚠️', label: '处理失败' }
            };

            var SKIP_THRESHOLD_MS = 300; // 小于 300ms 视为秒回，不展示状态流

            // 单个 flow 的运行状态
            var activeFlow = null;
            // flowId -> msgId（气泡 DOM 的 id，也用于折叠/展开查找）
            var flowMsgMap = {};

            // 返回 STATUS 的显示配置；找不到则走通用兜底
            function getDisplay(status) {
                return STATUS_DISPLAY_MAP[status] || { icon: '⚙️', label: status || '处理中' };
            }

            // 构造单个步骤的 HTML 行
            function buildStepRow(stepIdx, status, extra, isTerminal) {
                var disp = getDisplay(status);
                var icon = disp.icon;
                var label = disp.label;
                var isStepFailed = false;
                // 工具名优先使用 extra.toolCnName（中文）
                if (status === 'CALLING_TOOL' || status === 'TOOL_RESULT') {
                    if (extra && extra.toolCnName) {
                        // 解析 detail 判断成功/失败
                        isStepFailed = extra.detail && typeof extra.detail === 'string' && extra.detail.indexOf('失败') === 0;
                        if (status === 'TOOL_RESULT') {
                            icon = isStepFailed ? '❌' : '✅';
                            label = extra.toolCnName + (isStepFailed ? ' 失败' : ' 完成');
                        } else {
                            label = extra.toolCnName;
                        }
                    }
                }
                // 对 CALLING_* / TOOL_RESULT：附加 detail 详情
                var detailHtml = '';
                if (extra && extra.detail) {
                    detailHtml = '<div class="as-step-detail">' + escapeHtml(extra.detail) + '</div>';
                }
                // ERROR：附加 message
                if (status === 'ERROR' && extra && extra.message) {
                    detailHtml = '<div class="as-step-detail as-error-detail">' + escapeHtml(extra.message) + '</div>';
                }
                // 终态 TOOL_RESULT / DONE：右侧耗时显示
                var durHtml = '';
                if ((status === 'TOOL_RESULT' || status === 'DONE' || status === 'ERROR') && extra && extra.durationMs != null) {
                    var ms = Number(extra.durationMs);
                    if (!isNaN(ms) && ms >= 0) {
                        durHtml = '<span class="as-step-duration">' + formatDuration(ms) + '</span>';
                    }
                }
                var cls = 'as-step-row' + (isTerminal ? ' as-step-done' : ' as-step-pending');
                if (status === 'ERROR' || isStepFailed) cls += ' as-step-error';
                // 用 data-step 便于按顺序重排（其实事件本身已有序，但保留方便扩展）
                return '<div class="' + cls + '" data-step="' + stepIdx + '">' +
                    '<span class="as-step-icon">' + icon + '</span>' +
                    '<div class="as-step-body">' +
                        '<div class="as-step-label-row">' +
                            '<span class="as-step-label">' + escapeHtml(label) + '</span>' +
                            durHtml +
                        '</div>' +
                        detailHtml +
                    '</div>' +
                    '<span class="as-step-check">' + (isTerminal ? (status === 'ERROR' || isStepFailed ? '✕' : '✓') : '') + '</span>' +
                '</div>';
            }

            function formatDuration(ms) {
                if (ms < 1000) return ms + ' ms';
                var s = Math.round(ms / 100) / 10;
                return s + ' 秒';
            }

            function escapeHtml(s) {
                if (s == null) return '';
                return String(s).replace(/[&<>"']/g, function(c) {
                    return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]);
                });
            }

            // 在聊天区创建状态流气泡（作为 bot 临时消息气泡，但带特殊 class）
            function createFlowBubble(flowId) {
                var history = document.getElementById('chatHistory');
                if (!history) return null;
                var msgId = 'flow-' + flowId + '-' + Date.now();
                var wrapper = document.createElement('div');
                wrapper.className = 'error-msg-wrapper as-flow-wrapper';
                wrapper.id = 'wrap-' + msgId;

                var msg = document.createElement('div');
                msg.className = 'chat-message bot as-flow-bubble';
                msg.id = msgId;
                msg.setAttribute('data-temp', 'true');
                msg.setAttribute('data-flow-id', flowId);

                // 头部：标题 + 最终摘要（折叠模式）
                var html = '';
                html += '<div class="as-flow-header" data-flow-toggle="' + flowId + '">';
                html += '  <span class="as-flow-title">✨ 正在为你处理</span>';
                html += '  <span class="as-flow-summary" style="display:none;"></span>';
                html += '  <span class="as-flow-toggle">▼</span>';
                html += '</div>';
                html += '<div class="as-flow-steps" id="as-steps-' + flowId + '"></div>';

                msg.innerHTML = html;
                wrapper.appendChild(msg);
                history.appendChild(wrapper);
                history.scrollTop = history.scrollHeight;

                // 点击头部：折叠/展开
                var headerEl = msg.querySelector('.as-flow-header');
                if (headerEl) {
                    headerEl.addEventListener('click', function() {
                        var stepsEl = document.getElementById('as-steps-' + flowId);
                        var summaryEl = msg.querySelector('.as-flow-summary');
                        var toggleEl = msg.querySelector('.as-flow-toggle');
                        if (!stepsEl) return;
                        if (stepsEl.style.display === 'none') {
                            stepsEl.style.display = '';
                            if (summaryEl) summaryEl.style.display = 'none';
                            if (toggleEl) toggleEl.textContent = '▼';
                            msg.classList.remove('as-flow-collapsed');
                        } else {
                            stepsEl.style.display = 'none';
                            if (summaryEl) summaryEl.style.display = '';
                            if (toggleEl) toggleEl.textContent = '▲';
                            msg.classList.add('as-flow-collapsed');
                        }
                    });
                }

                return { msgId: msgId, wrapper: wrapper, msgEl: msg };
            }

            // 更新 / 追加步骤行
            function appendOrUpdateStep(flowId, stepIdx, status, extra, isTerminal) {
                var stepsEl = document.getElementById('as-steps-' + flowId);
                if (!stepsEl) return;
                // 按 stepIdx 查找是否已存在（如 TOOL_RESULT 不需要替换 CALLING，直接追加新行即可）
                var rowHtml = buildStepRow(stepIdx, status, extra, isTerminal);
                stepsEl.insertAdjacentHTML('beforeend', rowHtml);
                // 给最后一行加轻微的进入动画
                var rows = stepsEl.querySelectorAll('.as-step-row');
                if (rows.length > 0) {
                    var last = rows[rows.length - 1];
                    last.classList.add('as-step-enter');
                    setTimeout(function() { last && last.classList.remove('as-step-enter'); }, 350);
                }
                // 如果该 status 是终态（DONE/ERROR/TOOL_RESULT），需要把对应步骤 pending → done
                // 这里简化策略：所有旧 pending 行都视为已完成（因为后端事件不会乱序）
                var pendingRows = stepsEl.querySelectorAll('.as-step-pending');
                // 最后一行保留 pending，除非该事件本身就是终态
                if (isTerminal) {
                    pendingRows.forEach(function(r) { r.classList.remove('as-step-pending'); r.classList.add('as-step-done'); });
                } else {
                    for (var i = 0; i < pendingRows.length - 1; i++) {
                        pendingRows[i].classList.remove('as-step-pending');
                        pendingRows[i].classList.add('as-step-done');
                    }
                }
                // 滚动到底
                var history = document.getElementById('chatHistory');
                if (history) history.scrollTop = history.scrollHeight;
            }

            // 终态处理：生成摘要、自动折叠、若 < 300ms 则销毁
            function handleTerminal(flowId, status, extra) {
                var info = flowMsgMap[flowId];
                if (!info) return;
                var msgEl = info.msgEl;
                var wrapperEl = info.wrapper;

                // 计算总耗时：优先用事件自带 durationMs，否则用 startTs 估算
                var durMs = (extra && extra.durationMs != null) ? Number(extra.durationMs) : (Date.now() - info.startTs);
                var totalSteps = (extra && extra.totalSteps) ? Number(extra.totalSteps) : info.totalStepsSeen;
                if (!totalSteps) {
                    var stepsEl = document.getElementById('as-steps-' + flowId);
                    totalSteps = stepsEl ? stepsEl.querySelectorAll('.as-step-row').length : 0;
                }

                // < 阈值：静默销毁
                if (durMs < SKIP_THRESHOLD_MS && status === 'DONE') {
                    destroyFlow(flowId);
                    return;
                }

                // 更新所有还在 pending 的步骤为 done
                var stepsEl = document.getElementById('as-steps-' + flowId);
                if (stepsEl) {
                    stepsEl.querySelectorAll('.as-step-pending').forEach(function(r) {
                        r.classList.remove('as-step-pending');
                        r.classList.add('as-step-done');
                    });
                }

                // 生成摘要 & 更新标题
                var titleEl = msgEl.querySelector('.as-flow-title');
                var summaryEl = msgEl.querySelector('.as-flow-summary');
                var durStr = formatDuration(durMs);
                if (status === 'DONE') {
                    if (titleEl) titleEl.innerHTML = '✨ 处理完成';
                    if (summaryEl) summaryEl.innerHTML = '<span class="as-summary-text">' +
                        totalSteps + ' 步 · 用时 ' + durStr + '</span>';
                    msgEl.classList.add('as-flow-finished');
                } else if (status === 'ERROR') {
                    if (titleEl) titleEl.innerHTML = '⚠️ 处理失败';
                    if (summaryEl) summaryEl.innerHTML = '<span class="as-summary-text as-summary-error">' +
                        '用时 ' + durStr + ' · 点击查看详情</span>';
                    msgEl.classList.add('as-flow-error');
                }

                // 自动折叠（给用户看到完成后 700ms 再折叠，有确认感）
                setTimeout(function() {
                    if (!flowMsgMap[flowId]) return;
                    if (stepsEl) stepsEl.style.display = 'none';
                    if (summaryEl) summaryEl.style.display = '';
                    var toggleEl = msgEl.querySelector('.as-flow-toggle');
                    if (toggleEl) toggleEl.textContent = '▲';
                    msgEl.classList.add('as-flow-collapsed');
                }, status === 'ERROR' ? 1500 : 700);

                // 不再追踪 activeFlow，但保留 flowMsgMap 引用（后续收到 TEXT 回复会从 chatHistory 移除临时标记）
                if (activeFlow && activeFlow.flowId === flowId) {
                    activeFlow = null;
                }
            }

            // 静默销毁整个气泡（<300ms 秒回场景）
            function destroyFlow(flowId) {
                var info = flowMsgMap[flowId];
                if (!info) return;
                try {
                    if (info.wrapper && info.wrapper.parentNode) {
                        info.wrapper.parentNode.removeChild(info.wrapper);
                    }
                } catch (e) {}
                delete flowMsgMap[flowId];
                if (activeFlow && activeFlow.flowId === flowId) activeFlow = null;
                var history = document.getElementById('chatHistory');
                if (history) history.scrollTop = history.scrollHeight;
            }

            // 对外：收到一条 AGENT_STATUS 事件
            function onStatusEvent(msg) {
                if (!msg || !msg.status) return;
                var flowId = msg.flowId;
                var stepIdx = msg.stepIndex || 0;
                var status = msg.status;
                var extra = msg.extra || {};
                // durationMs 直接从顶层取（终态事件）
                if (msg.durationMs != null) extra.durationMs = msg.durationMs;
                if (msg.totalSteps != null) extra.totalSteps = msg.totalSteps;

                // START_THINKING：创建气泡
                if (status === 'START_THINKING' && !flowMsgMap[flowId]) {
                    var created = createFlowBubble(flowId);
                    if (!created) return;
                    flowMsgMap[flowId] = {
                        flowId: flowId,
                        startTs: Date.now(),
                        msgId: created.msgId,
                        wrapper: created.wrapper,
                        msgEl: created.msgEl,
                        totalStepsSeen: stepIdx
                    };
                    activeFlow = flowMsgMap[flowId];
                    // 追加起始步骤行
                    appendOrUpdateStep(flowId, stepIdx, status, extra, false);
                    return;
                }

                // 非 START 且气泡不存在：忽略（例如来自另一个标签页/过期事件）
                if (!flowMsgMap[flowId]) return;

                // 更新总步骤数估计
                if (flowMsgMap[flowId].totalStepsSeen < stepIdx) {
                    flowMsgMap[flowId].totalStepsSeen = stepIdx;
                }

                var isTerminal = (status === 'DONE' || status === 'ERROR' || status === 'TOOL_RESULT');
                appendOrUpdateStep(flowId, stepIdx, status, extra, isTerminal);

                // 终态
                if (status === 'DONE' || status === 'ERROR') {
                    handleTerminal(flowId, status, extra);
                }
            }

            // 对外：当普通 bot 文本消息出现时，把所有状态流气泡从「临时消息」升级为已结束的占位气泡
            // 这样 removeTempMessages 不会误删；但状态流本身是临时视觉，仍保留显示（用户可折叠）
            function onFinalBotMessageArrived() {
                Object.keys(flowMsgMap).forEach(function(flowId) {
                    var info = flowMsgMap[flowId];
                    if (info && info.msgEl) {
                        info.msgEl.setAttribute('data-temp', 'finalized');
                    }
                });
            }

            // 暴露到全局
            window.AgentStatusFlow = {
                onStatusEvent: onStatusEvent,
                onFinalBotMessageArrived: onFinalBotMessageArrived,
                _destroyFlow: destroyFlow
            };
        })();
