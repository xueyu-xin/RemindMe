package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.service.RecurringReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recurring-reminders")
@RequiredArgsConstructor
public class RecurringReminderController {

    private final RecurringReminderService recurringReminderService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam Long userId) {
        try {
            List<RecurringReminder> reminders = recurringReminderService.getRecurringRemindersByUser(userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", reminders);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            return recurringReminderService.getRecurringReminderById(id, userId)
                    .map(reminder -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("success", true);
                        result.put("data", reminder);
                        return ResponseEntity.ok(result);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.get("title");
            String remindTime = (String) body.get("remindTime");
            String startDateStr = (String) body.get("startDate");
            String endDateStr = (String) body.get("endDate");
            String repeatTypeStr = (String) body.get("repeatType");
            String location = (String) body.get("location");
            String priority = (String) body.get("priority");

            LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr, DATE_FORMATTER) : null;
            LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr, DATE_FORMATTER) : null;
            RepeatType repeatType = repeatTypeStr != null ? RepeatType.valueOf(repeatTypeStr) : null;

            RecurringReminder updated = recurringReminderService.updateRecurringReminder(
                    id, userId, title, remindTime, startDate, endDate, repeatType, location, priority);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已更新");
            result.put("data", updated);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.cancelRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已取消");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.pauseRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已暂停");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.resumeRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已恢复");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
