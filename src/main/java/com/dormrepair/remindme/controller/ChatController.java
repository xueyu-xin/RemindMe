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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 聊天与 AI 解析接口控制器。统一前缀 /api/chat，承担用户自然语言输入→LLM 解析→确认会话创建的主流程、聊天历史查询、消息异步保存等能力；支持 agent.enabled 开关在「Agent 模式」和「传统确认卡片模式」之间切换
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    // 确认会话服务：维护待确认/待执行的确认卡片生命周期
    private final ConfirmationService confirmationService;
    // 大模型服务：自然语言→提醒信息解析、意图识别、编辑意图解析等核心 LLM 调用
    private final LLMService llmService;
    // 对话统计服务：记录对话消息、查询历史聊天记录
    private final DialogStatsService dialogStatsService;
    // Agent 核心调度器：agent.enabled=true 时启用，替代传统解析流程走 ReAct 循环
    private final AgentService agentService;
    // 用户服务：取用户默认提前提醒时间等偏好设置
    private final com.dormrepair.remindme.service.UserService userService;

    // Agent 模式总开关（配置文件 agent.enabled，默认关闭即走传统解析流程）
    @Value("${agent.enabled:false}")
    private boolean agentEnabled;

    // 聊天消息主入口：每一条自然语言输入 POST 到此
    // 处理流程：参数校验→GPS坐标提取→按 agent.enabled 走不同分支→Agent ReAct 模式失败时回退传统解析→确认卡片/文本响应
    // request 常用字段：userId(必)、text(必,用户输入)、latitude(可选)、longitude(可选)
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> request) {
        // 从请求体安全提取 userId / text，缺一直接返回 400
        Long userId = request.containsKey("userId") ? Long.parseLong(String.valueOf(request.get("userId"))) : null;
        String text = (String) request.get("text");

        if (userId == null || text == null || text.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "参数错误");
            return ResponseEntity.badRequest().body(error);
        }

        text = text.trim();

        // 可选 GPS 坐标：若传递经纬度会进入上下文，供天气、位置工具使用
        Double latitude = request.containsKey("latitude") ? toDouble(request.get("latitude")) : null;
        Double longitude = request.containsKey("longitude") ? toDouble(request.get("longitude")) : null;

        // Agent 模式分支：启用时走完整的 Agent 工具调用循环，失败时 catch 回退下面的传统解析流程
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
                            response.put("needConfirmation", true);
                            response.put("type", "confirmation_required");
                            response.put("message", agentResponse.getContent());
                            response.put("sessionId", agentResponse.getSessionId());
                            response.put("data", agentResponse.getConfirmationData());
                            // 周期提醒标识，供调用方识别
                            Map<String, Object> cdata = agentResponse.getConfirmationData();
                            if (cdata != null && cdata.get("data") instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> inner = (Map<String, Object>) cdata.get("data");
                                if (Boolean.TRUE.equals(inner.get("isRecurring"))) {
                                    response.put("isRecurring", true);
                                }
                            }
                            log.info("Agent返回CONFIRMATION: sessionId={}, isRecurring={}",
                                    agentResponse.getSessionId(), response.get("isRecurring"));
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
                LLMService.ParsedResult result = llmService.parseReminder(editInput, userId);

                Map<String, Object> parsedData = new HashMap<>();
                parsedData.put("title", result.title());
                parsedData.put("remindTime", result.remindTime() != null ? result.remindTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                parsedData.put("intentType", result.intentType());
                parsedData.put("location", result.location());
                parsedData.put("priority", result.priority());
                parsedData.put("advanceMinutes", result.advanceMinutes() != null ? result.advanceMinutes() : getDefaultAdvance(userId));
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

        LLMService.ParsedResult result = llmService.parseReminder(text, userId);

        if (userId != null) {
            IntentType intentType = IntentType.fromString(result.intentType());
            dialogStatsService.recordDialog(userId, text, intentType);
        }

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", result.title());
        // 时间解析：优先使用 LLM 解析结果，其次使用相对时间兜底，最后使用默认提前量
        Integer advance = result.advanceMinutes() != null ? result.advanceMinutes() : getDefaultAdvance(userId);
        LocalDateTime remindTime = result.remindTime();
        if (remindTime == null) {
            // LLM 未解析出时间，尝试相对时间兜底解析
            remindTime = parseRelativeTime(text);
            if (remindTime != null) {
                log.info("LLM 解析时间为空，使用相对时间兜底：{} → {}", text, remindTime);
                // 相对时间匹配成功时，更新 advance 为 0（已计算过时间）
                advance = 0;
            } else {
                // 兜底也失败，使用当前时间
                remindTime = LocalDateTime.now();
                log.info("时间兜底解析失败，使用当前时间 → {}", remindTime);
            }
        }
        parsedData.put("remindTime", remindTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        parsedData.put("intentType", result.intentType());
        parsedData.put("location", result.location());
        parsedData.put("priority", result.priority());
        parsedData.put("advanceMinutes", advance);
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

    // 判断是否为「确认」语义：支持多种口语化表达
    private boolean isConfirm(String text) {
        return text.matches("^(确认|是的|好|ok|OK|嗯|行|可以|确定|是|好的)$");
    }

    // 获取用户自定义的默认提前提醒分钟数；用户不存在或未配置时返回 10 分钟作为系统兜底
    private int getDefaultAdvance(Long userId) {
        if (userId == null) return 10;
        return userService.getUserById(userId)
                .map(u -> u.getDefaultAdvanceMinutes())
                .orElse(10);
    }

    // 相对时间兜底解析：当 LLM 无法解析"X分钟后"等相对时间表达时，使用正则表达式直接解析
    // 支持 "X分钟后"、"X小时后"、"X天后" 等格式
    private LocalDateTime parseRelativeTime(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        // 匹配 "X分钟后"
        Pattern minutePattern = Pattern.compile("(\\d+)\\s*分钟后");
        Matcher minuteMatcher = minutePattern.matcher(input);
        if (minuteMatcher.find()) {
            int minutes = Integer.parseInt(minuteMatcher.group(1));
            log.info("相对时间解析：匹配到 {}分钟后，计算结果为 {}", minutes, LocalDateTime.now().plusMinutes(minutes));
            return LocalDateTime.now().plusMinutes(minutes);
        }

        // 匹配 "X小时后"
        Pattern hourPattern = Pattern.compile("(\\d+)\\s*小时后");
        Matcher hourMatcher = hourPattern.matcher(input);
        if (hourMatcher.find()) {
            int hours = Integer.parseInt(hourMatcher.group(1));
            log.info("相对时间解析：匹配到 {}小时后，计算结果为 {}", hours, LocalDateTime.now().plusHours(hours));
            return LocalDateTime.now().plusHours(hours);
        }

        // 匹配 "X天后"
        Pattern dayPattern = Pattern.compile("(\\d+)\\s*天后");
        Matcher dayMatcher = dayPattern.matcher(input);
        if (dayMatcher.find()) {
            int days = Integer.parseInt(dayMatcher.group(1));
            log.info("相对时间解析：匹配到 {}天后，计算结果为 {}", days, LocalDateTime.now().plusDays(days));
            return LocalDateTime.now().plusDays(days);
        }

        // 匹配 "X秒后"（用于"马上"、"立刻"等场景的精确表达）
        Pattern secondPattern = Pattern.compile("(\\d+)\\s*秒后");
        Matcher secondMatcher = secondPattern.matcher(input);
        if (secondMatcher.find()) {
            int seconds = Integer.parseInt(secondMatcher.group(1));
            log.info("相对时间解析：匹配到 {}秒后，计算结果为 {}", seconds, LocalDateTime.now().plusSeconds(seconds));
            return LocalDateTime.now().plusSeconds(seconds);
        }

        return null;
    }

    // 判断是否为「取消」语义
    private boolean isCancel(String text) {
        return text.matches("^(取消|算了|不|不要|否|不用了|算了吧)$");
    }

    // 判断是否为「编辑/修改」语义：以编辑动词开头匹配
    private boolean isEdit(String text) {
        String lower = text.toLowerCase();
        return lower.matches("^(改成|修改为|换一下|改一下|调整|变更|改为|改成|调整为).*$");
    }

    // 从「改成XXXX」句式中截取真正的编辑内容（剥去前缀动词）
    private String extractEditContent(String text) {
        return text.replaceAll("^(改成|修改为|换一下|改一下|调整|变更)", "").trim();
    }

    // 判断是否为「到达确认」语义：知道了/收到等，表示接受即将执行的提醒
    private boolean isExecuteConfirm(String text) {
        return text.matches("^(知道了|收到|好的|嗯|ok|OK)$");
    }

    // 判断是否为「稍后再提醒」语义
    private boolean isLater(String text) {
        return text.matches("^(稍后|等下|过会|再等|稍等|过一会儿)$");
    }

    // 安全地将任意对象转为 Double：兼容 Number 类型、数字字符串；失败或空均返回 null
    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // 聊天历史查询接口：按时间倒序返回指定用户最近 N 条对话消息
    // userId - 查询目标用户（必填）
    // limit  - 返回条数上限，默认 50，最大 100，超出按 100 截断，<=0 时重置为 50
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (userId == null) {
                resp.put("success", false);
                resp.put("message", "userId 不能为空");
                return ResponseEntity.badRequest().body(resp);
            }
            // 限制最大条数防止一次返回过多导致卡顿/内存压力
            int actualLimit = limit != null ? Math.min(limit, 100) : 50;
            if (actualLimit <= 0) actualLimit = 50;

            List<Map<String, Object>> history = dialogStatsService.queryMessageHistory(userId, actualLimit);
            resp.put("success", true);
            resp.put("data", history);
            resp.put("count", history.size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("查询聊天历史失败: userId={}", userId, e);
            resp.put("success", false);
            resp.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    // 单条聊天消息异步落库接口：客户端展示后回传，用于把消息记录与解析统计解耦
    // request 必填字段：userId、role(user/bot)、content；可选字段 sessionId（同一会话内消息关联）
    @PostMapping("/save-message")
    public ResponseEntity<Map<String, Object>> saveMessage(@RequestBody Map<String, Object> request) {
        Map<String, Object> resp = new HashMap<>();
        try {
            Long userId = request.containsKey("userId") ? Long.parseLong(String.valueOf(request.get("userId"))) : null;
            String role = request.containsKey("role") ? String.valueOf(request.get("role")) : null;
            String content = request.containsKey("content") ? String.valueOf(request.get("content")) : null;

            if (userId == null) {
                resp.put("success", false);
                resp.put("message", "userId 不能为空");
                return ResponseEntity.badRequest().body(resp);
            }
            if (!"user".equals(role) && !"bot".equals(role)) {
                resp.put("success", false);
                resp.put("message", "role 必须是 user 或 bot");
                return ResponseEntity.badRequest().body(resp);
            }
            if (content == null || content.trim().isEmpty()) {
                resp.put("success", false);
                resp.put("message", "content 不能为空");
                return ResponseEntity.badRequest().body(resp);
            }

            String sessionId = request.containsKey("sessionId") ? String.valueOf(request.get("sessionId")) : null;
            dialogStatsService.saveMessage(userId, role, content.trim(), sessionId);

            resp.put("success", true);
            resp.put("message", "保存成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("保存聊天消息失败", e);
            resp.put("success", false);
            resp.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }
}
