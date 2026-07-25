package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.agent.AgentResponse;
import com.dormrepair.remindme.agent.AgentService;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.entity.ConfirmationSession;
import com.dormrepair.remindme.enums.IntentType;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.DialogStatsService;
import com.dormrepair.remindme.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConfirmationService confirmationService;
    private final LLMService llmService;
    private final DialogStatsService dialogStatsService;
    private final AgentService agentService;

    @Value("${agent.enabled:false}")
    private boolean agentEnabled;

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> request) {
        Long userId = request.containsKey("userId") ? Long.parseLong(String.valueOf(request.get("userId"))) : null;
        String text = (String) request.get("text");

        if (userId == null || text == null || text.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "参数错误");
            return ResponseEntity.badRequest().body(error);
        }

        text = text.trim();

        // 提取可选的GPS坐标
        Double latitude = request.containsKey("latitude") ? toDouble(request.get("latitude")) : null;
        Double longitude = request.containsKey("longitude") ? toDouble(request.get("longitude")) : null;

        // Agent模式：如果启用Agent，调用AgentService处理
        if (agentEnabled) {
            try {
                Map<String, Object> context = null;
                if (latitude != null && longitude != null) {
                    context = new HashMap<>();
                    context.put("latitude", latitude);
                    context.put("longitude", longitude);
                }
                AgentResponse agentResponse = agentService.processRequest(userId, text, context);
                
                Map<String, Object> response = new HashMap<>();
                
                if (agentResponse.isSuccess()) {
                    switch (agentResponse.getType()) {
                        case TEXT:
                            response.put("success", true);
                            response.put("message", agentResponse.getContent());
                            response.put("type", "text");
                            break;
                        case TOOL_CALL:
                            response.put("success", true);
                            response.put("type", "tool_call");
                            response.put("toolName", agentResponse.getToolName());
                            response.put("toolArgs", agentResponse.getToolArgs());
                            break;
                        case CONFIRMATION:
                            response.put("success", true);
                            response.put("type", "confirmation_required");
                            response.put("message", agentResponse.getContent());
                            response.put("needConfirmation", true);
                            response.put("sessionId", agentResponse.getSessionId());
                            response.put("data", agentResponse.getConfirmationData());
                            break;
                        default:
                            response.put("success", true);
                            response.put("message", agentResponse.getContent());
                    }
                } else {
                    response.put("success", false);
                    response.put("message", agentResponse.getErrorMessage());
                }
                
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                log.error("Agent处理请求失败，回退到原有逻辑: userId={}, error={}", userId, e.getMessage(), e);
                // 回退到原有逻辑
            }
        }

        List<com.dormrepair.remindme.entity.ConfirmationSession> pendingSessions = confirmationService.getPendingSessions(userId);
        com.dormrepair.remindme.entity.ConfirmationSession pendingSession = pendingSessions.isEmpty() ? null : pendingSessions.get(0);

        try {
            if (text.startsWith("确认") && text.length() > 2) {
                String keyword = text.substring(2).trim();
                com.dormrepair.remindme.entity.Reminder reminder = confirmationService.confirmByTitle(userId, keyword);
                String timeStr = reminder.getRemindTime() != null 
                        ? reminder.getRemindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) 
                        : "未设置";
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "✅ 已确认：\"" + reminder.getTitle() + "\"，" + timeStr + " 执行");
                return ResponseEntity.ok(response);
            }

            if (text.startsWith("取消") && text.length() > 2) {
                String keyword = text.substring(2).trim();
                boolean success = confirmationService.cancelByTitle(userId, keyword);
                Map<String, Object> response = new HashMap<>();
                response.put("success", success);
                response.put("message", success ? "❌ 已取消" : "未找到匹配的行程");
                return ResponseEntity.ok(response);
            }

            if (isConfirm(text)) {
                List<com.dormrepair.remindme.entity.ConfirmationSession> sessions = confirmationService.getPendingSessions(userId);
                if (sessions.isEmpty()) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "当前没有待确认的行程");
                    return ResponseEntity.ok(response);
                } else if (sessions.size() == 1) {
                    com.dormrepair.remindme.entity.ConfirmationSession session = sessions.get(0);
                    confirmationService.pushSingleConfirmCard(userId, session);
                    Map<String, Object> response = new HashMap<>();
                    response.put("needConfirmation", true);
                    response.put("type", "single_confirm_card");
                    response.put("data", confirmationService.getSessionData(session.getSessionId()));
                    return ResponseEntity.ok(response);
                } else {
                    confirmationService.pushMultiConfirmCard(userId, sessions);
                    Map<String, Object> response = new HashMap<>();
                    response.put("needConfirmation", true);
                    response.put("type", "multi_confirm_card");
                    response.put("count", sessions.size());
                    return ResponseEntity.ok(response);
                }
            }

            if (text.contains("一键确认") || text.contains("全部确认")) {
                List<com.dormrepair.remindme.entity.Reminder> reminders = confirmationService.confirmAll(userId);
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "已确认全部 " + reminders.size() + " 个行程");
                return ResponseEntity.ok(response);
            }

            if (text.contains("一键取消") || text.contains("全部取消")) {
                int count = confirmationService.cancelAll(userId);
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "已取消全部 " + count + " 个行程");
                return ResponseEntity.ok(response);
            }

            if (isEdit(text)) {
                String editInput = extractEditContent(text);
                LLMService.ParsedResult result = llmService.parseReminder(editInput);

                Map<String, Object> parsedData = new HashMap<>();
                parsedData.put("title", result.title());
                parsedData.put("remindTime", result.remindTime() != null ? result.remindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                parsedData.put("intentType", result.intentType());
                parsedData.put("location", result.location());
                parsedData.put("priority", result.priority());
                parsedData.put("advanceMinutes", 10);
                parsedData.put("confidence", 0.95);

                if (pendingSession != null && "INFO_CONFIRM".equals(pendingSession.getStage())) {
                    confirmationService.reparse(pendingSession.getSessionId(), userId, editInput, parsedData);
                    Map<String, Object> response = new HashMap<>();
                    response.put("needConfirmation", true);
                    response.put("type", "confirmation_required");
                    response.put("message", "已重新解析，请确认");
                    return ResponseEntity.ok(response);
                } else {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "当前没有待确认的行程");
                    return ResponseEntity.ok(response);
                }
            }
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.ok(error);
        }

        if (pendingSession != null && "REMINDER_EXECUTE".equals(pendingSession.getStage())) {
            if (isConfirm(text) || isExecuteConfirm(text)) {
                Map<String, Object> result = confirmationService.handleExecuteConfirm(pendingSession.getSessionId(), userId);
                return ResponseEntity.ok(result);
            }

            if (isLater(text)) {
                Map<String, Object> result = confirmationService.handleExecuteLater(pendingSession.getSessionId(), userId);
                return ResponseEntity.ok(result);
            }

            if (isCancel(text)) {
                Map<String, Object> result = confirmationService.handleExecuteCancel(pendingSession.getSessionId(), userId);
                return ResponseEntity.ok(result);
            }
        }

        LLMService.ParsedResult result = llmService.parseReminder(text);

        if (userId != null) {
            IntentType intentType = IntentType.fromString(result.intentType());
            dialogStatsService.recordDialog(userId, text, intentType);
        }

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", result.title());
        // 如果时间为null，使用默认时间（当前时间+10分钟）
        LocalDateTime remindTime = result.remindTime() != null ? result.remindTime() : LocalDateTime.now().plusMinutes(10);
        parsedData.put("remindTime", remindTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        parsedData.put("intentType", result.intentType());
        parsedData.put("location", result.location());
        parsedData.put("priority", result.priority());
        parsedData.put("advanceMinutes", 10);
        parsedData.put("confidence", 0.95);

        if ("REMINDER".equals(result.intentType()) && userId != null) {
            ConfirmationCardResponse card = confirmationService.createSession(userId, text, parsedData);
            confirmationService.pushPendingSummary(userId);

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

    private boolean isConfirm(String text) {
        return text.matches("^(确认|是的|好|ok|OK|嗯|行|可以|确定|是|好的)$");
    }

    private boolean isCancel(String text) {
        return text.matches("^(取消|算了|不|不要|否|不用了|算了吧)$");
    }

    private boolean isEdit(String text) {
        String lower = text.toLowerCase();
        return lower.matches("^(改成|修改为|换一下|改一下|调整|变更|改为|改成|调整为).*$");
    }

    private String extractEditContent(String text) {
        return text.replaceAll("^(改成|修改为|换一下|改一下|调整|变更)", "").trim();
    }

    private boolean isExecuteConfirm(String text) {
        return text.matches("^(知道了|收到|好的|嗯|ok|OK)$");
    }

    private boolean isLater(String text) {
        return text.matches("^(稍后|等下|过会|再等|稍等|过一会儿)$");
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
