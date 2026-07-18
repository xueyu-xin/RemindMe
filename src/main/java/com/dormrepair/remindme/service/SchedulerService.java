package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ReminderRepository reminderRepository;
    private final PushService pushService;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanPendingReminders() {
        log.debug("扫描到期提醒...");

        List<Reminder> pendingReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                .filter(r -> r.getRemindTime().isBefore(LocalDateTime.now()))
                .collect(java.util.stream.Collectors.toList());

        for (Reminder reminder : pendingReminders) {
            try {
                pushService.pushReminder(reminder);
                reminder.setStatus(ReminderStatus.SENT);
                reminderRepository.save(reminder);
                log.info("提醒已推送: id={}, title={}", reminder.getId(), reminder.getTitle());
            } catch (Exception e) {
                log.error("推送提醒失败: id={}", reminder.getId(), e);
            }
        }
    }

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void checkConfirmTimeout() {
        log.debug("检查超时未确认提醒...");

        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(30);

        List<Reminder> timeoutReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getConfirmStatus() == ConfirmStatus.PENDING)
                .filter(r -> r.getStatus() == ReminderStatus.SENT)
                .filter(r -> r.getLastConfirmRemindAt() == null || r.getLastConfirmRemindAt().isBefore(timeoutThreshold))
                .filter(r -> r.getConfirmAttempts() < 3)
                .collect(java.util.stream.Collectors.toList());

        for (Reminder reminder : timeoutReminders) {
            try {
                reminder.setConfirmAttempts(reminder.getConfirmAttempts() + 1);
                reminder.setLastConfirmRemindAt(LocalDateTime.now());

                if (reminder.getConfirmAttempts() >= 3) {
                    reminder.setConfirmStatus(ConfirmStatus.DEFAULTED);
                    log.info("提醒默认确认: id={}", reminder.getId());
                } else {
                    pushService.pushReminder(reminder);
                    log.info("补提醒推送: id={}, attempt={}", reminder.getId(), reminder.getConfirmAttempts());
                }

                reminderRepository.save(reminder);
            } catch (Exception e) {
                log.error("检查确认超时失败: id={}", reminder.getId(), e);
            }
        }
    }
}
