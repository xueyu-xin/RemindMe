package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.LLMService;
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
    private final LLMService llmService;

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

    /**
     * 智能修改接口
     * <p>
     * 解析用户修改意图，自动更新对应字段并刷新确认会话
     * </p>
     */
    @PostMapping("/{sessionId}/smart-edit")
    public ResponseEntity<Map<String, Object>> smartEdit(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String userInput = (String) body.get("userInput");
            @SuppressWarnings("unchecked")
            Map<String, Object> currentData = (Map<String, Object>) body.get("currentData");

            if (userInput == null || userInput.trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少用户输入");
                return ResponseEntity.badRequest().body(error);
            }
            if (currentData == null || currentData.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少当前卡片数据");
                return ResponseEntity.badRequest().body(error);
            }

            // 调用 LLM 解析修改意图
            LLMService.EditIntent editIntent = llmService.parseEditIntent(userInput, currentData);
            log.info("修改意图解析结果: field={}, newValue={}, reason={}",
                    editIntent.getField(), editIntent.getNewValue(), editIntent.getReason());

            // 无法识别修改意图
            if ("unknown".equals(editIntent.getField())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无法识别修改意图，请明确说明要修改的内容（如：改成下午三点、地点改为会议室、改成开会）");
                return ResponseEntity.ok(result);
            }

            // 根据解析结果更新对应字段
            String fieldName = editIntent.getField();
            String newValue = editIntent.getNewValue();
            String fieldDisplayName = getFieldDisplayName(fieldName);

            // 复制 currentData 并更新对应字段
            Map<String, Object> updatedData = new HashMap<>(currentData);
            switch (fieldName) {
                case "time":
                    updatedData.put("remindTime", newValue);
                    break;
                case "location":
                    updatedData.put("location", newValue);
                    break;
                case "title":
                    updatedData.put("title", newValue);
                    break;
                case "priority":
                    updatedData.put("priority", newValue);
                    break;
                default:
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("message", "不支持修改的字段类型：" + fieldName);
                    return ResponseEntity.ok(error);
            }

            // 确保必要字段存在
            updatedData.putIfAbsent("priority", "NORMAL");
            updatedData.putIfAbsent("advanceMinutes", 10);
            updatedData.putIfAbsent("intentType", "REMINDER");
            updatedData.put("confidence", 0.95);

            // 调用 reparse 更新会话（后端会自动推送更新后的卡片）
            confirmationService.reparse(sessionId, userId, userInput, updatedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "✏️ 已修改" + fieldDisplayName + "为：" + newValue);
            result.put("field", fieldName);
            result.put("newValue", newValue);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("智能修改失败: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "修改失败，请重试");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 获取字段中文显示名
     */
    private String getFieldDisplayName(String field) {
        return switch (field) {
            case "time" -> "时间";
            case "location" -> "地点";
            case "title" -> "标题";
            case "priority" -> "优先级";
            default -> field;
        };
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