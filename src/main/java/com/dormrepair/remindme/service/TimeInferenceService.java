package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 智能时间推断：当用户没明确给时间时，按"历史学习 > 类型推理 > 当前时段 > 默认时间"四级回退给出建议时间
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeInferenceService {

    private final ReminderRepository reminderRepository;

    // 不同类型事项的经验默认时间，命中标题关键词即套用
    private static final Map<String, LocalTime> TYPE_DEFAULT_TIMES = new HashMap<>();
    static {
        TYPE_DEFAULT_TIMES.put("作业", LocalTime.of(20, 0));
        TYPE_DEFAULT_TIMES.put("考试", LocalTime.of(9, 0));
        TYPE_DEFAULT_TIMES.put("上课", LocalTime.of(8, 30));
        TYPE_DEFAULT_TIMES.put("开会", LocalTime.of(14, 0));
        TYPE_DEFAULT_TIMES.put("吃饭", LocalTime.of(12, 0));
        TYPE_DEFAULT_TIMES.put("睡觉", LocalTime.of(23, 0));
        TYPE_DEFAULT_TIMES.put("运动", LocalTime.of(18, 0));
        TYPE_DEFAULT_TIMES.put("学习", LocalTime.of(19, 0));
        TYPE_DEFAULT_TIMES.put("起床", LocalTime.of(7, 0));
        TYPE_DEFAULT_TIMES.put("休息", LocalTime.of(12, 30));
    }

    // 推断主入口：依次尝试 4 种策略，命中即返回
    public LocalDateTime inferTime(Long userId, String title, String userInput) {
        // 第一优先级：从该用户历史提醒里学习最常见时间
        LocalDateTime inferred = learnFromHistory(userId, title);

        if (inferred != null) {
            log.debug("时间推断[历史学习]: {}", inferred);
            return inferred;
        }

        // 第二优先级：根据标题关键词套用类型默认时间
        inferred = inferByType(title);

        if (inferred != null) {
            log.debug("时间推断[类型推理]: {}", inferred);
            return inferred;
        }

        // 第三优先级：按当前时段给出下一个合理时间点
        inferred = inferByCurrentTime();

        if (inferred != null) {
            log.debug("时间推断[当前时段]: {}", inferred);
            return inferred;
        }

        // 兜底：明天 9:00
        return inferDefault();
    }

    // 历史学习：找标题相似的过往提醒，取出现频次最高的时间点
    public LocalDateTime learnFromHistory(Long userId, String title) {
        List<Reminder> similarReminders = reminderRepository.findByUserIdAndStatus(userId, null)
                .stream()
                .filter(r -> r.getTitle().toLowerCase().contains(title.toLowerCase()))
                .filter(r -> r.getRemindTime() != null)
                .collect(Collectors.toList());

        if (similarReminders.isEmpty()) {
            return null;
        }

        // 按时间点统计出现次数
        Map<LocalTime, Long> timeCounts = new HashMap<>();

        for (Reminder reminder : similarReminders) {
            LocalTime time = reminder.getRemindTime().toLocalTime();
            timeCounts.merge(time, 1L, Long::sum);
        }

        // 取频次最高的时间点
        LocalTime mostCommonTime = timeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (mostCommonTime == null) {
            return null;
        }

        // 今天该时间点已过则顺延到明天
        LocalDate today = LocalDate.now();

        if (TimeUtils.now().isAfter(today.atTime(mostCommonTime))) {
            today = today.plusDays(1);
        }

        return today.atTime(mostCommonTime);
    }

    // 类型推理：标题命中类型关键词时套用经验时间，今天过了则顺延明天
    public LocalDateTime inferByType(String title) {
        for (Map.Entry<String, LocalTime> entry : TYPE_DEFAULT_TIMES.entrySet()) {
            if (title.contains(entry.getKey())) {
                LocalDate today = LocalDate.now();
                if (TimeUtils.now().isAfter(today.atTime(entry.getValue()))) {
                    today = today.plusDays(1);
                }
                return today.atTime(entry.getValue());
            }
        }
        return null;
    }

    // 当前时段推理：按当前时间落在哪个区间，给出下一个合理整点
    public LocalDateTime inferByCurrentTime() {
        LocalTime now = LocalTime.now();

        if (now.isBefore(LocalTime.of(9, 0))) {
            return LocalDate.now().atTime(9, 0);
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            return LocalDate.now().atTime(14, 0);
        } else if (now.isBefore(LocalTime.of(18, 0))) {
            return LocalDate.now().atTime(20, 0);
        } else {
            return LocalDate.now().plusDays(1).atTime(9, 0);
        }
    }

    // 兜底默认时间：明天 9:00
    public LocalDateTime inferDefault() {
        return LocalDate.now().plusDays(1).atTime(9, 0);
    }

    // 周期提醒的时间推断：在指定星期几上套用历史时间或类型默认时间，已过则顺延下周
    public LocalDateTime inferWeeklyTime(Long userId, String title, DayOfWeek targetDay) {
        LocalDateTime historyTime = learnFromHistory(userId, title);

        if (historyTime != null) {
            LocalDate today = LocalDate.now();
            LocalDate targetDate = today.with(targetDay);

            // 目标日期已过则顺延到下周
            if (targetDate.isBefore(today)) {
                targetDate = targetDate.plusWeeks(1);
            }

            return targetDate.atTime(historyTime.toLocalTime());
        }

        // 无历史则套用类型默认时间，找不到类型默认 9:00
        LocalTime defaultTime = TYPE_DEFAULT_TIMES.getOrDefault(title, LocalTime.of(9, 0));

        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.with(targetDay);

        if (targetDate.isBefore(today)) {
            targetDate = targetDate.plusWeeks(1);
        }

        return targetDate.atTime(defaultTime);
    }
}
