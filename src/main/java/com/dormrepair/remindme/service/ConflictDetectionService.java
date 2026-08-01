package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

// 提醒冲突检测：在新建/修改提醒时检查时间冲突，并给出可读的冲突描述和推荐空档时间
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictDetectionService {

    private final ReminderRepository reminderRepository;

    // 单点提醒的冲突窗口：前后各 5 分钟视为冲突
    private static final int CONFLICT_WINDOW_MINUTES = 5;

    // 单点提醒冲突检测的便捷重载，endTime 传 null
    // userId              用户ID
    // remindTime          提醒时间
    // excludeReminderId   修改场景下排除自身提醒，可为 null
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime, Long excludeReminderId) {
        return checkConflict(userId, remindTime, null, excludeReminderId);
    }

    // 冲突检测主逻辑：区分时间段提醒和单点提醒分别处理
    // userId              用户ID
    // remindTime          提醒时间
    // endTime             结束时间，时间段提醒时非空，单点为 null
    // excludeReminderId   排除自身提醒，可为 null
    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime, LocalDateTime endTime, Long excludeReminderId) {
        List<Reminder> conflicts;

        if (endTime != null) {
            // 时间段提醒：按区间重叠查询
            log.debug("检测时间段冲突: userId={}, remindTime={}, endTime={}, excludeId={}",
                    userId, remindTime, endTime, excludeReminderId);

            conflicts = reminderRepository.findOverlappingReminders(userId, remindTime, endTime);

            // 排除自身
            conflicts = conflicts.stream()
                    .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                    .collect(Collectors.toList());
        } else {
            // 单点提醒：以 ±5 分钟为窗口构建查询范围，统一用 findOverlappingReminders 检测
            // 这样能正确检测已有时间段提醒（endTime 不为空）与单点提醒的重叠
            LocalDateTime start = remindTime.minusMinutes(CONFLICT_WINDOW_MINUTES);
            LocalDateTime end = remindTime.plusMinutes(CONFLICT_WINDOW_MINUTES);

            log.debug("检测单点冲突: userId={}, remindTime={}, 范围={} ~ {}, excludeId={}",
                    userId, remindTime, start, end, excludeReminderId);

            // findOverlappingReminders 已包含 status != CANCELLED 和 confirmStatus = CONFIRMED 过滤
            List<Reminder> reminders = reminderRepository.findOverlappingReminders(userId, start, end);

            // 排除自身
            conflicts = reminders.stream()
                    .filter(r -> excludeReminderId == null || !r.getId().equals(excludeReminderId))
                    .collect(Collectors.toList());
        }

        if (conflicts.isEmpty()) {
            log.debug("未检测到冲突: userId={}, remindTime={}", userId, remindTime);
            return new ConflictResponse(false, null, null);
        }

        log.info("检测到 {} 个冲突: userId={}, remindTime={}", conflicts.size(), userId, remindTime);

        // 映射为响应项，携带 endTime 以区分时间段提醒
        List<ConflictResponse.ConflictItem> items = conflicts.stream()
                .map(r -> new ConflictResponse.ConflictItem(r.getId(), r.getTitle(), r.getRemindTime(), r.getEndTime(), r.getLocation()))
                .collect(Collectors.toList());

        // 向后找一个空闲窗口作为建议时间
        LocalDateTime suggestedTime = calculateSuggestedTime(userId, remindTime, endTime, excludeReminderId);

        return new ConflictResponse(true, items, suggestedTime);
    }

    // 是否存在冲突的便捷判断，单点版本
    public boolean hasConflict(Long userId, LocalDateTime remindTime, Long excludeReminderId) {
        return hasConflict(userId, remindTime, null, excludeReminderId);
    }

    // 是否存在冲突，支持时间段提醒
    public boolean hasConflict(Long userId, LocalDateTime remindTime, LocalDateTime endTime, Long excludeReminderId) {
        ConflictResponse response = checkConflict(userId, remindTime, endTime, excludeReminderId);
        return response.isHasConflict();
    }

    // 生成可读的冲突描述文本，单个和多个冲突分别拼装
    public String getConflictDescription(List<ConflictResponse.ConflictItem> conflicts, LocalDateTime newRemindTime) {
        if (conflicts == null || conflicts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("您已有提醒");

        if (conflicts.size() == 1) {
            // 单个冲突：标题 + 时间
            ConflictResponse.ConflictItem item = conflicts.get(0);
            String timeStr = item.getRemindTime().format(TimeUtils.TIME_FORMATTER);
            sb.append("「").append(item.getTitle()).append("」在 ").append(timeStr);
        } else {
            // 多个冲突：列出数量并逐个拼接
            sb.append("（").append(conflicts.size()).append("个）：");
            for (int i = 0; i < conflicts.size(); i++) {
                ConflictResponse.ConflictItem item = conflicts.get(i);
                String timeStr = item.getRemindTime().format(TimeUtils.TIME_FORMATTER);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append("「").append(item.getTitle()).append("」").append(timeStr);
            }
        }

        String newTimeStr = newRemindTime.format(TimeUtils.TIME_FORMATTER);
        sb.append("，当前提醒 ").append(newTimeStr).append(" 与其冲突");

        return sb.toString();
    }

    // 单点版本的建议时间计算，委托给时间段版本
    private LocalDateTime calculateSuggestedTime(Long userId, LocalDateTime fromTime, Long excludeReminderId) {
        return calculateSuggestedTime(userId, fromTime, null, excludeReminderId);
    }

    // 建议时间：从冲突起点向后递推，找到第一个无冲突窗口；2 小时内找不到则返回 2 小时后
    // endTime 在时间段提醒场景下决定窗口步长
    private LocalDateTime calculateSuggestedTime(Long userId, LocalDateTime fromTime, LocalDateTime endTime, Long excludeReminderId) {
        // 时间段提醒时，步长取时间段长度与 30 分钟的较大者
        int windowMinutes = CONFLICT_WINDOW_MINUTES;
        if (endTime != null) {
            long durationMinutes = java.time.Duration.between(fromTime, endTime).toMinutes();
            windowMinutes = (int) Math.max(durationMinutes, CONFLICT_WINDOW_MINUTES);
        }

        LocalDateTime candidate = fromTime.plusMinutes(windowMinutes);
        LocalDateTime maxSearchTime = fromTime.plusHours(2);

        // 在 2 小时窗口内逐步试探
        while (candidate.isBefore(maxSearchTime)) {
            if (endTime != null) {
                // 时间段提醒：用重叠查询检测候选窗口是否冲突
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
                // 单点提醒：±30 分钟窗口内查冲突
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

        // 2 小时内都找不到空档，直接返回 2 小时后
        return maxSearchTime;
    }
}
