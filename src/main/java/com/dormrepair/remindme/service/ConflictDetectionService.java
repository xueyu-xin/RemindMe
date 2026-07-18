package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 冲突检测服务
 * <p>
 * 增强的冲突检测逻辑，支持多种冲突类型
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictDetectionService {

    private final ReminderRepository reminderRepository;

    private static final int DEFAULT_CONFLICT_WINDOW_MINUTES = 30;

    public ConflictResult detectConflicts(Long userId, LocalDateTime newTime, String title) {
        List<Conflict> conflicts = new ArrayList<>();

        List<Reminder> existing = reminderRepository.findByUserIdAndStatus(userId, null)
                .stream()
                .filter(r -> !r.getStatus().name().equals("CANCELLED"))
                .collect(Collectors.toList());

        LocalDateTime windowStart = newTime.minusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);
        LocalDateTime windowEnd = newTime.plusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);

        for (Reminder reminder : existing) {
            LocalDateTime existingTime = reminder.getRemindTime();

            if (existingTime.isAfter(windowStart) && existingTime.isBefore(windowEnd)) {
                conflicts.add(new Conflict(
                        reminder.getId(),
                        reminder.getTitle(),
                        existingTime,
                        ConflictType.TIME_OVERLAP,
                        "时间冲突"
                ));
            }

            if (reminder.getTitle().toLowerCase().contains(title.toLowerCase())) {
                conflicts.add(new Conflict(
                        reminder.getId(),
                        reminder.getTitle(),
                        existingTime,
                        ConflictType.SIMILAR_TITLE,
                        "相似事项"
                ));
            }

            if (reminder.getLocation() != null && title.contains(reminder.getLocation())) {
                conflicts.add(new Conflict(
                        reminder.getId(),
                        reminder.getTitle(),
                        existingTime,
                        ConflictType.LOCATION_CONFLICT,
                        "地点冲突"
                ));
            }
        }

        LocalDateTime suggestedTime = suggestAlternativeTime(userId, newTime);

        return new ConflictResult(
                !conflicts.isEmpty(),
                conflicts,
                suggestedTime,
                conflicts.size()
        );
    }

    private LocalDateTime suggestAlternativeTime(Long userId, LocalDateTime originalTime) {
        LocalDateTime suggested = originalTime.plusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);
        
        for (int i = 0; i < 10; i++) {
            LocalDateTime windowStart = suggested.minusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);
            LocalDateTime windowEnd = suggested.plusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);

            List<Reminder> conflicts = reminderRepository.findByUserIdAndRemindTimeBetween(userId, windowStart, windowEnd);
            
            if (conflicts.isEmpty()) {
                return suggested;
            }
            
            suggested = suggested.plusMinutes(DEFAULT_CONFLICT_WINDOW_MINUTES);
        }

        return suggested;
    }

    public enum ConflictType {
        TIME_OVERLAP,
        SIMILAR_TITLE,
        LOCATION_CONFLICT,
        PRIORITY_CONFLICT
    }

    public record Conflict(
            Long reminderId,
            String title,
            LocalDateTime time,
            ConflictType type,
            String description
    ) {}

    public record ConflictResult(
            boolean hasConflict,
            List<Conflict> conflicts,
            LocalDateTime suggestedTime,
            int conflictCount
    ) {}
}
