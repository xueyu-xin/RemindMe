package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.PeriodStatus;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 月经周期服务
 * <p>
 * 经期计算、状态判断、提醒生成
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodService {

    private final UserRepository userRepository;
    private final ReminderRepository reminderRepository;
    private final PushService pushService;

    private static final int DEFAULT_CYCLE_LENGTH = 28;
    private static final int SAFE_DAYS_AFTER_PERIOD = 7;
    private static final int OVULATION_OFFSET = 14;
    private static final int PREMENSTRUAL_DAYS = 3;

    public PeriodStatusResponse calculatePeriodStatus(User user) {
        if (user.getLastPeriodStart() == null) {
            return createUnknownStatus();
        }

        LocalDate lastPeriod = user.getLastPeriodStart();
        int cycleLength = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        
        LocalDate nextPeriod = lastPeriod.plusDays(cycleLength);
        LocalDate ovulationDate = nextPeriod.minusDays(OVULATION_OFFSET);
        
        LocalDate today = LocalDate.now();
        long daysUntilNext = ChronoUnit.DAYS.between(today, nextPeriod);
        
        PeriodStatus status = determineStatus(lastPeriod, nextPeriod, ovulationDate, cycleLength);

        PeriodStatusResponse response = new PeriodStatusResponse();
        response.setStatus(status.name());
        response.setStatusName(status.getDisplayName());
        response.setIcon(getIcon(status));
        response.setNextPeriodDate(nextPeriod);
        response.setOvulationDate(ovulationDate);
        response.setDaysUntilNextPeriod((int) daysUntilNext);
        response.setCycleLength(cycleLength);
        response.setShouldRemind(shouldRemind(user, today, nextPeriod));

        return response;
    }

    @Transactional
    public void checkPeriodReminder(User user) {
        if (!user.getEnablePeriodReminder()) {
            return;
        }

        if (user.getLastPeriodStart() == null) {
            return;
        }

        PeriodStatusResponse status = calculatePeriodStatus(user);
        if (!status.getShouldRemind()) {
            return;
        }

        boolean exists = reminderRepository.findByUserIdAndStatus(user.getId(), ReminderStatus.PENDING)
                .stream()
                .anyMatch(r -> r.getSource() == ReminderSource.PERIOD_REMINDER);

        if (!exists) {
            Reminder reminder = new Reminder();
            reminder.setUserId(user.getId());
            reminder.setTitle("经期提醒");
            reminder.setRemindTime(status.getNextPeriodDate().atStartOfDay());
            reminder.setSource(ReminderSource.PERIOD_REMINDER);
            reminder.setStatus(ReminderStatus.PENDING);
            
            reminderRepository.save(reminder);
            log.info("创建经期提醒: userId={}, date={}", user.getId(), status.getNextPeriodDate());
        }
    }

    @Transactional
    public void confirmPeriodStart(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        user.setLastPeriodStart(LocalDate.now());
        user.setPeriodConfirmStatus(ConfirmStatus.CONFIRMED);
        user.setPeriodRemindPostponed(false);

        if (user.getPeriodConfirmCount() != null) {
            user.setPeriodConfirmCount(user.getPeriodConfirmCount() + 1);
        } else {
            user.setPeriodConfirmCount(1);
        }

        userRepository.save(user);
        log.info("用户确认经期开始: userId={}", userId);
    }

    @Transactional
    public void postponePeriodReminder(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (user.getPeriodRemindPostponedCount() != null && user.getPeriodRemindPostponedCount() >= 3) {
            throw new IllegalArgumentException("已达到最大推迟次数");
        }

        user.setPeriodRemindPostponed(true);
        user.setPeriodRemindPostponedCount(
                user.getPeriodRemindPostponedCount() != null 
                        ? user.getPeriodRemindPostponedCount() + 1 
                        : 1
        );

        userRepository.save(user);
        log.info("用户推迟经期提醒: userId={}, count={}", userId, user.getPeriodRemindPostponedCount());
    }

    public LocalDate getNextPeriodDate(User user) {
        if (user.getLastPeriodStart() == null) {
            return null;
        }
        int cycleLength = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        return user.getLastPeriodStart().plusDays(cycleLength);
    }

    private PeriodStatus determineStatus(LocalDate lastPeriod, LocalDate nextPeriod, 
                                         LocalDate ovulationDate, int cycleLength) {
        LocalDate today = LocalDate.now();

        if (today.isBefore(lastPeriod.plusDays(cycleLength - PREMENSTRUAL_DAYS))) {
            if (today.isBefore(lastPeriod.plusDays(SAFE_DAYS_AFTER_PERIOD))) {
                return PeriodStatus.MENSTRUATING;
            }
            
            if (today.isBefore(ovulationDate.minusDays(2)) || today.isAfter(ovulationDate.plusDays(2))) {
                return PeriodStatus.SAFE;
            }
            
            return PeriodStatus.OVULATORY;
        }

        return PeriodStatus.PREMENSTRUAL;
    }

    private String getIcon(PeriodStatus status) {
        return switch (status) {
            case SAFE -> "♀️";
            case OVULATORY -> "🌸";
            case PREMENSTRUAL -> "📋";
            case MENSTRUATING -> "🌙";
            default -> "❓";
        };
    }

    private boolean shouldRemind(User user, LocalDate today, LocalDate nextPeriod) {
        if (!user.getEnablePeriodReminder()) {
            return false;
        }
        
        if (user.getPeriodRemindPostponed()) {
            return false;
        }

        return ChronoUnit.DAYS.between(today, nextPeriod) <= 3;
    }

    private PeriodStatusResponse createUnknownStatus() {
        PeriodStatusResponse response = new PeriodStatusResponse();
        response.setStatus(PeriodStatus.UNKNOWN.name());
        response.setStatusName(PeriodStatus.UNKNOWN.getDisplayName());
        response.setIcon("❓");
        response.setShouldRemind(false);
        return response;
    }
}
