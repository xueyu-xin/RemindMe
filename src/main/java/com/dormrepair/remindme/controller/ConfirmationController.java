package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.service.ConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
}