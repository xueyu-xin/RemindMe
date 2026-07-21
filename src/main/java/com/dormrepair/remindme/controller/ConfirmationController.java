package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.service.ConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/confirmations")
@RequiredArgsConstructor
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    @PostMapping("/{sessionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.confirm(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.cancel(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{sessionId}/edit")
    public ResponseEntity<Map<String, Object>> edit(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String newInput = (String) body.get("input");
            Map<String, Object> newParsedData = (Map<String, Object>) body.get("parsedData");

            if (newInput == null || newParsedData == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少必要参数");
                return ResponseEntity.badRequest().body(error);
            }

            confirmationService.reparse(sessionId, userId, newInput, newParsedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "已重新解析");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{sessionId}/execute/confirm")
    public ResponseEntity<Map<String, Object>> executeConfirm(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteConfirm(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{sessionId}/execute/later")
    public ResponseEntity<Map<String, Object>> executeLater(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteLater(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{sessionId}/execute/cancel")
    public ResponseEntity<Map<String, Object>> executeCancel(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteCancel(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/{sessionId}/data")
    public ResponseEntity<Map<String, Object>> getData(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        Map<String, Object> data = confirmationService.getSessionData(sessionId);
        return ResponseEntity.ok(data);
    }

    @PostMapping("/{sessionId}/reparse")
    public ResponseEntity<Map<String, Object>> reparse(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.get("title");
            String remindTime = (String) body.get("remindTime");
            String location = (String) body.get("location");
            String priority = (String) body.get("priority");
            Integer advanceMinutes = (Integer) body.get("advanceMinutes");

            if (title == null || remindTime == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少必要参数");
                return ResponseEntity.badRequest().body(error);
            }

            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", title);
            parsedData.put("remindTime", remindTime);
            parsedData.put("location", location);
            parsedData.put("priority", priority != null ? priority : "NORMAL");
            parsedData.put("advanceMinutes", advanceMinutes != null ? advanceMinutes : 10);
            parsedData.put("intentType", "REMINDER");
            parsedData.put("confidence", 0.95);

            confirmationService.reparse(sessionId, userId, title, parsedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "已重新解析");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/action")
    public ResponseEntity<Map<String, Object>> action(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        String action = (String) body.get("action");
        Long userId = body.containsKey("userId") ? Long.parseLong(String.valueOf(body.get("userId"))) : null;

        if (sessionId == null || action == null || userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "缺少必要参数");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = new HashMap<>();
            if ("confirm".equals(action)) {
                confirmationService.confirmSingle(userId, sessionId);
                result.put("success", true);
                result.put("message", "已确认");
            } else if ("cancel".equals(action)) {
                boolean success = confirmationService.cancelSingle(userId, sessionId);
                result.put("success", success);
                result.put("message", success ? "已取消" : "操作失败");
            } else {
                result.put("success", false);
                result.put("message", "无效的操作类型");
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/action-all")
    public ResponseEntity<Map<String, Object>> actionAll(@RequestBody Map<String, Object> body) {
        Long userId = body.containsKey("userId") ? Long.parseLong(String.valueOf(body.get("userId"))) : null;
        String action = (String) body.get("action");

        if (userId == null || action == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "缺少必要参数");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = new HashMap<>();
            if ("confirmAll".equals(action)) {
                List<Reminder> reminders = confirmationService.confirmAll(userId);
                result.put("success", true);
                result.put("message", "已确认全部 " + reminders.size() + " 个行程");
            } else if ("cancelAll".equals(action)) {
                int count = confirmationService.cancelAll(userId);
                result.put("success", true);
                result.put("message", "已取消全部 " + count + " 个行程");
            } else {
                result.put("success", false);
                result.put("message", "无效的操作类型");
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}