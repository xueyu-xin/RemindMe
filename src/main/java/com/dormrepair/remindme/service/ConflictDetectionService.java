package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 冲突检测服务
 * <p>
 * 负责检测提醒时间冲突，提供冲突描述和建议时间
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictDetectionService {

    private final ReminderRepository reminderRepository;

    private static final int CONFLICT_WINDOW_MINUTES = 30;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 检测提醒时间冲突
     *
     * @param userId 用户ID
     * @param remindTime 提醒时间
     * @param excludeReminderId 排除的提醒ID（修改时排除自身，可为null）
     * @return 冲突响应，包含冲突列表和建议时间
     */
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime, Long excludeReminderId) {
        return checkConflict(userId, remindTime, null, excludeReminderId);
    }

    /**
     * 检测提醒时间冲突（支持时间段提醒）
     *
     * @param userId 用户ID
     * @param remindTime 提醒时间
     * @param endTime 结束时间（时间段提醒时使用，单点提醒为null）
     * @param excludeReminderId 排除的提醒ID（修改时排除自身，可为null）
     * @return 冲突响应，包含冲突列表和建议时间
     */
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime, LocalDateTime endTime, Long excludeReminderId) {
        List<Reminder> conflicts;

        if (endTime != null) {
            // 时间段重叠检测：调用 findOverlappingReminders
            log.debug("检测时间段冲突: userId={}, remindTime={}, endTime={}, excludeId={}",
                    userId, remindTime, endTime, excludeReminderId);

            conflicts = reminderRepository.findOverlappingReminders(userId, remindTime, endTime);

            // 过滤：排除自身
            conflicts = conflicts.stream()
                    .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                    .collect(Collectors.toList());
        } else {
            // 单点提醒：使用原有单点 ±30min 逻辑
            LocalDateTime start = remindTime.minusMinutes(CONFLICT_WINDOW_MINUTES);
            LocalDateTime end = remindTime.plusMinutes(CONFLICT_WINDOW_MINUTES);

            log.debug("检测单点冲突: userId={}, remindTime={}, 范围={} ~ {}, excludeId={}",
                    userId, remindTime, start, end, excludeReminderId);

            List<Reminder> reminders = reminderRepository.findByUserIdAndRemindTimeBetween(userId, start, end);

            // 过滤：未取消的 + 已确认的 + 排除自身
            conflicts = reminders.stream()
                    .filter(r -> r.getStatus() != ReminderStatus.CANCELLED)
                    .filter(r -> r.getConfirmStatus() == ConfirmStatus.CONFIRMED)
                    .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                    .collect(Collectors.toList());
        }

        if (conflicts.isEmpty()) {
            log.debug("未检测到冲突: userId={}, remindTime={}", userId, remindTime);
            return new ConflictResponse(false, null, null);
        }

        log.info("检测到 {} 个冲突: userId={}, remindTime={}", conflicts.size(), userId, remindTime);

        // 转换为 ConflictItem（包含 endTime）
        List<ConflictResponse.ConflictItem> items = conflicts.stream()
                .map(r -> new ConflictResponse.ConflictItem(r.getId(), r.getTitle(), r.getRemindTime(), r.getEndTime()))
                .collect(Collectors.toList());

        // 计算建议时间（向后找第一个空闲的30分钟窗口）
        LocalDateTime suggestedTime = calculateSuggestedTime(userId, remindTime, endTime, excludeReminderId);

        return new ConflictResponse(true, items, suggestedTime);
    }

    /**
     * 判断是否存在冲突
     *
     * @param userId 用户ID
     * @param remindTime 提醒时间
     * @param excludeReminderId 排除的提醒ID
     * @return 是否有冲突
     */
    public boolean hasConflict(Long userId, LocalDateTime remindTime, Long excludeReminderId) {
        return hasConflict(userId, remindTime, null, excludeReminderId);
    }

    /**
     * 判断是否存在冲突（支持时间段提醒）
     *
     * @param userId 用户ID
     * @param remindTime 提醒时间
     * @param endTime 结束时间（可选）
     * @param excludeReminderId 排除的提醒ID
     * @return 是否有冲突
     */
    public boolean hasConflict(Long userId, LocalDateTime remindTime, LocalDateTime endTime, Long excludeReminderId) {
        ConflictResponse response = checkConflict(userId, remindTime, endTime, excludeReminderId);
        return response.isHasConflict();
    }

    /**
     * 获取冲突描述文本
     *
     * @param conflicts 冲突列表
     * @param newRemindTime 新提醒时间
     * @return 可读的冲突描述
     */
    public String getConflictDescription(List<ConflictResponse.ConflictItem> conflicts, LocalDateTime newRemindTime) {
        if (conflicts == null || conflicts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("您已有提醒");

        if (conflicts.size() == 1) {
            ConflictResponse.ConflictItem item = conflicts.get(0);
            String timeStr = item.getRemindTime().format(TIME_FORMATTER);
            sb.append("「").append(item.getTitle()).append("」在 ").append(timeStr);
        } else {
            sb.append("（").append(conflicts.size()).append("个）：");
            for (int i = 0; i < conflicts.size(); i++) {
                ConflictResponse.ConflictItem item = conflicts.get(i);
                String timeStr = item.getRemindTime().format(TIME_FORMATTER);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append("「").append(item.getTitle()).append("」").append(timeStr);
            }
        }

        String newTimeStr = newRemindTime.format(TIME_FORMATTER);
        sb.append("，当前提醒 ").append(newTimeStr).append(" 与其冲突");

        return sb.toString();
    }

    /**
     * 计算建议的提醒时间
     * <p>
     * 从冲突时间向后找，找到第一个30分钟内没有冲突的时间点
     * </p>
     *
     * @param userId 用户ID
     * @param fromTime 起始时间
     * @param excludeReminderId 排除的提醒ID
     * @return 建议时间
     */
    private LocalDateTime calculateSuggestedTime(Long userId, LocalDateTime fromTime, Long excludeReminderId) {
        return calculateSuggestedTime(userId, fromTime, null, excludeReminderId);
    }

    /**
     * 计算建议的提醒时间（支持时间段提醒）
     *
     * @param userId 用户ID
     * @param fromTime 起始时间
     * @param endTime 结束时间（可选，用于计算时间段长度）
     * @param excludeReminderId 排除的提醒ID
     * @return 建议时间
     */
    private LocalDateTime calculateSuggestedTime(Long userId, LocalDateTime fromTime, LocalDateTime endTime, Long excludeReminderId) {
        // 如果是时间段提醒，计算时间段长度作为间隔
        int windowMinutes = CONFLICT_WINDOW_MINUTES;
        if (endTime != null) {
            long durationMinutes = java.time.Duration.between(fromTime, endTime).toMinutes();
            windowMinutes = (int) Math.max(durationMinutes, CONFLICT_WINDOW_MINUTES);
        }

        LocalDateTime candidate = fromTime.plusMinutes(windowMinutes);
        LocalDateTime maxSearchTime = fromTime.plusHours(2);

        while (candidate.isBefore(maxSearchTime)) {
            if (endTime != null) {
                // 时间段提醒：使用 findOverlappingReminders 检测重叠
                LocalDateTime candidateEnd = candidate.plusMinutes(windowMinutes);
                boolean hasConflict = !reminderRepository.findOverlappingReminders(userId, candidate, candidateEnd)
                        .stream()
                        .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                        .findAny()
                        .isEmpty();

                if (!hasConflict) {
                    return candidate;
                }
            } else {
                // 单点提醒：使用原有逻辑
                LocalDateTime start = candidate.minusMinutes(CONFLICT_WINDOW_MINUTES);
                LocalDateTime end = candidate.plusMinutes(CONFLICT_WINDOW_MINUTES);

                List<Reminder> reminders = reminderRepository.findByUserIdAndRemindTimeBetween(userId, start, end);
                boolean hasConflict = reminders.stream()
                        .filter(r -> r.getStatus() != ReminderStatus.CANCELLED)
                        .filter(r -> r.getConfirmStatus() == ConfirmStatus.CONFIRMED)
                        .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                        .findAny()
                        .isPresent();

                if (!hasConflict) {
                    return candidate;
                }
            }

            candidate = candidate.plusMinutes(windowMinutes);
        }

        // 2小时内都找不到，返回2小时后的时间
        return maxSearchTime;
    }
}
