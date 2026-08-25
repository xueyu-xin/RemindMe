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

        // 周期卡片（含 confirm_direct/confirm_skip/overwrite_all 任一）自动补充取消按钮
        var isRecurringCard = card.actions.some(function(a) {
            return a.id === 'confirm_direct' || a.id === 'confirm_skip' || a.id === 'overwrite_all';
        });
        if (isRecurringCard) {
            var hasCancel = card.actions.some(function(a) { return a.id === 'cancel'; });
            if (!hasCancel) {
                card.actions.push({ id: 'cancel', label: '❌ 取消', style: 'danger' });
            }
        }

        // 如果已存在相同 sessionId 的卡片，先移除
        if (cards[sessionId]) {
            removeCard(sessionId);
        }

        // 创建卡片元素：多项事务卡片走专用渲染路径
        var isMulti = card.data && card.data.isMulti === true;
        var cardElement = isMulti ? createMultiCardElement(card) : createCardElement(card);
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

        if (card.data.note) {
            html += '<div class="info-item"><span class="info-label">备注</span><span class="info-value">' + escapeHtml(card.data.note) + '</span></div>';
        }

        html += '<div class="info-item"><span class="info-label">优先级</span><span class="info-value">' + escapeHtml(card.data.priority || 'NORMAL') + '</span></div>';
        html += '</div>';

        // 操作按钮
        // 周期冲突卡片（含 confirm_direct/confirm_skip/overwrite_all）拆分为两排：
        // 第一排：confirm_direct + confirm_skip（2 个，各占一半）
        // 第二排：overwrite_all + edit + cancel（3 个，各占三分之一）
        // 非周期卡片保持原单排 card-actions 布局
        var isRecurringConflictCard = card.actions.some(function(a) {
            return a.id === 'confirm_direct' || a.id === 'confirm_skip' || a.id === 'overwrite_all';
        });

        if (isRecurringConflictCard) {
            // 按 ID 分组：第一排 confirm_direct/confirm_skip，第二排 overwrite_all/edit/cancel
            var firstRowIds = ['confirm_direct', 'confirm_skip'];
            var firstRowActions = [];
            var secondRowActions = [];
            for (var i = 0; i < card.actions.length; i++) {
                if (firstRowIds.indexOf(card.actions[i].id) >= 0) {
                    firstRowActions.push(card.actions[i]);
                } else {
                    secondRowActions.push(card.actions[i]);
                }
            }

            // 第一排
            html += '<div class="card-actions-row">';
            for (var i = 0; i < firstRowActions.length; i++) {
                var action = firstRowActions[i];
                html += '<button class="action-btn ' + (action.style || '') + '" data-action-id="' + action.id + '" data-session-id="' + sessionId + '">' + escapeHtml(action.label) + '</button>';
            }
            html += '</div>';

            // 第二排
            html += '<div class="card-actions-row">';
            for (var i = 0; i < secondRowActions.length; i++) {
                var action = secondRowActions[i];
                html += '<button class="action-btn ' + (action.style || '') + '" data-action-id="' + action.id + '" data-session-id="' + sessionId + '">' + escapeHtml(action.label) + '</button>';
            }
            html += '</div>';
        } else {
            // 非周期卡片：保持原单排布局
            html += '<div class="card-actions">';
            for (var i = 0; i < card.actions.length; i++) {
                var action = card.actions[i];
                html += '<button class="action-btn ' + (action.style || '') + '" data-action-id="' + action.id + '" data-session-id="' + sessionId + '">' + escapeHtml(action.label) + '</button>';
            }
            html += '</div>';
        }

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

        // 禁用所有操作按钮防止重复点击（显示 loading 文案）
        var cardEl = document.getElementById('card-' + sessionId);
        setCardButtonsLoading(cardEl);

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

                // 设置会话标记，防止 WebSocket card_confirmed 重复显示（P0 统一用 CardHelpers）
                if (window.CardHelpers) {
                    window.CardHelpers.markSessionDone(sessionId);
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
                restoreCardButtons(cardEl);
                showError(result.message || '确认失败');
            }
        }).catch(function(err) {
            // 恢复按钮
            restoreCardButtons(cardEl);
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 取消卡片
     */
    function cancel(sessionId, userId) {
        stopCountdown(sessionId);

        // 禁用所有操作按钮防止重复点击（显示 loading 文案）
        var cardEl = document.getElementById('card-' + sessionId);
        setCardButtonsLoading(cardEl);

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

                // 设置会话标记（P0 统一用 CardHelpers）
                if (window.CardHelpers) {
                    window.CardHelpers.markSessionDone(sessionId);
                }

                // 使用全局 addMessage 显示取消回复
                if (typeof window.addMessage === 'function') {
                    window.addMessage('bot', result.message || '❌ 已取消', { skipSave: false });
                } else {
                    addBotMessage(result.message || '❌ 已取消');
                }

                removeCard(sessionId);
            } else {
                restoreCardButtons(cardEl);
                showError(result.message || '取消失败');
            }
        }).catch(function(err) {
            restoreCardButtons(cardEl);
            showError('请求失败: ' + err.message);
        });
    }

    /**
     * 切换编辑模式
     */
    function toggleEditMode(sessionId) {
        stopCountdown(sessionId);
        
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
        var data = card.data;

        // 先构造原始 remindTime（单提醒的完整时间）
        var originalRemindTime = data.remindTime || '';
        if (data.startDate && data.recurringRemindTime) {
            var rtime = data.recurringRemindTime;
            if (rtime.length === 5) rtime += ':00';
            originalRemindTime = data.startDate + ' ' + rtime;
        } else {
            var origMatch = /^[\u4e00-\u9fa5]+\s+(\d{2}:\d{2})\s+\|\s+(\d{4}-\d{2}-\d{2})\s+至/.exec(originalRemindTime);
            if (origMatch) {
                originalRemindTime = origMatch[2] + ' ' + origMatch[1] + ':00';
            }
        }

        // 判断是否是周期提醒（三源合一）
        var isRecurring = false;
        // 1. 优先使用 data.isRecurring（布尔值）
        if (data.isRecurring === true) {
            isRecurring = true;
        }
        // 2. 检查 remindTime 是否包含周期特征字 "至" 或 "共"
        if (!isRecurring) {
            var rt = data.remindTime || '';
            if (rt.indexOf('至') >= 0 || rt.indexOf('共') >= 0) {
                isRecurring = true;
            }
        }
        // 3. 检查 startDate 是否存在且非空
        if (!isRecurring) {
            if (data.startDate !== undefined && data.startDate !== null && data.startDate !== '') {
                isRecurring = true;
            }
        }

        var formHtml = '';
        formHtml += '<div class="rm-card-edit-form">';
        formHtml += '<input type="hidden" class="edit-is-recurring" value="' + String(isRecurring ? '1' : '0') + '">';
        formHtml += '<div class="form-group"><label>标题</label><input type="text" class="edit-title" value="' + escapeHtml(data.title || '') + '"></div>';

        if (!isRecurring) {
            // 普通提醒：datetime-local
            var dtLocalVal = '';
            if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(originalRemindTime)) {
                dtLocalVal = originalRemindTime.replace(' ', 'T').slice(0, 16); // yyyy-MM-ddTHH:mm
            }
            formHtml += '<div class="form-group"><label>时间</label><input type="datetime-local" class="edit-remindTime-single" value="' + escapeHtml(dtLocalVal) + '"></div>';
        } else {
            // 周期提醒：time + 2 * date + repeatType

            // 提取 HH:mm
            var timeHHmm = '';
            if (data.recurringRemindTime) {
                timeHHmm = data.recurringRemindTime.length >= 5
                    ? data.recurringRemindTime.slice(0, 5)
                    : data.recurringRemindTime;
            } else if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(originalRemindTime)) {
                timeHHmm = originalRemindTime.split(' ')[1].slice(0, 5);
            } else {
                var tMatch = /^[\u4e00-\u9fa5]+\s+(\d{2}:\d{2})/.exec(data.remindTime || '');
                if (tMatch) timeHHmm = tMatch[1];
            }

            // 开始日期
            var startDateVal = data.startDate || '';
            if (!startDateVal && /^\d{4}-\d{2}-\d{2} /.test(originalRemindTime)) {
                startDateVal = originalRemindTime.split(' ')[0];
            }
            if (!startDateVal) {
                var sMatch = /^[\u4e00-\u9fa5]+\s+\d{2}:\d{2}\s+\|\s+(\d{4}-\d{2}-\d{2})\s+至/.exec(data.remindTime || '');
                if (sMatch) startDateVal = sMatch[1];
            }

            // 结束日期
            var endDateVal = data.endDate || '';
            if (!endDateVal) {
                var eMatch = /至\s+(\d{4}-\d{2}-\d{2})/.exec(data.remindTime || '');
                if (eMatch) endDateVal = eMatch[1];
            }

            var repeatTypeVal = data.repeatType || 'DAILY';

            formHtml += '<div class="form-group"><label>时间</label><input type="time" class="edit-recurring-time" value="' + escapeHtml(timeHHmm) + '"></div>';
            formHtml += '<div class="form-group"><label>开始日期</label><input type="date" class="edit-recurring-start" value="' + escapeHtml(startDateVal) + '"></div>';
            formHtml += '<div class="form-group"><label>结束日期</label><input type="date" class="edit-recurring-end" value="' + escapeHtml(endDateVal) + '"></div>';
            formHtml += '<div class="form-group"><label>重复类型</label>';
            formHtml += '<select class="edit-recurring-repeat">';
            formHtml += '<option value="DAILY"' + (repeatTypeVal === 'DAILY' ? ' selected' : '') + '>每天</option>';
            formHtml += '<option value="WEEKLY"' + (repeatTypeVal === 'WEEKLY' ? ' selected' : '') + '>每周</option>';
            formHtml += '<option value="MONTHLY"' + (repeatTypeVal === 'MONTHLY' ? ' selected' : '') + '>每月</option>';
            formHtml += '</select></div>';
        }

        formHtml += '<div class="form-group"><label>地点</label><input type="text" class="edit-location" value="' + escapeHtml(data.location || '') + '"></div>';
        formHtml += '<div class="form-group"><label>备注</label><textarea class="edit-note" rows="2">' + escapeHtml(data.note || '') + '</textarea></div>';
        formHtml += '<div class="form-group"><label>优先级</label>';
        formHtml += '<select class="edit-priority">';
        formHtml += '<option value="LOW"' + (data.priority === 'LOW' ? ' selected' : '') + '>低</option>';
        formHtml += '<option value="NORMAL"' + (data.priority === 'NORMAL' ? ' selected' : '') + '>普通</option>';
        formHtml += '<option value="HIGH"' + (data.priority === 'HIGH' ? ' selected' : '') + '>高</option>';
        formHtml += '<option value="URGENT"' + (data.priority === 'URGENT' ? ' selected' : '') + '>紧急</option>';
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

        var form = cardEntry.card.element.querySelector('.rm-card-edit-form');
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

        var flagInput = card.element.querySelector('.edit-is-recurring');
        var isRecurring = flagInput && flagInput.value === '1';

        var titleVal = card.element.querySelector('.edit-title').value;
        var locationVal = card.element.querySelector('.edit-location').value;
        var noteVal = card.element.querySelector('.edit-note').value;
        var priorityVal = card.element.querySelector('.edit-priority').value;

        var editData = {
            title: titleVal,
            location: locationVal,
            note: noteVal,
            priority: priorityVal
        };

        if (!isRecurring) {
            // 普通提醒：datetime-local -> yyyy-MM-dd HH:mm:ss
            var dtInput = card.element.querySelector('.edit-remindTime-single');
            var dtVal = dtInput ? dtInput.value : '';
            editData.remindTime = dtVal ? (dtVal.replace('T', ' ') + ':00') : '';
        } else {
            // 周期提醒：time + 2 * date + repeatType
            var timeInput = card.element.querySelector('.edit-recurring-time');
            var startInput = card.element.querySelector('.edit-recurring-start');
            var endInput = card.element.querySelector('.edit-recurring-end');
            var repeatInput = card.element.querySelector('.edit-recurring-repeat');

            var timeVal = (timeInput && timeInput.value) ? timeInput.value : '';   // HH:mm
            var startVal = (startInput && startInput.value) ? startInput.value : ''; // yyyy-MM-dd
            var endVal = (endInput && endInput.value) ? endInput.value : '';
            var repeatVal = (repeatInput && repeatInput.value) ? repeatInput.value : 'DAILY';

            // recurringRemindTime：补成 HH:mm:ss
            var recurringTimeFull = timeVal;
            if (recurringTimeFull.length === 5) recurringTimeFull += ':00';

            editData.remindTime = (startVal && recurringTimeFull) ? (startVal + ' ' + recurringTimeFull) : '';
            editData.recurringRemindTime = recurringTimeFull;
            editData.startDate = startVal;
            editData.endDate = endVal;
            editData.repeatType = repeatVal;
            editData.isRecurring = true;
        }

        // 兜底读取：即使 isRecurring=false，若 DOM 中存在周期字段且有值也按周期提醒提交
        if (!isRecurring) {
            var domFlagInput = card.element.querySelector('.edit-is-recurring');
            var domTimeInput = card.element.querySelector('.edit-recurring-time');
            var domStartInput = card.element.querySelector('.edit-recurring-start');
            var domEndInput = card.element.querySelector('.edit-recurring-end');
            var domRepeatInput = card.element.querySelector('.edit-recurring-repeat');

            var domFlagVal = domFlagInput ? domFlagInput.value : '';
            var domTimeVal = (domTimeInput && domTimeInput.value) ? domTimeInput.value : '';
            var domStartVal = (domStartInput && domStartInput.value) ? domStartInput.value : '';
            var domEndVal = (domEndInput && domEndInput.value) ? domEndInput.value : '';
            var domRepeatVal = (domRepeatInput && domRepeatInput.value) ? domRepeatInput.value : '';

            var hasRecurringFields = domFlagVal === '1'
                || !!domTimeVal || !!domStartVal || !!domEndVal || !!domRepeatVal;

            if (hasRecurringFields) {
                var dRecurringTimeFull = domTimeVal;
                if (dRecurringTimeFull && dRecurringTimeFull.length === 5) dRecurringTimeFull += ':00';
                if (dRecurringTimeFull) {
                    editData.recurringRemindTime = dRecurringTimeFull;
                    if (domStartVal) {
                        editData.remindTime = domStartVal + ' ' + dRecurringTimeFull;
                    }
                }
                if (domStartVal) editData.startDate = domStartVal;
                if (domEndVal)   editData.endDate = domEndVal;
                if (domRepeatVal) editData.repeatType = domRepeatVal;
                editData.isRecurring = true;
            }
        }

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

        var newElement = (card.data && card.data.isMulti === true)
            ? createMultiCardElement(card) : createCardElement(card);

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

        // 超时自动确认：标记会话已处理（P0 统一用 CardHelpers）
        if (window.CardHelpers) {
            window.CardHelpers.markSessionDone(sessionId);
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
        var endpoint = actionId === 'retry' ? 'reparse'
            : actionId === 'confirm_direct' ? 'recurring/direct'
            : actionId === 'confirm_skip' ? 'recurring/skip'
            : actionId === 'overwrite_all' ? 'recurring/overwrite-all'
            : actionId;

        // 禁用按钮（显示 loading 文案）
        var cardEl = document.getElementById('card-' + sessionId);
        setCardButtonsLoading(cardEl);

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

                    // 终结操作：标记会话已处理（P0 统一用 CardHelpers）
                    if (window.CardHelpers) {
                        window.CardHelpers.markSessionDone(sessionId);
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
                restoreCardButtons(cardEl);
                showError(result.message || '操作失败');
            }
        }).catch(function(err) {
            restoreCardButtons(cardEl);
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

        // 兜底：按 ID 清理可能残留的 wrapper（避免孤儿空气泡）
        var wrapperEl = document.getElementById('cardWrapper-' + sessionId);
        if (wrapperEl) wrapperEl.remove();

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
     * 设置卡片按钮 loading 状态：统一转发给 window.CardHelpers（P0 统一多套体系）
     */
    function setCardButtonsLoading(cardEl) {
        if (window.CardHelpers) {
            window.CardHelpers.setButtonsLoading(cardEl);
        }
    }

    /**
     * 恢复卡片按钮状态：统一转发给 window.CardHelpers（P0 统一多套体系）
     */
    function restoreCardButtons(cardEl) {
        if (window.CardHelpers) {
            window.CardHelpers.restoreButtons(cardEl);
        }
    }

    /**
     * 获取请求头：统一转发给 window.CardHelpers（P0 统一多套体系）
     */
    function getHeaders() {
        if (window.CardHelpers) {
            return window.CardHelpers.getAuthHeaders();
        }
        // 兜底：CardHelpers 未加载时临时构造（极端场景）
        var headers = { 'Content-Type': 'application/json' };
        var token = localStorage.getItem('token');
        if (token) { headers['Authorization'] = 'Bearer ' + token; }
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

    // ===== 多项事务确认卡片渲染 =====

    // 创建多项事务卡片元素：标题 + 统计栏 + 条目列表 + 批量操作按钮
    function createMultiCardElement(card) {
        var sessionId = card.sessionId;
        var div = document.createElement('div');
        div.className = 'confirmation-card multi-confirmation-card';
        div.id = 'card-' + sessionId;
        div.setAttribute('data-session-id', sessionId);
        div.setAttribute('data-multi', '1');

        var items = (card.data && card.data.multiItems) || [];
        var s = card.data || {};

        var html = '';
        html += '<div class="card-title">' + escapeHtml(card.message) + '</div>';

        // 统计栏
        html += '<div class="multi-item-stats">';
        html += '<span>共 ' + (s.totalCount || 0) + ' 项</span>';
        html += '<span>待处理 ' + (s.pendingCount != null ? s.pendingCount : 0) + '</span>';
        html += '<span>已确认 ' + (s.confirmedCount || 0) + '</span>';
        html += '<span>已取消 ' + (s.cancelledCount || 0) + '</span>';
        if (s.conflictCount) {
            html += '<span class="conflict">⚠️冲突 ' + s.conflictCount + '</span>';
        }
        html += '</div>';

        // 条目列表（复用项目已有 card-list 容器类）
        html += '<div class="rm-card-list multi-item-list">';
        html += renderMultiItems(items, sessionId);
        html += '</div>';

        // 批量操作按钮
        var conflictCount = items.filter(function(item) {
            return item.hasConflict === true;
        }).length;
        html += '<div class="card-actions multi-card-actions">';
        html += '<button class="action-btn primary" data-action-id="confirm_selected" data-session-id="' + sessionId + '">✅ 确认选中</button>';
        html += '<button class="action-btn danger" data-action-id="cancel_selected" data-session-id="' + sessionId + '">❌ 取消选中</button>';
        if (conflictCount > 0) {
            html += '<button class="action-btn warning" data-action-id="overwrite_selected" data-session-id="' + sessionId + '">📝 覆盖选中</button>';
        }
        html += '</div>';

        div.innerHTML = html;

        // 绑定批量按钮事件：委托给 main.js 的 handleMultiCardAction
        var batchBtns = div.querySelectorAll('.multi-card-actions .action-btn');
        for (var k = 0; k < batchBtns.length; k++) {
            (function(btn) {
                btn.addEventListener('click', function() {
                    var actionId = btn.getAttribute('data-action-id');
                    var btnSessionId = btn.getAttribute('data-session-id');
                    if (typeof window.handleMultiCardAction === 'function') {
                        window.handleMultiCardAction(actionId, btnSessionId);
                    } else {
                        showError('多项操作处理未就绪');
                    }
                });
            })(batchBtns[k]);
        }

        // 绑定条目级事件（复选框联动、修改按钮、冲突标记）
        bindMultiItemEvents(div, sessionId);

        return div;
    }

    // 渲染条目列表 HTML（复用项目已有 card-list / card-list-item / conflict-select-all / mini-btn 类）
    function renderMultiItems(items, sessionId) {
        var html = '';
        // 全选行（复用项目已有 conflict-select-all 样式）
        html += '<div class="conflict-select-all multi-item-header">';
        html += '<input type="checkbox" class="conflict-checkbox multi-item-select-all" data-session-id="' + sessionId + '">';
        html += '<label>全选</label>';
        html += '</div>';

        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var status = item.status || 'PENDING';
            // 复用 card-list-item 基础样式，叠加多卡片特有修饰类
            var cls = 'rm-card-list-item multi-item-row';
            if (status === 'CONFIRMED') cls += ' confirmed';
            else if (status === 'CANCELLED') cls += ' cancelled';
            else cls += ' pending';
            if (item.hasConflict && status === 'PENDING') cls += ' has-conflict';

            // remindTime 格式 yyyy-MM-dd HH:mm:ss，显示完整时间
            var timeShort = item.remindTime || '';

            html += '<div class="' + cls + '" data-item-id="' + i + '">';
            // 复选框：PENDING 可勾选，其他状态禁用（复用 conflict-checkbox）
            if (status === 'PENDING') {
                html += '<input type="checkbox" class="conflict-checkbox multi-item-checkbox" data-item-id="' + i + '" data-session-id="' + sessionId + '">';
            } else {
                html += '<input type="checkbox" class="conflict-checkbox multi-item-checkbox" disabled>';
            }
            // 序号 + 标题 + 时间 + 地点（复用 item-number / item-title / item-time）
            html += '<span class="item-number multi-item-index">' + (i + 1) + '</span>';
            html += '<span class="item-title multi-item-title">' + escapeHtml(item.title || '') + '</span>';
            html += '<span class="item-time multi-item-time">' + escapeHtml(timeShort) + '</span>';
            if (item.location) {
                html += '<span class="multi-item-location">📍' + escapeHtml(item.location) + '</span>';
            }
            // 状态标记（多卡片特有，保留）
            if (status === 'CONFIRMED') {
                html += '<span class="multi-item-status confirmed">✅已确认</span>';
            } else if (status === 'CANCELLED') {
                html += '<span class="multi-item-status cancelled">❌已取消</span>';
            } else if (item.hasConflict) {
                html += '<span class="multi-item-conflict-badge" data-item-id="' + i + '" data-session-id="' + sessionId + '" title="查看冲突详情">⚠️冲突</span>';
            }
            // 修改按钮：仅 PENDING 显示（复用 mini-btn 基础样式 + 颜色修饰）
            if (status === 'PENDING') {
                html += '<button class="mini-btn multi-item-modify-btn" data-item-id="' + i + '" data-session-id="' + sessionId + '" title="修改">✏️ 修改</button>';
            }
            html += '</div>';
        }
        return html;
    }

    // 绑定条目级事件：全选联动、单条修改、冲突详情
    function bindMultiItemEvents(container, sessionId) {
        // 全选 → 同步所有 PENDING 复选框
        var selectAll = container.querySelector('.multi-item-select-all');
        if (selectAll) {
            selectAll.addEventListener('change', function() {
                var boxes = container.querySelectorAll('.multi-item-checkbox:not(:disabled)');
                for (var i = 0; i < boxes.length; i++) {
                    boxes[i].checked = selectAll.checked;
                }
            });
        }
        // 单个复选框 → 反向同步全选框
        var singleBoxes = container.querySelectorAll('.multi-item-checkbox:not(:disabled)');
        for (var j = 0; j < singleBoxes.length; j++) {
            singleBoxes[j].addEventListener('change', function() {
                if (!selectAll) return;
                var allBoxes = container.querySelectorAll('.multi-item-checkbox:not(:disabled)');
                var allChecked = true;
                for (var k = 0; k < allBoxes.length; k++) {
                    if (!allBoxes[k].checked) { allChecked = false; break; }
                }
                selectAll.checked = allChecked;
            });
        }
        // 单条修改按钮
        var modifyBtns = container.querySelectorAll('.multi-item-modify-btn');
        for (var m = 0; m < modifyBtns.length; m++) {
            (function(btn) {
                btn.addEventListener('click', function() {
                    var itemId = parseInt(btn.getAttribute('data-item-id'), 10);
                    showMultiItemEditForm(sessionId, itemId);
                });
            })(modifyBtns[m]);
        }
        // 冲突标记点击 → 弹窗
        var conflictBadges = container.querySelectorAll('.multi-item-conflict-badge');
        for (var c = 0; c < conflictBadges.length; c++) {
            (function(badge) {
                badge.addEventListener('click', function() {
                    var itemId = parseInt(badge.getAttribute('data-item-id'), 10);
                    showMultiConflictDetail(sessionId, itemId);
                });
            })(conflictBadges[c]);
        }
    }

    // 获取/更新卡片状态（用于批量操作后局部刷新，不重建整个卡片）
    function updateMultiCardStatus(sessionId, newData) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;
        if (newData) cardEntry.card.data = newData;
        var card = cardEntry.card;
        var el = card.element;
        var s = card.data || {};

        // 更新统计栏
        var statsEl = el.querySelector('.multi-item-stats');
        if (statsEl) {
            var statsHtml = '';
            statsHtml += '<span>共 ' + (s.totalCount || 0) + ' 项</span>';
            statsHtml += '<span>待处理 ' + (s.pendingCount != null ? s.pendingCount : 0) + '</span>';
            statsHtml += '<span>已确认 ' + (s.confirmedCount || 0) + '</span>';
            statsHtml += '<span>已取消 ' + (s.cancelledCount || 0) + '</span>';
            if (s.conflictCount) {
                statsHtml += '<span class="conflict">⚠️冲突 ' + s.conflictCount + '</span>';
            }
            statsEl.innerHTML = statsHtml;
        }
        // 重新渲染条目列表并重绑事件
        var listEl = el.querySelector('.multi-item-list');
        if (listEl) {
            listEl.innerHTML = renderMultiItems(card.data.multiItems || [], sessionId);
            bindMultiItemEvents(el, sessionId);
        }
    }

    // 获取冲突详情弹窗
    function showMultiConflictDetail(sessionId, itemIndex) {
        var userId = localStorage.getItem('userId');
        fetch('/api/confirmations/multi/' + sessionId + '/item/' + itemIndex + '/conflict?userId=' + userId, {
            headers: getHeaders()
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success && result.data) {
                renderMultiConflictPopup(sessionId, itemIndex, result.data);
            } else {
                showError(result.message || '获取冲突详情失败');
            }
        }).catch(function(err) {
            showError('请求失败: ' + err.message);
        });
    }

    // 渲染冲突详情弹窗
    function renderMultiConflictPopup(sessionId, itemIndex, detail) {
        var existing = document.getElementById('conflict-detail-overlay-' + sessionId);
        if (existing) existing.remove();

        var overlay = document.createElement('div');
        overlay.className = 'conflict-detail-overlay';
        overlay.id = 'conflict-detail-overlay-' + sessionId;

        var html = '';
        html += '<div class="conflict-detail-content">';
        html += '<div class="conflict-detail-title">⚠️ 时间冲突</div>';
        // 当前事务
        html += '<div class="conflict-detail-item current">';
        html += '<div class="conflict-detail-label">当前事务</div>';
        html += '<div class="conflict-detail-name">' + escapeHtml(detail.currentTitle || '') + '</div>';
        html += '<div class="conflict-detail-time">' + escapeHtml(detail.currentRemindTime || '') + '</div>';
        if (detail.currentLocation) {
            html += '<div class="conflict-detail-loc">📍' + escapeHtml(detail.currentLocation) + '</div>';
        }
        html += '</div>';
        html += '<div class="conflict-detail-arrow">⬆ 冲突 ⬇</div>';
        // 已有提醒
        html += '<div class="conflict-detail-item existing">';
        html += '<div class="conflict-detail-label">已有提醒</div>';
        html += '<div class="conflict-detail-name">' + escapeHtml(detail.conflictTitle || '') + '</div>';
        html += '<div class="conflict-detail-time">' + escapeHtml(detail.conflictRemindTime || '') + '</div>';
        if (detail.conflictLocation) {
            html += '<div class="conflict-detail-loc">📍' + escapeHtml(detail.conflictLocation) + '</div>';
        }
        html += '</div>';
        if (detail.conflictMessage) {
            html += '<div class="conflict-detail-message">' + escapeHtml(detail.conflictMessage) + '</div>';
        }
        // 操作按钮
        html += '<div class="card-actions multi-card-actions">';
        html += '<button class="action-btn warning conflict-overwrite-btn">📝 覆盖</button>';
        html += '<button class="action-btn secondary conflict-modify-btn">✏️ 修改</button>';
        html += '<button class="action-btn secondary conflict-close-btn">关闭</button>';
        html += '</div>';
        html += '</div>';

        overlay.innerHTML = html;
        document.body.appendChild(overlay);

        overlay.querySelector('.conflict-close-btn').addEventListener('click', function() {
            overlay.remove();
        });
        overlay.querySelector('.conflict-modify-btn').addEventListener('click', function() {
            overlay.remove();
            showMultiItemEditForm(sessionId, itemIndex);
        });
        overlay.querySelector('.conflict-overwrite-btn').addEventListener('click', function() {
            overlay.remove();
            // 覆盖单条：直接调用 overwrite_selected 并传入指定条目
            if (typeof window.handleMultiCardAction === 'function') {
                window.handleMultiCardAction('overwrite_selected', sessionId, [itemIndex]);
            }
        });
        // 点击遮罩关闭
        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) overlay.remove();
        });
    }

    // 显示单条事务编辑表单（多项卡片专用，提交到 multi/item/{index} 接口）
    function showMultiItemEditForm(sessionId, itemIndex) {
        var cardEntry = cards[sessionId];
        if (!cardEntry || !cardEntry.card.element) return;
        var card = cardEntry.card;
        var items = (card.data && card.data.multiItems) || [];
        if (itemIndex < 0 || itemIndex >= items.length) return;
        var item = items[itemIndex];

        // 移除已有编辑表单
        var existingForm = card.element.querySelector('.rm-card-edit-form');
        if (existingForm) existingForm.remove();

        // datetime-local 回填：yyyy-MM-dd HH:mm:ss → yyyy-MM-ddTHH:mm
        var dtLocalVal = '';
        if (item.remindTime && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?$/.test(item.remindTime)) {
            dtLocalVal = item.remindTime.replace(' ', 'T').slice(0, 16);
        }

        var formHtml = '';
        formHtml += '<div class="rm-card-edit-form multi-item-edit-form">';
        formHtml += '<div class="form-group"><label>标题</label><input type="text" class="edit-title" value="' + escapeHtml(item.title || '') + '"></div>';
        formHtml += '<div class="form-group"><label>时间</label><input type="datetime-local" class="edit-remindTime-single" value="' + escapeHtml(dtLocalVal) + '"></div>';
        formHtml += '<div class="form-group"><label>地点</label><input type="text" class="edit-location" value="' + escapeHtml(item.location || '') + '"></div>';
        formHtml += '<div class="form-group"><label>备注</label><textarea class="edit-note" rows="2">' + escapeHtml(item.note || '') + '</textarea></div>';
        formHtml += '<div class="form-group"><label>优先级</label>';
        formHtml += '<select class="edit-priority">';
        formHtml += '<option value="LOW"' + (item.priority === 'LOW' ? ' selected' : '') + '>低</option>';
        formHtml += '<option value="NORMAL"' + (item.priority === 'NORMAL' ? ' selected' : '') + '>普通</option>';
        formHtml += '<option value="HIGH"' + (item.priority === 'HIGH' ? ' selected' : '') + '>高</option>';
        formHtml += '</select></div>';
        formHtml += '<div class="form-actions">';
        formHtml += '<button class="action-btn primary multi-edit-submit" data-item-id="' + itemIndex + '">修改</button>';
        formHtml += '<button class="action-btn secondary multi-edit-cancel">取消修改</button>';
        formHtml += '</div>';
        formHtml += '</div>';

        var itemList = card.element.querySelector('.multi-item-list');
        if (itemList) {
            itemList.insertAdjacentHTML('afterend', formHtml);
        }

        var submitBtn = card.element.querySelector('.multi-edit-submit');
        var cancelBtn = card.element.querySelector('.multi-edit-cancel');
        if (submitBtn) {
            submitBtn.addEventListener('click', function() {
                submitMultiItemEdit(sessionId, itemIndex);
            });
        }
        if (cancelBtn) {
            cancelBtn.addEventListener('click', function() {
                var form = card.element.querySelector('.rm-card-edit-form');
                if (form) form.remove();
            });
        }
    }

    // 提交单条事务修改
    function submitMultiItemEdit(sessionId, itemIndex) {
        var cardEntry = cards[sessionId];
        if (!cardEntry) return;
        var card = cardEntry.card;
        var userId = localStorage.getItem('userId');

        var titleVal = card.element.querySelector('.edit-title').value;
        var locationVal = card.element.querySelector('.edit-location').value;
        var noteVal = card.element.querySelector('.edit-note').value;
        var priorityVal = card.element.querySelector('.edit-priority').value;
        var dtInput = card.element.querySelector('.edit-remindTime-single');
        var dtVal = dtInput ? dtInput.value : '';
        var remindTime = dtVal ? (dtVal.replace('T', ' ') + ':00') : '';

        var editData = {
            title: titleVal,
            remindTime: remindTime,
            location: locationVal,
            note: noteVal,
            priority: priorityVal
        };

        fetch('/api/confirmations/multi/' + sessionId + '/item/' + itemIndex + '?userId=' + userId, {
            method: 'PUT',
            headers: getHeaders(),
            body: JSON.stringify(editData)
        }).then(function(res) { return res.json(); })
        .then(function(result) {
            if (result.success) {
                // 移除编辑表单
                var form = card.element.querySelector('.rm-card-edit-form');
                if (form) form.remove();
                // 刷新卡片数据
                fetchCardData(sessionId, userId);
            } else {
                showError(result.message || '修改失败');
            }
        }).catch(function(err) {
            showError('请求失败: ' + err.message);
        });
    }

    // 暴露公共接口
    return {
        init: init,
        show: show,
        clear: clear,
        removeCard: removeCard,
        getCardCount: getCardCount,
        fetchCardData: fetchCardData,
        updateMultiCardStatus: updateMultiCardStatus
    };
})();

// 兼容全局访问
window.CardSystem = CardSystem;
