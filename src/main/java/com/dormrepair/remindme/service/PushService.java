package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.PushLog;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.PushChannel;
import com.dormrepair.remindme.repository.PushLogRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

// 提醒消息推送：把提醒通过 WebSocket 投递给用户，并写入推送日志便于后续追踪已读状态
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final WebSocketPushService webSocketPushService;
    private final PushLogRepository pushLogRepository;

    // 通用推送入口，固定走 /queue/reminders 频道
    public void pushToUser(Long userId, Object data) {
        webSocketPushService.pushToUser(userId, "/queue/reminders", data);
    }

    // 推送一条提醒：组装通知体 -> 投递 -> 落库记录推送日志
    @Transactional(rollbackFor = Exception.class)
    public void pushReminder(Reminder reminder) {
        // 组装通知负载，type 字段标识这是一条提醒
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "REMINDER");
        notification.put("id", reminder.getId());
        notification.put("title", reminder.getTitle());
        notification.put("remindTime", reminder.getRemindTime());
        notification.put("location", reminder.getLocation());
        notification.put("priority", reminder.getPriority());

        pushToUser(reminder.getUserId(), notification);

        // 写推送日志，默认未读，频道为 WebSocket
        PushLog pushLog = new PushLog();
        pushLog.setReminderId(reminder.getId());
        pushLog.setUserId(reminder.getUserId());
        pushLog.setPushedAt(TimeUtils.now());
        pushLog.setUserRead(false);
        pushLog.setPushChannel(PushChannel.WEBSOCKET);

        pushLogRepository.save(pushLog);

        log.info("推送提醒: reminderId={}, userId={}", reminder.getId(), reminder.getUserId());
    }
}
