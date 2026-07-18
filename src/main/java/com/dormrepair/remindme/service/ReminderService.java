package com.dormrepair.remindme.service;

import com.dormrepair.remindme.dto.request.ReminderCreateRequest;
import com.dormrepair.remindme.dto.request.ReminderUpdateRequest;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.ReminderResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.entity.User;
import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.dormrepair.remindme.enums.ReminderStatus;
import com.dormrepair.remindme.repository.ReminderRepository;
import com.dormrepair.remindme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提醒业务逻辑服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final UserRepository userRepository;

    private static final int CONFLICT_WINDOW_MINUTES = 30;

    @Transactional
    public ReminderResponse createReminder(ReminderCreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        ConflictResponse conflict = checkConflict(request.getUserId(), request.getRemindTime());
        if (conflict.isHasConflict()) {
            throw new IllegalArgumentException("提醒时间与已有提醒冲突");
        }

        Reminder reminder = new Reminder();
        reminder.setUserId(request.getUserId());
        reminder.setTitle(request.getTitle());
        reminder.setRemindTime(request.getRemindTime());
        reminder.setTimezone(request.getTimezone());
        reminder.setAdvanceMinutes(request.getAdvanceMinutes() != null ? request.getAdvanceMinutes() : user.getDefaultAdvanceMinutes());
        reminder.setCronExpression(request.getCronExpression());
        reminder.setLocation(request.getLocation());
        reminder.setPriority(Priority.valueOf(request.getPriority()));
        reminder.setSource(ReminderSource.valueOf(request.getSource()));

        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    @Transactional
    public ReminderResponse updateReminder(Long id, ReminderUpdateRequest request, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);

        if (request.getTitle() != null) {
            reminder.setTitle(request.getTitle());
        }
        if (request.getRemindTime() != null) {
            reminder.setRemindTime(request.getRemindTime());
        }
        if (request.getTimezone() != null) {
            reminder.setTimezone(request.getTimezone());
        }
        if (request.getAdvanceMinutes() != null) {
            reminder.setAdvanceMinutes(request.getAdvanceMinutes());
        }
        if (request.getCronExpression() != null) {
            reminder.setCronExpression(request.getCronExpression());
        }
        if (request.getLocation() != null) {
            reminder.setLocation(request.getLocation());
        }
        if (request.getPriority() != null) {
            reminder.setPriority(Priority.valueOf(request.getPriority()));
        }

        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    @Transactional
    public void cancelReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setStatus(ReminderStatus.CANCELLED);
        reminderRepository.save(reminder);
    }

    public List<ReminderResponse> getRemindersByUser(Long userId) {
        return reminderRepository.findByUserIdAndStatus(userId, ReminderStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReminderResponse confirmReminder(Long id, Long userId) {
        Reminder reminder = getReminderByIdAndUserId(id, userId);
        reminder.setConfirmStatus(ConfirmStatus.CONFIRMED);
        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    public List<ReminderResponse> getPendingReminders(Long userId) {
        return reminderRepository.findByUserIdAndConfirmStatus(userId, ConfirmStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ConflictResponse checkConflict(Long userId, LocalDateTime remindTime) {
        LocalDateTime start = remindTime.minusMinutes(CONFLICT_WINDOW_MINUTES);
        LocalDateTime end = remindTime.plusMinutes(CONFLICT_WINDOW_MINUTES);

        List<Reminder> conflicts = reminderRepository.findByUserIdAndRemindTimeBetween(userId, start, end);

        if (conflicts.isEmpty()) {
            return new ConflictResponse(false, null, null);
        }

        List<ConflictResponse.ConflictItem> items = conflicts.stream()
                .map(r -> new ConflictResponse.ConflictItem(r.getId(), r.getTitle(), r.getRemindTime()))
                .collect(Collectors.toList());

        LocalDateTime suggestedTime = end;
        while (suggestedTime.isBefore(remindTime.plusHours(2))) {
            List<Reminder> check = reminderRepository.findByUserIdAndRemindTimeBetween(
                    userId,
                    suggestedTime.minusMinutes(CONFLICT_WINDOW_MINUTES),
                    suggestedTime.plusMinutes(CONFLICT_WINDOW_MINUTES)
            );
            if (check.isEmpty()) {
                break;
            }
            suggestedTime = suggestedTime.plusMinutes(CONFLICT_WINDOW_MINUTES);
        }

        return new ConflictResponse(true, items, suggestedTime);
    }

    private Reminder getReminderByIdAndUserId(Long id, Long userId) {
        return reminderRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在或无权操作"));
    }

    private ReminderResponse toResponse(Reminder reminder) {
        ReminderResponse response = new ReminderResponse();
        response.setId(reminder.getId());
        response.setUserId(reminder.getUserId());
        response.setTitle(reminder.getTitle());
        response.setRemindTime(reminder.getRemindTime());
        response.setTimezone(reminder.getTimezone());
        response.setAdvanceMinutes(reminder.getAdvanceMinutes());
        response.setCronExpression(reminder.getCronExpression());
        response.setLocation(reminder.getLocation());
        response.setPriority(reminder.getPriority().name());
        response.setStatus(reminder.getStatus().name());
        response.setConfirmStatus(reminder.getConfirmStatus().name());
        response.setConfirmAttempts(reminder.getConfirmAttempts());
        response.setLastConfirmRemindAt(reminder.getLastConfirmRemindAt());
        response.setSource(reminder.getSource().name());
        response.setCreatedAt(reminder.getCreatedAt());
        response.setUpdatedAt(reminder.getUpdatedAt());
        return response;
    }
}
