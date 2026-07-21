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

import java.time.Duration;
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
    private final ConfirmationService confirmationService;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanPendingReminders() {
        log.debug("扫描到期提醒...");

        List<Reminder> pendingReminders = reminderRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING)
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
                    reminder.setConfirmAttempts(reminder.getConfirmAttempts() + 1);
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
                .filter(r -> r.getConfirmStatus() == ConfirmStatus.PENDING)
                .filter(r -> r.getStatus() == ReminderStatus.SENT)
                .filter(r -> r.getConfirmAttempts() >= 3)
                .collect(java.util.stream.Collectors.toList());

        for (Reminder reminder : timeoutReminders) {
            try {
                reminder.setConfirmStatus(ConfirmStatus.DEFAULTED);
                reminder.setStatus(ReminderStatus.COMPLETED);
                reminderRepository.save(reminder);
                log.info("提醒默认确认(3次稍后): id={}", reminder.getId());
            } catch (Exception e) {
                log.error("检查确认超时失败: id={}", reminder.getId(), e);
            }
        }
    }
}
