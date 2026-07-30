/**
 * 通知系统 - 可伸缩通知栏
 * 支持叠加通知、展开/折叠、确认/稍后操作
 * 依赖 SoundSystem（音效系统）
 */
var NotificationSystem = (function() {
    var notifications = [];
    var MAX_NOTIFICATIONS = 10;
    var isExpanded = false;
    var container = null;

    /**
     * 初始化通知栏容器
     */
    function init() {
        if (container) return;

        // 创建通知栏容器
        container = document.createElement('div');
        container.id = 'notificationPanel';
        container.className = 'notification-panel';

        document.body.appendChild(container);

        // 监听外部点击关闭
        document.addEventListener('click', function(e) {
            if (!isExpanded) return;
            if (!container.contains(e.target)) {
                close();
            }
        });

        // 防止容器内点击冒泡
        container.addEventListener('click', function(e) {
            e.stopPropagation();
        });
    }

    /**
     * 添加新通知
     * @param {Object} data - 通知数据
     */
    function add(data) {
        init();

        var notification = {
            id: data.id || Date.now() + '_' + Math.random().toString(36).substr(2, 9),
            title: data.title || '未命名提醒',
            time: data.time || '',
            location: data.location || '',
            remark: data.remark || '',
            priority: data.priority || 'NORMAL',
            reminderId: data.reminderId || null,
            sessionId: data.sessionId || null,
            attempt: data.attempt || 1,
            status: 'pending',
            expired: data.expired || false
        };

        // 添加到队列最前面
        notifications.unshift(notification);

        // 超出时移除最旧的
        if (notifications.length > MAX_NOTIFICATIONS) {
            notifications = notifications.slice(0, MAX_NOTIFICATIONS);
        }

        // 播放音效
        if (window.SoundSystem) {
            SoundSystem.init();
            SoundSystem.play(notification.priority);
        }

        // 更新UI
        render();

        return notification;
    }

    /**
     * 确认通知
     * @param {string} notifId - 通知ID
     */
    function confirm(notifId) {
        var notif = findById(notifId);
        if (!notif) return;

        notif.status = 'confirmed';

        // 调用后端接口：阶段二执行确认（标记提醒为 SENT/CONFIRMED，停止调度器推送）
        if (notif.sessionId) {
            var userId = localStorage.getItem('userId');
            fetch('/api/confirmations/' + notif.sessionId + '/execute/confirm?userId=' + userId, {
                method: 'POST',
                headers: getHeaders()
            }).catch(function(err) {
                console.warn('确认通知接口调用失败:', err.message);
            });
        }

        // 移除通知
        removeById(notifId);
        render();
    }

    /**
     * 稍后提醒通知
     * @param {string} notifId - 通知ID
     */
    function later(notifId) {
        var notif = findById(notifId);
        if (!notif) return;

        notif.status = 'later';

        if (notif.sessionId) {
            var userId = localStorage.getItem('userId');
            fetch('/api/confirmations/' + notif.sessionId + '/execute/later?userId=' + userId, {
                method: 'POST',
                headers: getHeaders()
            }).then(function(response) {
                return response.json();
            }).then(function(result) {
                if (result.success) {
                    console.log('稍后提醒已推迟: ' + result.message);
                } else {
                    console.warn('稍后提醒失败: ' + (result.message || '未知错误'));
                }
            }).catch(function(err) {
                console.warn('稍后提醒接口调用失败:', err.message);
            });
        }

        // 移除通知
        removeById(notifId);
        render();
    }

    /**
     * 切换展开/折叠状态
     */
    function toggle() {
        isExpanded = !isExpanded;
        render();
    }

    /**
     * 强制收起
     */
    function close() {
        isExpanded = false;
        render();
    }

    /**
     * 根据ID查找通知
     */
    function findById(id) {
        for (var i = 0; i < notifications.length; i++) {
            if (notifications[i].id === id) return notifications[i];
        }
        return null;
    }

    /**
     * 根据ID移除通知
     */
    function removeById(id) {
        notifications = notifications.filter(function(n) {
            return n.id !== id;
        });
    }

    /**
     * 获取待处理通知列表
     */
    function getPending() {
        return notifications.filter(function(n) {
            return n.status === 'pending';
        });
    }

    /**
     * 获取请求头（包含认证）
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
     * 渲染通知栏UI
     */
    function render() {
        if (!container) return;

        var pending = getPending();

        if (pending.length === 0) {
            // 无通知时隐藏
            container.style.display = 'none';
            container.innerHTML = '';
            return;
        }

        container.style.display = 'flex';

        if (!isExpanded) {
            // 折叠态
            renderCollapsed(pending);
        } else {
            // 展开态
            renderExpanded(pending);
        }
    }

    /**
     * 渲染折叠态
     */
    function renderCollapsed(pending) {
        var hasUrgent = pending.some(function(n) { return n.priority === 'URGENT'; });

        var html = '';
        html += '<div class="notification-collapsed' + (hasUrgent ? ' urgent' : '') + '" onclick="NotificationSystem.toggle()">';
        html += '<div class="notification-bar-inner">';
        html += '<span class="notification-icon">🔔</span>';
        html += '<span class="notification-count">' + pending.length + '条新提醒</span>';

        // 前两条预览
        if (pending.length >= 1) {
            html += '<span class="notification-preview">· ' + escapeHtml(pending[0].title) + '</span>';
        }
        if (pending.length >= 2) {
            html += '<span class="notification-preview-more"> 等</span>';
        }

        html += '<span class="notification-toggle">展开 ▼</span>';
        html += '</div>';
        html += '</div>';

        container.innerHTML = html;
    }

    /**
     * 渲染展开态
     */
    function renderExpanded(pending) {
        var html = '';

        // 头部
        html += '<div class="notification-header">';
        html += '<span class="notification-title">📢 通知中心</span>';
        html += '<span class="notification-unread">未读 ' + pending.length + '</span>';
        html += '<span class="notification-close" onclick="NotificationSystem.close()">收起 ▲</span>';
        html += '</div>';

        // 通知列表
        html += '<div class="notification-list">';
        for (var i = 0; i < pending.length; i++) {
            html += renderNotificationItem(pending[i]);
        }
        html += '</div>';

        container.innerHTML = html;

        // 绑定事件
        bindNotificationEvents(pending);
    }

    /**
     * 渲染单条通知
     */
    function renderNotificationItem(notif) {
        var escapedTitle = escapeHtml(notif.title);
        var escapedTime = escapeHtml(notif.time);
        var escapedLocation = escapeHtml(notif.location);
        var escapedRemark = escapeHtml(notif.remark);

        var html = '<div class="notification-item' + (notif.expired ? ' expired' : '') + '" data-id="' + notif.id + '">';

        // 图标
        var icon = getPriorityIcon(notif.priority);
        html += '<span class="notification-item-icon">' + icon + '</span>';

        // 内容
        html += '<div class="notification-item-content">';
        html += '<div class="notification-item-title">' + escapedTitle + '</div>';
        html += '<div class="notification-item-meta">';
        if (escapedTime) html += '<span class="notif-time">🕐 ' + escapedTime + '</span>';
        if (escapedLocation) html += '<span class="notif-location">📍 ' + escapedLocation + '</span>';
        if (notif.attempt > 1) html += '<span class="notif-attempt">🔔 第' + notif.attempt + '次提醒</span>';
        html += '</div>';
        if (escapedRemark) {
            html += '<div class="notification-item-remark">' + escapedRemark + '</div>';
        }
        html += '</div>';

        // 操作按钮
        if (!notif.expired) {
            html += '<div class="notification-item-actions">';
            html += '<button class="notif-btn-confirm" data-action="confirm" data-id="' + notif.id + '">我知道了</button>';
            html += '<button class="notif-btn-later" data-action="later" data-id="' + notif.id + '">稍后提醒</button>';
            html += '</div>';
        } else {
            html += '<div class="notification-item-expired">已过期</div>';
        }

        html += '</div>';
        return html;
    }

    /**
     * 绑定通知项事件
     */
    function bindNotificationEvents(pending) {
        var buttons = container.querySelectorAll('[data-action]');
        for (var i = 0; i < buttons.length; i++) {
            (function(btn) {
                btn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    var action = btn.getAttribute('data-action');
                    var id = btn.getAttribute('data-id');

                    if (action === 'confirm') {
                        confirm(id);
                    } else if (action === 'later') {
                        later(id);
                    }
                });
            })(buttons[i]);
        }
    }

    /**
     * 获取优先级图标
     */
    function getPriorityIcon(priority) {
        switch ((priority || 'NORMAL').toUpperCase()) {
            case 'URGENT': return '🚨';
            case 'HIGH': return '⚠️';
            case 'NORMAL': return '🔔';
            case 'LOW': return '🔵';
            default: return '🔔';
        }
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

    // 暴露公共接口
    return {
        init: init,
        add: add,
        confirm: confirm,
        later: later,
        toggle: toggle,
        close: close,
        getPending: getPending
    };
})();

// 兼容全局访问
window.NotificationSystem = NotificationSystem;
