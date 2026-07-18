package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.repository.ReminderRepository;
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

/**
 * 智能时间推断服务
 * <p>
 * 4级时间推断：历史学习 > 类型推理 > 当前时段 > 默认时间
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeInferenceService {

    private final ReminderRepository reminderRepository;

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

    public LocalDateTime inferTime(Long userId, String title, String userInput) {
        LocalDateTime inferred = learnFromHistory(userId, title);
        
        if (inferred != null) {
            log.debug("时间推断[历史学习]: {}", inferred);
            return inferred;
        }

        inferred = inferByType(title);
        
        if (inferred != null) {
            log.debug("时间推断[类型推理]: {}", inferred);
            return inferred;
        }

        inferred = inferByCurrentTime();
        
        if (inferred != null) {
            log.debug("时间推断[当前时段]: {}", inferred);
            return inferred;
        }

        return inferDefault();
    }

    public LocalDateTime learnFromHistory(Long userId, String title) {
        List<Reminder> similarReminders = reminderRepository.findByUserIdAndStatus(userId, null)
                .stream()
                .filter(r -> r.getTitle().toLowerCase().contains(title.toLowerCase()))
                .filter(r -> r.getRemindTime() != null)
                .collect(Collectors.toList());

        if (similarReminders.isEmpty()) {
            return null;
        }

        Map<LocalTime, Long> timeCounts = new HashMap<>();
        
        for (Reminder reminder : similarReminders) {
            LocalTime time = reminder.getRemindTime().toLocalTime();
            timeCounts.merge(time, 1L, Long::sum);
        }

        LocalTime mostCommonTime = timeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (mostCommonTime == null) {
            return null;
        }

        LocalDate today = LocalDate.now();
        
        if (LocalDateTime.now().isAfter(today.atTime(mostCommonTime))) {
            today = today.plusDays(1);
        }

        return today.atTime(mostCommonTime);
    }

    public LocalDateTime inferByType(String title) {
        for (Map.Entry<String, LocalTime> entry : TYPE_DEFAULT_TIMES.entrySet()) {
            if (title.contains(entry.getKey())) {
                LocalDate today = LocalDate.now();
                if (LocalDateTime.now().isAfter(today.atTime(entry.getValue()))) {
                    today = today.plusDays(1);
                }
                return today.atTime(entry.getValue());
            }
        }
        return null;
    }

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

    public LocalDateTime inferDefault() {
        return LocalDate.now().plusDays(1).atTime(9, 0);
    }

    public LocalDateTime inferWeeklyTime(Long userId, String title, DayOfWeek targetDay) {
        LocalDateTime historyTime = learnFromHistory(userId, title);
        
        if (historyTime != null) {
            LocalDate today = LocalDate.now();
            LocalDate targetDate = today.with(targetDay);
            
            if (targetDate.isBefore(today)) {
                targetDate = targetDate.plusWeeks(1);
            }
            
            return targetDate.atTime(historyTime.toLocalTime());
        }

        LocalTime defaultTime = TYPE_DEFAULT_TIMES.getOrDefault(title, LocalTime.of(9, 0));
        
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.with(targetDay);
        
        if (targetDate.isBefore(today)) {
            targetDate = targetDate.plusWeeks(1);
        }
        
        return targetDate.atTime(defaultTime);
    }
}
