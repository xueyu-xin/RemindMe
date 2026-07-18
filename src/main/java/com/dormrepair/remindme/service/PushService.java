package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.PushLog;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.PushChannel;
import com.dormrepair.remindme.repository.PushLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket推送服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final SimpMessagingTemplate messagingTemplate;
    private final PushLogRepository pushLogRepository;

    public void pushToUser(Long userId, String message) {
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/topic/reminders",
                message
        );
        log.info("推送消息给用户: userId={}, message={}", userId, message);
    }

    public void broadcast(String message) {
        messagingTemplate.convertAndSend("/topic/public", message);
        log.info("广播消息: {}", message);
    }

    @Transactional
    public void pushReminder(Reminder reminder) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "REMINDER");
        notification.put("id", reminder.getId());
        notification.put("title", reminder.getTitle());
        notification.put("remindTime", reminder.getRemindTime());
        notification.put("location", reminder.getLocation());
        notification.put("priority", reminder.getPriority());

        pushToUser(reminder.getUserId(), notification.toString());

        PushLog pushLog = new PushLog();
        pushLog.setReminderId(reminder.getId());
        pushLog.setUserId(reminder.getUserId());
        pushLog.setPushedAt(LocalDateTime.now());
        pushLog.setUserRead(false);
        pushLog.setPushChannel(PushChannel.WEBSOCKET);

        pushLogRepository.save(pushLog);

        log.info("推送提醒: reminderId={}, userId={}", reminder.getId(), reminder.getUserId());
    }
}
