package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ExecutionStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ReminderRepository reminderRepository;
    private final ConfirmationService confirmationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanPendingReminders() {
        log.debug("扫描到期提醒...");

        List<Reminder> pendingReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                .filter(r -> r.getConfirmStatus() == ConfirmStatus.CONFIRMED)
                .filter(r -> r.getExecutionStatus() == ExecutionStatus.PENDING)
                .filter(r -> r.getRemindTime() != null)
                .filter(r -> r.getRemindTime().isBefore(LocalDateTime.now()))
                .collect(java.util.stream.Collectors.toList());

        for (Reminder reminder : pendingReminders) {
            try {
                boolean shouldPush = true;

                if (reminder.getLastConfirmRemindAt() != null) {
                    int intervalMinutes = reminder.getAdvanceMinutes() > 0 ? reminder.getAdvanceMinutes() : 10;
                    long minutesSinceLastPush = Duration.between(reminder.getLastConfirmRemindAt(), LocalDateTime.now()).toMinutes();
                    if (minutesSinceLastPush < intervalMinutes) {
                        shouldPush = false;
                    }
                }

                if (shouldPush) {
                    confirmationService.pushExecuteCard(reminder);
                    reminder.setLastConfirmRemindAt(LocalDateTime.now());
                    // 第一次推送时重置尝试次数
                    if (reminder.getConfirmAttempts() == 0) {
                        reminder.setConfirmAttempts(1);
                    } else {
                        reminder.setConfirmAttempts(reminder.getConfirmAttempts() + 1);
                    }
                    reminderRepository.save(reminder);
                    log.info("阶段二提醒已推送: id={}, title={}, attempt={}", reminder.getId(), reminder.getTitle(), reminder.getConfirmAttempts());
                }
            } catch (Exception e) {
                log.error("推送阶段二提醒失败: id={}", reminder.getId(), e);
            }
        }
    }

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void checkConfirmTimeout() {
        log.debug("检查超时未确认提醒...");

        List<Reminder> timeoutReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                .filter(r -> r.getConfirmStatus() == ConfirmStatus.CONFIRMED)
                .filter(r -> r.getExecutionStatus() == ExecutionStatus.PENDING)
                .filter(r -> r.getConfirmAttempts() >= 3)
                .collect(java.util.stream.Collectors.toList());

        for (Reminder reminder : timeoutReminders) {
            try {
                reminder.setExecutionStatus(ExecutionStatus.DEFAULTED);
                reminder.setStatus(ReminderStatus.SENT);
                reminderRepository.save(reminder);
                log.info("提醒默认确认执行(3次稍后): id={}", reminder.getId());

                // 推送自动确认消息
                pushExecuteAutoConfirm(reminder);
            } catch (Exception e) {
                log.error("检查确认超时失败: id={}", reminder.getId(), e);
            }
        }
    }

    private void pushExecuteAutoConfirm(Reminder reminder) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "execute_auto_confirm");
            data.put("success", true);
            data.put("message", "⏰ 提醒已自动确认（3次稍后未响应）：" + reminder.getTitle());
            data.put("reminderId", reminder.getId());
            data.put("title", reminder.getTitle());

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(reminder.getUserId()), 
                    "/topic/confirmations", 
                    data);
            log.info("推送阶段二自动确认消息: userId={}, reminderId={}", reminder.getUserId(), reminder.getId());
        } catch (Exception e) {
            log.error("推送阶段二自动确认消息失败: userId={}, error={}", reminder.getUserId(), e.getMessage());
        }
    }
}
