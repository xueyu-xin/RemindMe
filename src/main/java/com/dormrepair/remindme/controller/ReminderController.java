package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.ReminderCreateRequest;
import com.dormrepair.remindme.dto.request.ReminderUpdateRequest;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.ReminderResponse;
import com.dormrepair.remindme.enums.IntentType;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.DialogStatsService;
import com.dormrepair.remindme.service.LLMService;
import com.dormrepair.remindme.service.ReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提醒API接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;
    private final LLMService llmService;
    private final DialogStatsService dialogStatsService;
    private final ConfirmationService confirmationService;

    @PostMapping
    public ResponseEntity<ReminderResponse> createReminder(@Valid @RequestBody ReminderCreateRequest request) {
        ReminderResponse response = reminderService.createReminder(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<ReminderResponse>> getReminders(@RequestParam Long userId) {
        List<ReminderResponse> reminders = reminderService.getRemindersByUser(userId);
        return ResponseEntity.ok(reminders);
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ReminderResponse>> getPendingReminders(@RequestParam Long userId) {
        List<ReminderResponse> reminders = reminderService.getPendingReminders(userId);
        return ResponseEntity.ok(reminders);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReminderResponse> updateReminder(
            @PathVariable Long id,
            @Valid @RequestBody ReminderUpdateRequest request,
            @RequestParam Long userId) {
        ReminderResponse response = reminderService.updateReminder(id, request, userId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancelReminder(
            @PathVariable Long id,
            @RequestParam Long userId) {
        reminderService.cancelReminder(id, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "提醒已取消");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/check-conflict")
    public ResponseEntity<ConflictResponse> checkConflict(
            @RequestParam Long userId,
            @RequestParam LocalDateTime remindTime) {
        ConflictResponse response = reminderService.checkConflict(userId, remindTime);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseReminder(@RequestBody Map<String, Object> request) {
        String userInput = (String) request.get("input");
        Long userId = request.containsKey("userId") ? Long.parseLong(String.valueOf(request.get("userId"))) : null;
        boolean skipConfirmation = request.containsKey("skipConfirmation") && (Boolean) request.get("skipConfirmation");
        
        LLMService.ParsedResult result = llmService.parseReminder(userInput);

        if (userId != null) {
            IntentType intentType = IntentType.fromString(result.intentType());
            dialogStatsService.recordDialog(userId, userInput, intentType);
        }

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", result.title());
        parsedData.put("remindTime", result.remindTime() != null ? result.remindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        parsedData.put("intentType", result.intentType());
        parsedData.put("location", result.location());
        parsedData.put("priority", result.priority());
        parsedData.put("advanceMinutes", 10);
        parsedData.put("confidence", 0.95);

        if ("REMINDER".equals(result.intentType()) && userId != null && !skipConfirmation) {
            ConfirmationCardResponse card = confirmationService.createSession(userId, userInput, parsedData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("needConfirmation", true);
            response.put("type", "confirmation_required");
            response.put("message", "需要确认提醒信息");
            response.put("data", card.getData());
            
            return ResponseEntity.ok(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("needConfirmation", false);
        response.put("title", result.title());
        response.put("remindTime", result.remindTime());
        response.put("intentType", result.intentType());
        response.put("location", result.location());
        response.put("priority", result.priority());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReminderResponse> confirmReminder(
            @PathVariable Long id,
            @RequestParam Long userId) {
        ReminderResponse response = reminderService.confirmReminder(id, userId);
        return ResponseEntity.ok(response);
    }
}
