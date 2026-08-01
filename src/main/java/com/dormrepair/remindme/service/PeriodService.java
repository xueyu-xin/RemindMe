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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 经期服务：基于上次经期开始日期和周期参数，计算当前经期状态、排卵期与安全期，
// 并在临近下次经期时生成提醒；对 Agent 暴露 4 个工具：查状态/查下次经期/记录经期开始/改周期设置。
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodService {

    private final UserRepository userRepository;
    private final ReminderRepository reminderRepository;
    private final PushService pushService;

    // 默认月经周期（天）
    private static final int DEFAULT_CYCLE_LENGTH = 28;
    private static final int SAFE_DAYS_AFTER_PERIOD = 7;
    // 排卵日相对下次经期的倒推天数
    private static final int OVULATION_OFFSET = 14;
    // 经前期提醒窗口（天）
    private static final int PREMENSTRUAL_DAYS = 3;

    // 计算用户当前经期状态：会自动推进已过期的周期基准日期并落库，
    // 返回包含状态、下次经期、排卵日、距下次经期天数等信息的聚合对象。
    @Transactional
    public PeriodStatusResponse calculatePeriodStatus(User user) {
        if (user.getLastPeriodStart() == null) {
            return createUnknownStatus();
        }

        LocalDate lastPeriod = user.getLastPeriodStart();
        int cycleLength = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        int periodDuration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
        
        LocalDate today = LocalDate.now();
        
        // 自动推进周期：如果今天已达到或超过下次预计经期日期，
        // 则将上次经期开始日期推进到新周期，并持久化更新到数据库
        boolean rolled = false;
        while (today.isAfter(lastPeriod.plusDays(cycleLength - 1))) {
            lastPeriod = lastPeriod.plusDays(cycleLength);
            rolled = true;
        }
        if (rolled) {
            user.setLastPeriodStart(lastPeriod);
            userRepository.save(user);
            log.info("自动推进经期周期: userId={}, newLastPeriodStart={}", user.getId(), lastPeriod);
        }
        
        LocalDate nextPeriod = lastPeriod.plusDays(cycleLength);
        LocalDate ovulationDate = nextPeriod.minusDays(OVULATION_OFFSET);
        
        long daysUntilNext = ChronoUnit.DAYS.between(today, nextPeriod);
        
        PeriodStatus status = determineStatus(lastPeriod, nextPeriod, ovulationDate, cycleLength, periodDuration);

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

    // 定时检查是否需要创建经期提醒：仅在用户开启提醒、有经期记录、且距下次经期临近时，
    // 才插入一条 PERIOD_REMINDER 类型的待办提醒（已存在则跳过）。
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
            // 提醒时间 = 下次经期前一天 + 用户设置的提醒时间（默认 20:30）
            String timeStr = user.getPeriodRemindTime() != null ? user.getPeriodRemindTime() : "20:30";
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            LocalDateTime remindTime = status.getNextPeriodDate().minusDays(1).atTime(hour, minute);

            Reminder reminder = new Reminder();
            reminder.setUserId(user.getId());
            reminder.setTitle("经期提醒");
            reminder.setRemindTime(remindTime);
            reminder.setAdvanceMinutes(0);  // 准时提醒，无需提前
            reminder.setSource(ReminderSource.PERIOD_REMINDER);
            reminder.setStatus(ReminderStatus.PENDING);
            // 系统自动创建，跳过信息确认阶段，直接进入执行阶段，否则调度器扫描不到
            reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
            reminderRepository.save(reminder);
            log.info("创建经期提醒: userId={}, remindTime={}, nextPeriod={}", user.getId(), remindTime, status.getNextPeriodDate());
        }
    }

    // 用户确认经期已开始：以今天为新的经期开始日期，重置推迟状态，并累加确认次数
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

    // 推迟经期提醒：标记推迟状态并累加推迟次数，最多 3 次
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

    // 根据上次经期开始日期 + 周期天数推算下次经期日期；无经期记录返回 null
    public LocalDate getNextPeriodDate(User user) {
        if (user.getLastPeriodStart() == null) {
            return null;
        }
        int cycleLength = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        return user.getLastPeriodStart().plusDays(cycleLength);
    }

    // 判断某天是否在经期内：经期区间为 [lastPeriodStart, lastPeriodStart + periodDuration)
    public boolean isMenstruating(User user, LocalDate date) {
        if (user == null || user.getLastPeriodStart() == null) {
            return false;
        }
        LocalDate periodStart = user.getLastPeriodStart();
        int duration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
        LocalDate periodEnd = periodStart.plusDays(duration);
        return !date.isBefore(periodStart) && date.isBefore(periodEnd);
    }

    // 判断某天是否在排卵期：排卵日 = 下次经期 - 14 天，排卵窗口取排卵日 ± 2 天
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

    // 按优先级判断今天所处的经期阶段：经期 > 经前期 > 排卵期 > 安全期
    private PeriodStatus determineStatus(LocalDate lastPeriod, LocalDate nextPeriod, 
                                         LocalDate ovulationDate, int cycleLength, int periodDuration) {
        LocalDate today = LocalDate.now();

        // 判断是否处于经期中（今天落在 lastPeriod 到 lastPeriod + periodDuration - 1 之间）
        LocalDate periodEnd = lastPeriod.plusDays(periodDuration - 1);
        if (!today.isBefore(lastPeriod) && !today.isAfter(periodEnd)) {
            return PeriodStatus.MENSTRUATING;
        }

        // 判断是否处于经期前（今天距离 nextPeriod 小于等于 PREMENSTRUAL_DAYS 天）
        LocalDate premenstrualStart = nextPeriod.minusDays(PREMENSTRUAL_DAYS);
        if (!today.isBefore(premenstrualStart) && today.isBefore(nextPeriod)) {
            return PeriodStatus.PREMENSTRUAL;
        }

        // 判断是否处于排卵期
        LocalDate ovulationStart = ovulationDate.minusDays(2);
        LocalDate ovulationEnd = ovulationDate.plusDays(2);
        if (!today.isBefore(ovulationStart) && !today.isAfter(ovulationEnd)) {
            return PeriodStatus.OVULATORY;
        }

        // 默认返回安全期
        return PeriodStatus.SAFE;
    }

    // 各经期状态对应的展示图标
    private String getIcon(PeriodStatus status) {
        return switch (status) {
            case SAFE -> "♀️";
            case OVULATORY -> "🌸";
            case PREMENSTRUAL -> "📋";
            case MENSTRUATING -> "🌙";
            default -> "❓";
        };
    }

    // 是否需要触发经期提醒：开启提醒 + 未推迟 + 距下次经期不超过 3 天
    private boolean shouldRemind(User user, LocalDate today, LocalDate nextPeriod) {
        if (!user.getEnablePeriodReminder()) {
            return false;
        }
        
        if (user.getPeriodRemindPostponed()) {
            return false;
        }

        return ChronoUnit.DAYS.between(today, nextPeriod) <= 3;
    }

    // 构造无经期记录时的默认状态（UNKNOWN，不提醒）
    private PeriodStatusResponse createUnknownStatus() {
        PeriodStatusResponse response = new PeriodStatusResponse();
        response.setStatus(PeriodStatus.UNKNOWN.name());
        response.setStatusName(PeriodStatus.UNKNOWN.getDisplayName());
        response.setIcon("❓");
        response.setShouldRemind(false);
        return response;
    }

    // Agent 工具：查询当前经期状态，需校验性别为女性且已记录经期
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

    // Agent 工具：查询下次经期日期及距今天数
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

    // Agent 工具：记录或更新经期开始日期，不传日期则默认今天；
    // 内部会触发周期推进逻辑，返回推进后的真实基准日期与最新状态。
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
            // 校验不能晚于今天（与 UserController 的校验规则保持一致）
            if (periodStart.isAfter(LocalDate.now())) {
                result.put("success", false);
                result.put("message", "上次经期开始日期不能晚于今天");
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
        
        // 重新计算经期状态（calculatePeriodStatus内部可能推进lastPeriodStart并持久化）
        PeriodStatusResponse status = calculatePeriodStatus(user);

        // 用推进后的真实基准日期作为「当前记录的上次经期开始日期」回传给LLM
        LocalDate effectiveLastPeriod = user.getLastPeriodStart();
        int effectiveCycle = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        int effectiveDuration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
        LocalDate effectiveNext = effectiveLastPeriod.plusDays(effectiveCycle);

        StringBuilder msg = new StringBuilder();
        msg.append("已记录经期开始日期：").append(effectiveLastPeriod.format(formatter));
        // 如果用户传入的 startDate 与推进后的基准日期不同，说明发生了自动周期推进，需要解释给用户
        if (!periodStart.equals(effectiveLastPeriod)) {
            msg.append("（您提供的").append(periodStart.format(formatter))
               .append("已根据周期自动推进到当前周期的").append(effectiveLastPeriod.format(formatter)).append("）");
        }
        msg.append("；当前处于").append(status.getStatusName());
        if (status.getNextPeriodDate() != null) {
            msg.append("；下次经期预计：").append(status.getNextPeriodDate().format(formatter));
        }

        result.put("success", true);
        result.put("periodStartDate", effectiveLastPeriod.format(formatter));
        result.put("cycleDays", effectiveCycle);
        result.put("periodDuration", effectiveDuration);
        result.put("message", msg.toString());
        result.put("status", status.getStatus());
        result.put("statusName", status.getStatusName());
        result.put("nextPeriodDate", status.getNextPeriodDate() != null ? status.getNextPeriodDate().format(formatter) : effectiveNext.format(formatter));
        result.put("daysUntilNextPeriod", status.getDaysUntilNextPeriod());

        return result;
    }

    // Agent 工具：更新经期周期天数或持续天数，带交叉校验（周期须大于持续天数）；
    // 更新成功后若有经期记录会重新计算状态并回传。
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

        // 更新周期天数（必须大于0，且大于当前经期持续天数）
        if (cycleDays != null) {
            if (cycleDays <= 0) {
                result.put("success", false);
                result.put("message", "周期天数必须大于0");
                return result;
            }
            int currentDuration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
            if (cycleDays <= currentDuration) {
                result.put("success", false);
                result.put("message", "月经周期必须大于经期持续天数（" + currentDuration + "天）");
                return result;
            }
            user.setCycleDays(cycleDays);
            updated = true;
            result.put("cycleDays", cycleDays);
        }

        // 更新经期持续天数（必须大于0且小于月经周期）
        if (periodDuration != null) {
            int effectiveCycle = cycleDays != null ? cycleDays
                    : (user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH);
            if (periodDuration <= 0) {
                result.put("success", false);
                result.put("message", "经期持续天数必须大于0");
                return result;
            }
            if (periodDuration >= effectiveCycle) {
                result.put("success", false);
                result.put("message", "经期持续天数必须小于月经周期（" + effectiveCycle + "天）");
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

        // 即使没有经期记录，也返回用户当前最新的 cycleDays 和 periodDuration 给LLM
        int effectiveCycle = user.getCycleDays() != null ? user.getCycleDays() : DEFAULT_CYCLE_LENGTH;
        int effectiveDuration = user.getPeriodDuration() != null ? user.getPeriodDuration() : 5;
        result.put("cycleDays", effectiveCycle);
        result.put("periodDuration", effectiveDuration);

        // 如果已有经期记录，重新计算经期状态（内部可能推进lastPeriodStart）
        PeriodStatusResponse status = null;
        if (user.getLastPeriodStart() != null) {
            status = calculatePeriodStatus(user);
            result.put("periodStartDate", user.getLastPeriodStart().format(formatter));
            result.put("nextPeriodDate", status.getNextPeriodDate() != null ? status.getNextPeriodDate().format(formatter) : null);
            result.put("daysUntilNextPeriod", status.getDaysUntilNextPeriod());
            result.put("status", status.getStatus());
            result.put("statusName", status.getStatusName());
        }

        // 组装详细说明，LLM直接可以参考这份message给用户回复
        StringBuilder msg = new StringBuilder();
        List<String> changes = new ArrayList<>();
        if (cycleDays != null) {
            changes.add("月经周期更新为" + cycleDays + "天");
        }
        if (periodDuration != null) {
            changes.add("经期持续天数更新为" + periodDuration + "天");
        }
        if (!changes.isEmpty()) {
            msg.append(String.join("，", changes)).append("。");
        } else {
            msg.append("经期设置已更新。");
        }
        if (user.getLastPeriodStart() != null) {
            msg.append("上次经期开始日期：").append(user.getLastPeriodStart().format(formatter)).append("；");
            if (status != null) {
                msg.append("当前处于").append(status.getStatusName()).append("；");
                if (status.getNextPeriodDate() != null) {
                    msg.append("下次经期预计：").append(status.getNextPeriodDate().format(formatter));
                }
            }
        }

        result.put("success", true);
        result.put("message", msg.toString());

        return result;
    }
}
