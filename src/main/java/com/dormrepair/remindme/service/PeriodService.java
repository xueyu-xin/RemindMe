package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.AgentTool;
import com.dormrepair.remindme.dto.response.PeriodStatusResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Gender;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * 判断某天是否在经期内
     * 经期 = lastPeriodStart 到 lastPeriodStart + periodDuration
     */
    public boolean isMenstruating(User user, LocalDate date) {
        if (user == null || user.getLastPeriodStart() == null) {
            return false;
        }
        LocalDate periodStart = user.getLastPeriodStart();
        int duration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
        LocalDate periodEnd = periodStart.plusDays(duration);
        return !date.isBefore(periodStart) && date.isBefore(periodEnd);
    }

    /**
     * 判断某天是否为排卵期
     * 排卵日 = nextPeriod - 14天，排卵期 = 排卵日 ± 2天
     */
    public boolean isOvulating(User user, LocalDate date) {
        if (user == null || user.getLastPeriodStart() == null) {
            return false;
        }
        int cycleLength = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        LocalDate nextPeriod = user.getLastPeriodStart().plusDays(cycleLength);
        LocalDate ovulationDate = nextPeriod.minusDays(OVULATION_OFFSET);
        LocalDate ovulationStart = ovulationDate.minusDays(2);
        LocalDate ovulationEnd = ovulationDate.plusDays(3);
        return !date.isBefore(ovulationStart) && date.isBefore(ovulationEnd);
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

    @AgentTool(name = "get_period_status", description = "查询用户当前的经期状态，用户询问经期、月经、生理期、安全期、排卵期时调用")
    public Map<String, Object> getPeriodStatusForAgent(Long userId) {
        Map<String, Object> result = new HashMap<>();
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 性别检查
        if (user.getGender() != Gender.FEMALE) {
            result.put("success", false);
            result.put("message", "您尚未设置性别为女性，请在个人设置中设置。");
            return result;
        }

        // 检查是否已记录经期信息
        if (user.getLastPeriodStart() == null) {
            result.put("success", false);
            result.put("message", "尚未记录经期信息，请先设置上次经期开始日期。");
            return result;
        }

        PeriodStatusResponse status = calculatePeriodStatus(user);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        result.put("success", true);
        result.put("status", status.getStatus());
        result.put("statusName", status.getStatusName());
        result.put("icon", status.getIcon());
        result.put("nextPeriodDate", status.getNextPeriodDate() != null ? status.getNextPeriodDate().format(formatter) : null);
        result.put("ovulationDate", status.getOvulationDate() != null ? status.getOvulationDate().format(formatter) : null);
        result.put("daysUntilNextPeriod", status.getDaysUntilNextPeriod());
        result.put("cycleLength", status.getCycleLength());

        return result;
    }

    @AgentTool(name = "get_next_period", description = "查询下次经期日期，用户询问下次月经、下次经期、月经什么时候来时调用")
    public Map<String, Object> getNextPeriodForAgent(Long userId) {
        Map<String, Object> result = new HashMap<>();
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 性别检查
        if (user.getGender() != Gender.FEMALE) {
            result.put("success", false);
            result.put("message", "您尚未设置性别为女性，请在个人设置中设置。");
            return result;
        }

        // 检查是否已记录经期信息
        if (user.getLastPeriodStart() == null) {
            result.put("success", false);
            result.put("message", "尚未记录经期信息，请先设置上次经期开始日期。");
            return result;
        }

        LocalDate nextPeriod = getNextPeriodDate(user);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        long daysUntilNext = ChronoUnit.DAYS.between(today, nextPeriod);

        result.put("success", true);
        result.put("nextPeriodDate", nextPeriod.format(formatter));
        result.put("daysUntilNextPeriod", (int) daysUntilNext);

        return result;
    }

    @AgentTool(name = "update_period_start", description = "记录或更新经期开始日期，用户说'经期来了'、'月经开始了'、'今天来月经'时调用")
    @Transactional
    public Map<String, Object> updatePeriodStartForAgent(Long userId, String startDate) {
        Map<String, Object> result = new HashMap<>();
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 性别检查
        if (user.getGender() != Gender.FEMALE) {
            result.put("success", false);
            result.put("message", "您尚未设置性别为女性，请在个人设置中设置。");
            return result;
        }

        // 解析日期，不传则使用今天
        LocalDate periodStart;
        if (startDate == null || startDate.trim().isEmpty()) {
            periodStart = LocalDate.now();
        } else {
            try {
                periodStart = LocalDate.parse(startDate.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "日期格式不正确，请使用 yyyy-MM-dd 格式");
                return result;
            }
        }

        // 更新经期开始日期
        user.setLastPeriodStart(periodStart);
        user.setPeriodConfirmStatus(ConfirmStatus.CONFIRMED);
        user.setPeriodRemindPostponed(false);
        
        if (user.getPeriodConfirmCount() != null) {
            user.setPeriodConfirmCount(user.getPeriodConfirmCount() + 1);
        } else {
            user.setPeriodConfirmCount(1);
        }

        userRepository.save(user);
        log.info("Agent更新经期开始日期: userId={}, date={}", userId, periodStart);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        // 重新计算经期状态
        PeriodStatusResponse status = calculatePeriodStatus(user);

        result.put("success", true);
        result.put("periodStartDate", periodStart.format(formatter));
        result.put("message", "已记录经期开始日期：" + periodStart.format(formatter));
        result.put("status", status.getStatus());
        result.put("statusName", status.getStatusName());
        result.put("nextPeriodDate", status.getNextPeriodDate() != null ? status.getNextPeriodDate().format(formatter) : null);
        result.put("daysUntilNextPeriod", status.getDaysUntilNextPeriod());

        return result;
    }

    @AgentTool(name = "update_period_settings", description = "更新经期周期设置，用户说'周期改成30天'、'经期持续5天'时调用")
    @Transactional
    public Map<String, Object> updatePeriodSettingsForAgent(Long userId, Integer cycleDays, Integer periodDuration) {
        Map<String, Object> result = new HashMap<>();
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 性别检查
        if (user.getGender() != Gender.FEMALE) {
            result.put("success", false);
            result.put("message", "您尚未设置性别为女性，请在个人设置中设置。");
            return result;
        }

        boolean updated = false;

        // 更新周期天数
        if (cycleDays != null) {
            if (cycleDays < 20 || cycleDays > 45) {
                result.put("success", false);
                result.put("message", "周期天数应在 20-45 天之间");
                return result;
            }
            user.setCycleDays(cycleDays);
            updated = true;
            result.put("cycleDays", cycleDays);
        }

        // 更新经期持续天数
        if (periodDuration != null) {
            if (periodDuration < 3 || periodDuration > 7) {
                result.put("success", false);
                result.put("message", "经期持续天数应在 3-7 天之间");
                return result;
            }
            user.setPeriodDuration(periodDuration);
            updated = true;
            result.put("periodDuration", periodDuration);
        }

        if (!updated) {
            result.put("success", false);
            result.put("message", "请提供要更新的设置（cycleDays 或 periodDuration）");
            return result;
        }

        userRepository.save(user);
        log.info("Agent更新经期设置: userId={}, cycleDays={}, periodDuration={}", userId, cycleDays, periodDuration);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        // 如果已有经期记录，重新计算经期状态
        if (user.getLastPeriodStart() != null) {
            PeriodStatusResponse status = calculatePeriodStatus(user);
            result.put("nextPeriodDate", status.getNextPeriodDate() != null ? status.getNextPeriodDate().format(formatter) : null);
            result.put("daysUntilNextPeriod", status.getDaysUntilNextPeriod());
        }

        result.put("success", true);
        result.put("message", "经期设置已更新");

        return result;
    }
}
