package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.ConversationMemory;
import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.ToolCall;
import com.dormrepair.remindme.agent.ToolDefinition;
import com.dormrepair.remindme.enums.MemoryCategory;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// LLM 服务核心：将用户输入解析为结构化提醒、支撑带工具调用的对话、生成系统提示词、解析 LLM 输出中的工具调用 JSON
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    // 解析用户输入（兼容入口，使用默认 10 分钟提前量）
    public ParsedResult parseReminder(String userInput) {
        return parseReminder(userInput, null);
    }

    // 解析用户输入为结构化提醒，按 userId 读取用户的默认提前量
    public ParsedResult parseReminder(String userInput, Long userId) {
        int defaultAdvance = 10;
        if (userId != null) {
            defaultAdvance = userService.getUserById(userId)
                    .map(u -> u.getDefaultAdvanceMinutes())
                    .orElse(10);
        }

        log.info("开始解析用户输入: {}, userId: {}", userInput, userId);
        String prompt = buildPrompt(userInput);

        try {
            log.info("构建Prompt完成，开始调用LLM...");
            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText();

            log.info("LLM调用成功，响应内容: {}", content);

            JsonNode root = objectMapper.readTree(content);

            int advanceMinutes = root.has("advanceMinutes") && !root.get("advanceMinutes").isNull()
                    ? root.get("advanceMinutes").asInt() : defaultAdvance;

            return ParsedResult.builder()
                    .title(root.has("title") ? root.get("title").asText() : null)
                    .remindTime(root.has("remindTime") ? parseDateTime(root.get("remindTime").asText()) : null)
                    .endTime(root.has("endTime") && !root.get("endTime").isNull() ? parseDateTime(root.get("endTime").asText()) : null)
                    .intentType(root.has("intentType") ? root.get("intentType").asText() : "UNKNOWN")
                    .location(root.has("location") && !root.get("location").isNull() ? root.get("location").asText() : null)
                    .priority(root.has("priority") ? root.get("priority").asText() : Priority.NORMAL.name())
                    .note(root.has("note") && !root.get("note").isNull() ? root.get("note").asText() : null)
                    .advanceMinutes(advanceMinutes)
                    .build();

        } catch (Exception e) {
            log.error("LLM解析失败，错误类型: {}, 错误信息: {}", e.getClass().getName(), e.getMessage());
            log.error("完整异常堆栈:", e);
            return ParsedResult.builder()
                    .title(userInput)
                    .intentType("UNKNOWN")
                    .advanceMinutes(defaultAdvance)
                    .build();
        }
    }

    // ===== P1-2：统一 LLM 调用重试骨架（消除 chatWithTools / chatWithoutTools 两份 ~40 行重复 while 循环）=====

    /**
     * 统一 ChatModel 调用 + 重试骨架：
     * - 最大重试 3 次，指数退避 100ms → 200ms → 400ms
     * - 仅对 RestClientException 重试；4xx HttpStatusCodeException、JsonProcessingException 等立即失败
     * - 调用 label 用于日志区分（"chatWithTools" / "chatWithoutTools"）
     */
    private ChatResponse doChatWithRetry(Prompt prompt, String label) {
        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;
        ChatResponse response = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                response = chatModel.call(prompt);
                break;
            } catch (RestClientException e) {
                boolean is4xx = false;
                if (e instanceof HttpStatusCodeException) {
                    int statusCode = ((HttpStatusCodeException) e).getStatusCode().value();
                    if (statusCode >= 400 && statusCode < 500) {
                        is4xx = true;
                    }
                }
                if (is4xx) {
                    lastException = e;
                    break;
                }
                lastException = e;
                log.warn("{} 调用失败，第 {} 次重试: {}", label, attempt, e.getMessage());

                if (attempt >= maxRetries) {
                    break;
                }

                long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (response == null) {
            throw new RuntimeException(label + " 调用失败，已重试 " + maxRetries + " 次", lastException);
        }
        return response;
    }

    // 带工具调用的对话：拼装系统提示词、历史消息和当前输入，调用 LLM 返回响应文本（带重试）
    public String chatWithTools(String userInput, List<ToolDefinition> tools, List<Message> history) {
        log.info("开始带工具调用的对话: userInput={}, toolCount={}, historySize={}", userInput, tools.size(), history.size());

        List<org.springframework.ai.chat.messages.Message> messages = buildMessagesWithTools(userInput, tools, history);
        Prompt prompt = new Prompt(messages);

        ChatResponse response = doChatWithRetry(prompt, "chatWithTools");

        String content = response.getResult().getOutput().getText();
        log.info("工具调用对话响应: {}", content);

        if (content == null || content.isBlank()) {
            log.warn("LLM返回空响应！finishReason={}, tokensUsed={}",
                    response.getResult().getMetadata() != null ? response.getResult().getMetadata().getFinishReason() : "unknown",
                    response.getMetadata() != null && response.getMetadata().getUsage() != null
                            ? response.getMetadata().getUsage().getTotalTokens() : "unknown");
        }

        return content;
    }

    // 纯文本对话：不携带任何工具定义，用于引导语、总结等不需要工具调用的场景（带重试）
    // 使用最小化的系统提示词，避免 LLM 误判为需要调用工具
    public String chatWithoutTools(String userInput, List<Message> history) {
        log.info("开始纯文本对话: userInputLength={}, historySize={}", userInput.length(), history == null ? 0 : history.size());

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        String systemPrompt = """
                你是一个友好的提醒助手。请直接回复自然语言，不要输出任何JSON、工具调用或代码。
                保持口语化、温暖、简洁。""";
        messages.add(new SystemMessage(systemPrompt));

        if (history != null && !history.isEmpty()) {
            for (Message msg : history) {
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else if ("assistant".equals(msg.getRole())) {
                    messages.add(new AssistantMessage(msg.getContent()));
                }
            }
        }

        messages.add(new UserMessage(userInput));

        Prompt prompt = new Prompt(messages);

        ChatResponse response = doChatWithRetry(prompt, "chatWithoutTools");

        String content = response.getResult().getOutput().getText();
        log.info("纯文本对话响应: {}", content);

        if (content == null || content.isBlank()) {
            log.warn("纯文本对话返回空响应");
        }

        return content;
    }

    // 兜底总结：不挂任何工具，基于"本次对话"的消息切片让 LLM 生成最终自然语言总结
    // 触发场景：Agent 达到最大迭代仍未结束循环、或检测到重复工具调用需强制收尾
    // 调用方只能传本次对话切片，不能传全量历史；要求 LLM 分已完成/未完成/需询问三类回复
    public String generateSummaryFromHistory(List<Message> history) {
        log.info("开始生成兜底总结回复: currentTurnHistorySize={}", history == null ? 0 : history.size());

        String summaryPrompt = """
                你是智能提醒助手的收尾总结模块。当前对话已达到执行上限，需要你根据【本次对话】的全部消息生成最终自然语言回复。

                ⚠️ 你看到的对话历史包含【本次对话】的完整内容：
                1. 用户的原始发言（可能是一个新任务、也可能是对上一轮对话的补充/追问）；
                2. 用户发言后触发的所有工具调用及其执行结果；
                3. 系统注入的提示信息。
                你必须先理解用户【真实意图】（从 user 消息中还原），再结合工具执行结果进行总结。
                绝对禁止只机械地罗列"调了哪些工具"，而忽略用户到底要做什么。

                请严格遵守以下规则：
                1. 严禁再次输出任何 {"tool_call": ...} 格式的 JSON，只能输出自然语言回复。

                2. 【理解用户意图优先铁律】
                   你必须先阅读并吃透所有 user 消息的内容，搞清楚用户真正想要什么：
                   a) 用户是让你"设置提醒/查询天气/修改记忆/推荐餐厅…"，还是"补充上一轮的信息"，或"追问某件事"？
                   b) 如果用户是连续对话（例如上一轮你问了一个问题，这一轮用户回答你），请结合上下文理解意图，
                      不要把它当成一个全新的孤立任务。
                   c) 后面总结时，必须用"人话"对应用户的真实需求，不能只说工具名。

                3. 【反幻觉铁律】严禁编造、严禁脑补、严禁推测：
                   a) 不能把"没调用过工具"说成"已完成"；
                   b) 工具结果里的 success 字段是判断成功与否的唯一标准：
                      - success=true 才算成功；
                      - success=false 就是失败；
                   c) 对于【查询类工具】（retrieve_private_knowledge、get_all_private_memories、
                      retrieve_public_knowledge、query_weather_now、query_weather_forecast、
                      query_air_quality、query_weather_alert、query_reminders、get_reminder_detail、
                      get_user_preferences、get_location、get_period_status、get_current_time、
                      calculate_date、get_all_period_records 等），
                      如果 success=true，你【必须把与用户当前问题直接相关的查到的内容转述给用户】，
                      而不能只说"已完成检索"或"查询成功"。
                      特别注意：检索到多少条与回复无关！只转述能回答用户问题的那部分，
                      其余内容一个字都不要提。
                      ❌ 错误做法1：get_all_private_memories 返回 success=true count=22，你却说"没能读取到内容"
                      ❌ 错误做法2：用户只是存了条六级考试记忆，你却回复"记住了！另外我还记得你喜欢打篮球、
                                    不吃辣、在准备考研……"（把记忆库内容清单式倒给用户 = 严重错误）
                      ✅ 正确做法："记住了，你的六级是12月19号。"（只回应本次相关的内容）
                   d) 工具返回 message 里写了什么，你才能说什么；message 没写的，绝对不能自己补充。
                   e) result 字段中包含的具体数据（如 memories、forecast、weather 等字段里的内容）就是事实，
                      你可以也应当只引用其中与用户问题相关的部分来回答，不得全量转述。

                4. 必须按三类清晰回复：
                   ✅ 已完成的：
                      - 对应用户意图逐条说明做了什么（不要只写工具名，要写"帮你设置了…提醒""查到天气是…""记录了…记忆"等）；
                      - 查询类工具只转述与用户当前问题直接相关的关键信息（不要罗列原始JSON，用自然语言总结）；
                      - 检索返回的无关内容一律不提，宁少勿滥。
                   ❌ 未完成的：
                      - 用户要求但工具未成功执行（success=false）、或根本没调用对应工具的部分；
                      - 说明清楚原因（例如"缺少时间参数"、"工具调用失败：XX原因"）。
                   ❓ 需要询问的：
                      - 用户表述缺失关键参数，导致任务无法完成的部分；
                      - 明确向用户提问缺什么，给用户下一轮回复提供指引。
                三类都要覆盖，没有某类就省略；至少要有"✅ 已完成的"或"❌ 未完成的"其中之一。

                5. 回复语气要自然、温暖，像朋友聊天，可适当使用 emoji。
                6. 不要再提及"系统提示""达到上限""重复调用"等内部细节。
                7. 严禁出现"已经记录过啦/之前已经完成/你已经说过啦"等冗余话术（除非工具结果明确返回"已存在相同日程"）。
                8. 如果任务本身就是"查询信息"且 success=true，用户最想看到的是"查到的内容"，
                   而不是"我帮你查了"这种过程话，所以把查到的内容前置、说清楚。
                """;

        try {
            List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(summaryPrompt));

            if (history != null) {
                // 先把所有 user 消息内容汇总到最前面，方便 LLM 先理解用户整体意图
                StringBuilder userIntents = new StringBuilder();
                int userSeq = 1;
                for (Message msg : history) {
                    if ("user".equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                        userIntents.append("【用户意图").append(userSeq++).append("】").append(msg.getContent()).append("\n");
                    }
                }
                if (userIntents.length() > 0) {
                    messages.add(new UserMessage("以下是本次对话中用户的所有发言（按顺序），请先理解用户真实意图再看工具执行结果：\n" + userIntents));
                }

                // 再按原始顺序处理所有非user消息，并对查询类工具结果做摘要展开
                for (Message msg : history) {
                    if ("user".equals(msg.getRole())) {
                        continue; // user 消息已在上面汇总，避免重复
                    } else if ("assistant".equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                        messages.add(new AssistantMessage(msg.getContent()));
                    } else if ("system".equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                        messages.add(new SystemMessage(msg.getContent()));
                    } else if ("tool".equals(msg.getRole())) {
                        StringBuilder toolContent = new StringBuilder();
                        if (msg.getToolCall() != null) {
                            toolContent.append("工具调用: ").append(msg.getToolCall().getToolName())
                                    .append(", 参数: ").append(msg.getToolCall().getArguments());
                        }
                        if (msg.getContent() != null && !msg.getContent().isBlank()) {
                            if (toolContent.length() > 0) toolContent.append("\n");
                            toolContent.append("工具执行结果: ").append(msg.getContent());
                        }
                        if (msg.getToolResult() != null) {
                            if (toolContent.length() > 0) toolContent.append("\n");
                            ConversationMemory.ToolResult tr = msg.getToolResult();
                            toolContent.append("工具执行结果: ")
                                    .append("{success=").append(tr.isSuccess())
                                    .append(", tool=").append(tr.getToolName());
                            if (tr.getResult() != null) {
                                toolContent.append(", result=").append(tr.getResult());
                            }
                            toolContent.append("}");
                            // 查询类工具结果附加：把 result 里的关键信息展开成摘要，方便 LLM 转述
                            if (tr.isSuccess() && tr.getResult() != null) {
                                String summary = summarizeQueryToolResult(tr.getToolName(), String.valueOf(tr.getResult()));
                                if (summary != null && !summary.isBlank()) {
                                    toolContent.append("\n【结果摘要（请用于对用户回复）】").append(summary);
                                }
                            }
                        }
                        if (toolContent.length() > 0) {
                            messages.add(new UserMessage(toolContent.toString()));
                        }
                    }
                }
            }

            messages.add(new UserMessage("请根据以上对话历史，生成给用户的最终自然语言总结回复。"));

            Prompt prompt = new Prompt(messages);
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getText();
            log.info("兜底总结回复生成完成: {}", content);
            return content;
        } catch (Exception e) {
            log.error("生成兜底总结回复失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // 把查询类工具的 JSON 结果提取成自然语言摘要，供兜底总结 LLM 直接引用，避免转述出错
    @SuppressWarnings("unchecked")
    private String summarizeQueryToolResult(String toolName, String resultJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(resultJson, Map.class);
            StringBuilder sb = new StringBuilder();

            switch (toolName) {
                case "get_all_private_memories":
                case "retrieve_private_knowledge": {
                    Object count = map.get("count");
                    Object memories = map.get("memories");
                    if (count != null) sb.append("共").append(count).append("条记忆；");
                    if (memories instanceof List) {
                        List<Map<String, Object>> list = (List<Map<String, Object>>) memories;
                        // 按分类聚合内容；分类由 AI 从枚举中选定（编码字段），缺失时归入"未分类"
                        Map<String, List<String>> byCategory = new java.util.LinkedHashMap<>();
                        for (Map<String, Object> m : list) {
                            String content = m.get("content") != null ? m.get("content").toString() : "";
                            // 备注是用户手写的补充说明，AI 回答时要一并参考
                            String remark = m.get("remark") != null ? m.get("remark").toString() : "";
                            String line = remark.isEmpty() ? content : content + "（备注：" + remark + "）";
                            String category = m.get("category") != null ? m.get("category").toString() : "";
                            String categoryName = categoryDisplayName(category);
                            byCategory.computeIfAbsent(categoryName, k -> new ArrayList<>()).add(line);
                        }
                        for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
                            sb.append("\n  · ").append(e.getKey()).append("(").append(e.getValue().size()).append("条): ");
                            int max = Math.min(3, e.getValue().size());
                            for (int i = 0; i < max; i++) {
                                if (i > 0) sb.append("；");
                                sb.append(e.getValue().get(i));
                            }
                            if (e.getValue().size() > max) sb.append(" 等");
                        }
                    }
                    break;
                }
                case "retrieve_public_knowledge": {
                    Object count = map.get("count");
                    Object knowledge = map.get("knowledge");
                    if (count != null) sb.append("共").append(count).append("条手册内容；");
                    if (knowledge instanceof List) {
                        List<Map<String, Object>> list = (List<Map<String, Object>>) knowledge;
                        for (int i = 0; i < Math.min(3, list.size()); i++) {
                            Map<String, Object> m = list.get(i);
                            Object content = m.get("content");
                            if (content != null) sb.append("\n  · ").append(content.toString().length() > 80 ? content.toString().substring(0, 80) + "..." : content);
                        }
                    }
                    break;
                }
                case "query_weather_now": {
                    Object w = map.get("weather");
                    if (w instanceof Map) {
                        Map<String, Object> weather = (Map<String, Object>) w;
                        sb.append("当前天气：温度 ").append(weather.get("temperature")).append("℃，体感 ")
                                .append(weather.get("feelsLike")).append("℃，天气: ").append(weather.get("weather"));
                        if (weather.get("windDirection") != null) sb.append("，风向: ").append(weather.get("windDirection"));
                        if (weather.get("windScale") != null) sb.append(weather.get("windScale")).append("级");
                        if (weather.get("humidity") != null) sb.append("，湿度: ").append(weather.get("humidity")).append("%");
                    }
                    break;
                }
                case "query_weather_forecast": {
                    Object f = map.get("forecast");
                    if (f instanceof Map) {
                        Map<String, Object> forecast = (Map<String, Object>) f;
                        Object daily = forecast.get("daily");
                        Object days = forecast.get("days");
                        if (days != null) sb.append("未来").append(days).append("天预报：");
                        if (daily instanceof List) {
                            List<Map<String, Object>> list = (List<Map<String, Object>>) daily;
                            for (Map<String, Object> d : list) {
                                sb.append("\n  · ").append(d.get("date")).append(": ")
                                        .append(d.get("weatherDay") != null ? d.get("weatherDay") : "")
                                        .append("/").append(d.get("weatherNight") != null ? d.get("weatherNight") : "")
                                        .append("，气温 ").append(d.get("tempMin") != null ? d.get("tempMin") : "?")
                                        .append("~").append(d.get("tempMax") != null ? d.get("tempMax") : "?").append("℃");
                            }
                        }
                    }
                    break;
                }
                case "query_air_quality": {
                    Object aq = map.get("airQuality");
                    if (aq instanceof Map) {
                        Map<String, Object> air = (Map<String, Object>) aq;
                        sb.append("AQI: ").append(air.get("aqi")).append(" (").append(air.get("aqiDisplay")).append(")")
                                .append("，等级: ").append(air.get("level")).append(" ").append(air.get("category"));
                        if (air.get("primaryPollutant") != null) sb.append("，首要污染物: ").append(air.get("primaryPollutant"));
                        if (air.get("adviceGeneral") != null) sb.append("\n  建议: ").append(air.get("adviceGeneral"));
                    }
                    break;
                }
                case "query_weather_alert": {
                    Object hasAlerts = map.get("hasAlerts");
                    Object alerts = map.get("alerts");
                    sb.append(Boolean.TRUE.equals(hasAlerts) ? "有天气预警：" : "当前无天气预警。");
                    if (alerts instanceof List) {
                        List<Map<String, Object>> list = (List<Map<String, Object>>) alerts;
                        for (Map<String, Object> a : list) {
                            sb.append("\n  · ").append(a.get("severity") != null ? a.get("severity") : "")
                                    .append(" ").append(a.get("headline") != null ? a.get("headline") : "");
                        }
                    }
                    break;
                }
                case "query_reminders":
                case "get_reminder_detail": {
                    Object msg = map.get("message");
                    Object data = map.get("data");
                    if (msg != null) sb.append(msg);
                    if (data instanceof List) {
                        List<Map<String, Object>> list = (List<Map<String, Object>>) data;
                        sb.append(" 共").append(list.size()).append("条提醒：");
                        for (Map<String, Object> r : list) {
                            sb.append("\n  · 时间: ").append(r.get("reminderTime")).append("，内容: ").append(r.get("title"));
                        }
                    } else if (data instanceof Map) {
                        Map<String, Object> r = (Map<String, Object>) data;
                        sb.append("\n  · 时间: ").append(r.get("reminderTime")).append("，内容: ").append(r.get("title"))
                                .append("，状态: ").append(r.get("status"));
                    }
                    break;
                }
                case "get_user_preferences": {
                    Object prefs = map.get("preferences");
                    if (prefs instanceof Map) {
                        Map<String, Object> p = (Map<String, Object>) prefs;
                        sb.append("用户偏好：");
                        if (p.get("defaultCity") != null) sb.append("默认城市: ").append(p.get("defaultCity")).append("；");
                        if (p.get("defaultReminderAdvance") != null) sb.append("提前提醒: ").append(p.get("defaultReminderAdvance")).append("分钟；");
                        if (p.get("nickname") != null) sb.append("昵称: ").append(p.get("nickname"));
                    }
                    break;
                }
                case "get_location": {
                    Object loc = map.get("location");
                    if (loc instanceof Map) {
                        Map<String, Object> l = (Map<String, Object>) loc;
                        sb.append("位置信息：");
                        if (l.get("city") != null) sb.append("城市: ").append(l.get("city")).append("；");
                        if (l.get("address") != null) sb.append("地址: ").append(l.get("address")).append("；");
                        if (l.get("latitude") != null) sb.append("经纬度: ").append(l.get("latitude")).append(",").append(l.get("longitude"));
                    } else {
                        Object msg = map.get("message");
                        if (msg != null) sb.append(msg);
                    }
                    break;
                }
                case "get_period_status": {
                    Object status = map.get("status");
                    Object msg = map.get("message");
                    if (status instanceof Map) {
                        Map<String, Object> s = (Map<String, Object>) status;
                        sb.append("经期状态：");
                        if (s.get("phase") != null) sb.append("阶段: ").append(s.get("phase")).append("；");
                        if (s.get("daysSinceStart") != null) sb.append("距上次开始: ").append(s.get("daysSinceStart")).append("天；");
                        if (s.get("nextStartIn") != null) sb.append("距离下次预计: ").append(s.get("nextStartIn")).append("天");
                    } else if (msg != null) {
                        sb.append(msg);
                    }
                    break;
                }
                case "get_current_time":
                case "calculate_date": {
                    Object result = map.get("result");
                    Object msg = map.get("message");
                    if (result != null) sb.append(result);
                    else if (msg != null) sb.append(msg);
                    break;
                }
                default:
                    // 对于存储/修改/删除类工具，直接取 message
                    Object msg = map.get("message");
                    if (msg != null) sb.append(msg);
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("摘要查询工具结果失败 (tool={}): {}", toolName, e.getMessage());
            return null;
        }
    }

    // 记忆分类编码转中文名，供兜底摘要展示；未知编码或为空时归入"未分类"
    private String categoryDisplayName(String category) {
        if (category == null || category.isEmpty()) {
            return "未分类";
        }
        try {
            switch (MemoryCategory.valueOf(category)) {
                case PERSONAL_INFO: return "个人信息";
                case ACTIVITY: return "活动事务";
                case EVENT: return "重要日期";
                case PLAN: return "计划目标";
                case EXAM_SCHEDULE: return "考试安排";
                case HEALTH_CONDITION: return "健康状况";
                case DIET_PREFERENCE: return "饮食偏好";
                case HABIT: return "个人习惯";
                case INTEREST: return "兴趣爱好";
                case LANGUAGE_STYLE: return "语言风格";
                case OTHER: return "其他";
                default: return "未分类";
            }
        } catch (IllegalArgumentException e) {
            return "未分类";
        }
    }

    // 解析 LLM 响应，判断是否包含工具调用，非工具调用返回 null
    public ToolCall parseToolCall(String llmResponse) {
        List<ToolCall> calls = parseToolCalls(llmResponse);
        return calls.isEmpty() ? null : calls.get(0);
    }

    // 解析 LLM 响应中的所有工具调用，支持一次返回多个 {"tool_call":{...}}
    // 先尝试整段作为单个 JSON 解析，失败则用平衡括号扫描逐个提取顶层 JSON 对象
    public List<ToolCall> parseToolCalls(String llmResponse) {
        List<ToolCall> results = new ArrayList<>();
        if (llmResponse == null || llmResponse.isBlank()) {
            log.debug("LLM响应为空");
            return results;
        }

        // 调试日志：打印原始响应（截断）
        String truncatedResponse = llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse;
        log.debug("开始解析LLM响应: {}", truncatedResponse);

        // 先尝试整段作为单个 JSON 解析（最常见路径，性能最优）
        ToolCall single = tryParseSingleJson(llmResponse);
        if (single != null) {
            results.add(single);
            return results;
        }

        // 整段解析失败 → 多 JSON 拼接 / 文本夹杂 JSON 的情况
        // 用平衡括号扫描，逐个提取顶层 JSON 对象
        List<String> jsonObjects = extractAllTopLevelJsonObjects(llmResponse);
        if (jsonObjects.isEmpty()) {
            // 纯文本回复
            if (!llmResponse.trim().startsWith("{")) {
                log.debug("LLM返回纯文本回复，非工具调用");
            } else {
                log.debug("未解析到工具调用，响应内容: {}", truncatedResponse);
            }
            return results;
        }

        for (String json : jsonObjects) {
            ToolCall call = tryParseSingleJson(json);
            if (call != null) {
                results.add(call);
            }
        }
        if (!results.isEmpty()) {
            log.info("本次共解析到 {} 个工具调用", results.size());
        }
        return results;
    }

    // 尝试把一段文本当作单个 JSON 解析为 ToolCall，依次走：文本预处理 → 标准解析 → 修复后解析 → JSON片段提取解析
    // 预处理阶段会剥离 markdown 标记、BOM、首尾空白，并优先从混杂文本中提取第一个完整 JSON 对象
    private ToolCall tryParseSingleJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // ========== 文本预处理：剥离 markdown、BOM、空白 ==========
        String cleaned = stripMarkdownFences(text);
        cleaned = stripBom(cleaned);
        cleaned = cleaned.trim();

        // ========== 优先从混杂文本中提取 JSON 片段 ==========
        // 若 LLM 在 JSON 前后夹带自然语言（如"好的，马上为您创建..." + {...}），先把纯 JSON 抠出来
        String pureJson = extractJsonFragment(cleaned);
        if (pureJson != null && !pureJson.equals(cleaned)) {
            log.debug("检测到自然语言与JSON混杂，提取纯JSON片段: {}", pureJson.length() > 200 ? pureJson.substring(0, 200) + "..." : pureJson);
            // 后续三层解析全部基于提取出的纯 JSON
            cleaned = pureJson;
        }

        // 方式一：标准JSON解析
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            ToolCall result = parseStandardJson(root);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            log.debug("标准JSON解析失败: {}", e.getMessage());
        }

        // 方式二：尝试修复JSON后再解析（补括号、去尾随逗号、转义引号等）
        String fixedJson = fixJson(cleaned);
        if (!fixedJson.equals(cleaned)) {
            log.debug("尝试修复后的JSON: {}", fixedJson.length() > 200 ? fixedJson.substring(0, 200) + "..." : fixedJson);
            try {
                JsonNode root = objectMapper.readTree(fixedJson);
                ToolCall result = parseStandardJson(root);
                if (result != null) {
                    log.debug("修复后解析成功");
                    return result;
                }
            } catch (Exception e) {
                log.debug("修复后JSON解析失败: {}", e.getMessage());
            }
        }

        // 方式三：从原始文本中再次提取JSON片段（保险路径：cleaned 本身可能已经是纯 JSON 但修复失败，再试一次原始文本）
        String jsonFragment = extractJsonFragment(text);
        if (jsonFragment != null && !jsonFragment.equals(cleaned)) {
            log.debug("原始文本再次提取JSON片段: {}", jsonFragment.length() > 200 ? jsonFragment.substring(0, 200) + "..." : jsonFragment);
            try {
                JsonNode root = objectMapper.readTree(jsonFragment);
                ToolCall result = parseStandardJson(root);
                if (result != null) {
                    log.debug("原始文本提取JSON片段解析成功");
                    return result;
                }
            } catch (Exception e) {
                log.debug("原始文本提取JSON片段解析失败: {}", e.getMessage());
            }
        }

        return null;
    }

    // 剥离 markdown ```json ``` 或 ``` ``` 代码块标记，返回其中的纯文本
    private String stripMarkdownFences(String text) {
        if (text == null) return null;
        // 移除开头的 ```json / ```JSON / ```
        String result = text.replaceAll("(?is)^\\s*```(?:json)?\\s*", "");
        // 移除结尾的 ```
        result = result.replaceAll("(?is)\\s*```\\s*$", "");
        return result;
    }

    // 移除 UTF-8 BOM 标记（\uFEFF），它会导致 JSON 解析报 "Unexpected character"
    private String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    // 当 LLM 偷懒省略 tool_call.name 字段时，根据参数特征启发式推断工具名。
    // 匹配成功返回工具名字符串，否则返回 null。
    private String guessToolNameFromArguments(JsonNode toolCallNode) {
        if (toolCallNode == null || !toolCallNode.isObject()) {
            return null;
        }
        StringBuilder allKeys = new StringBuilder();
        toolCallNode.fields().forEachRemaining(entry -> allKeys.append(entry.getKey()).append(' '));
        String keys = allKeys.toString().toLowerCase();

        // 特征字段 → 工具名 的映射（顺序即优先级，先匹配先返回）
        if (keys.contains("remindtime") || keys.contains("remindertime") || keys.contains("remind_date")) {
            return "create_reminder";
        }
        if (keys.contains("recurring") || keys.contains("recurringrule") || keys.contains("recurring_days")) {
            return "create_recurring_reminder";
        }
        if (keys.contains("items") && toolCallNode.has("items") && toolCallNode.get("items").isArray()) {
            return "create_multi_reminders";
        }
        if (keys.contains("scheduleid") || keys.contains("schedule_id")) {
            if (keys.contains("confirm")) return "confirm_schedule_import";
            if (keys.contains("cancel")) return "cancel_schedule_import";
        }
        if (keys.contains("reminderid") || keys.contains("reminder_id")) {
            if (keys.contains("cancel")) return "cancel_reminder";
            if (keys.contains("update")) return "update_reminder";
            if (keys.contains("confirm")) return "confirm_reminder";
        }
        if (keys.contains("query") && keys.contains("weather")) return "query_weather_now";
        if (keys.contains("air") && keys.contains("quality")) return "query_air_quality";
        if (keys.contains("weather") && keys.contains("forecast")) return "query_weather_forecast";
        if (keys.contains("weather") && keys.contains("alert")) return "query_weather_alert";
        if (keys.contains("search") && keys.contains("city")) return "search_city";
        if (keys.contains("location")) return "get_location";
        if (keys.contains("period") && keys.contains("start")) return "update_period_start";
        if (keys.contains("period") && keys.contains("setting")) return "update_period_settings";
        if (keys.contains("period") && keys.contains("next")) return "get_next_period";
        if (keys.contains("period")) return "get_period_status";
        if (keys.contains("retrieve") && keys.contains("memory")) return "retrieve_private_knowledge";
        if (keys.contains("add") && keys.contains("memory")) return "add_private_memory";
        if (keys.contains("delete") && keys.contains("memory")) return "delete_private_memory";
        if (keys.contains("update") && keys.contains("memory")) return "update_private_memory";
        if (keys.contains("all") && keys.contains("memory")) return "get_all_private_memories";
        if (keys.contains("retrieve") && keys.contains("knowledge")) return "retrieve_public_knowledge";
        if (keys.contains("preference")) return "get_user_preferences";
        if (keys.contains("set") && keys.contains("preference")) return "set_user_preferences";
        if (keys.contains("current") && keys.contains("time")) return "get_current_time";
        if (keys.contains("calculate") && keys.contains("date")) return "calculate_date";
        if (keys.contains("export")) return "export_reminders";
        if (keys.contains("reminders") && keys.contains("cancel")) return "cancel_reminders_by_date";
        if (keys.contains("reminders") && (keys.contains("list") || keys.contains("query"))) return "query_reminders";
        if (keys.contains("reminder") && keys.contains("detail")) return "get_reminder_detail";

        return null;
    }

    // 用平衡括号扫描提取所有顶层 JSON 对象，跳过字符串内部的括号，仅保留含工具调用字段的片段
    private List<String> extractAllTopLevelJsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return objects;
        }

        int i = 0;
        int len = text.length();
        while (i < len) {
            // 找下一个 '{'
            int start = text.indexOf('{', i);
            if (start == -1) {
                break;
            }

            // 从 start 开始用平衡括号找到匹配的 '}'
            int balance = 0;
            boolean inString = false;
            boolean escaped = false;
            int end = -1;
            for (int j = start; j < len; j++) {
                char c = text.charAt(j);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (c == '{') {
                    balance++;
                } else if (c == '}') {
                    balance--;
                    if (balance == 0) {
                        end = j;
                        break;
                    }
                }
            }

            if (end == -1) {
                // 不平衡，无法提取，跳过这个 {
                i = start + 1;
                continue;
            }

            String candidate = text.substring(start, end + 1);
            // 只保留看起来像工具调用的片段（含 tool_call / function_call / tool 字段），避免误吞无关 JSON
            if (candidate.contains("\"tool_call\"") || candidate.contains("\"function_call\"") || candidate.contains("\"tool\"")) {
                objects.add(candidate);
            }
            i = end + 1;
        }
        return objects;
    }

    // 解析标准 JSON：兼容自定义 tool_call、OpenAI function_call 和简化 tool 三种格式
    // 当 LLM 偷懒省略 name 字段时，从参数特征推断工具名（如含 remindTime → create_reminder）
    private ToolCall parseStandardJson(JsonNode root) {
        // 检查是否包含工具调用（自定义格式）
        if (root.has("tool_call")) {
            JsonNode toolCallNode = root.get("tool_call");
            String toolName = toolCallNode.has("name") ? toolCallNode.get("name").asText() : null;

            // 无 name 字段 → 尝试从参数特征推断工具名
            if (toolName == null || toolName.isBlank()) {
                toolName = guessToolNameFromArguments(toolCallNode);
                if (toolName != null) {
                    log.warn("LLM输出tool_call缺少name字段，通过参数特征推断为: {}", toolName);
                } else {
                    log.error("LLM输出tool_call缺少name字段且无法推断，tool_call内容: {}", toolCallNode);
                }
            }

            if (toolName != null && !toolName.isBlank()) {
                Map<String, Object> arguments = new HashMap<>();
                if (toolCallNode.has("arguments")) {
                    JsonNode argsNode = toolCallNode.get("arguments");
                    if (argsNode.isObject()) {
                        argsNode.fields().forEachRemaining(entry -> {
                            arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                        });
                    }
                } else {
                    // LLM 偷懒：参数直接塞在 tool_call 里作为 sibling 字段，没有 arguments 包裹
                    toolCallNode.fields().forEachRemaining(entry -> {
                        String key = entry.getKey();
                        if (!"name".equals(key) && !"arguments".equals(key)) {
                            arguments.put(key, convertJsonNode(entry.getValue()));
                        }
                    });
                    if (!arguments.isEmpty()) {
                        log.warn("LLM输出tool_call缺少arguments包裹，参数作为sibling字段直接解析: {}", arguments.keySet());
                    }
                }

                log.info("解析到工具调用: toolName={}, arguments={}", toolName, arguments);
                return ToolCall.of(toolName, arguments);
            }
        }

        // 检查是否有function_call字段（OpenAI格式）
        if (root.has("function_call")) {
            JsonNode functionCallNode = root.get("function_call");
            String functionName = functionCallNode.has("name") ? functionCallNode.get("name").asText() : null;

            if (functionName != null && !functionName.isBlank()) {
                Map<String, Object> arguments = new HashMap<>();
                if (functionCallNode.has("arguments")) {
                    try {
                        JsonNode argsNode = functionCallNode.get("arguments");
                        if (argsNode.isObject()) {
                            argsNode.fields().forEachRemaining(entry -> {
                                arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                            });
                        } else if (argsNode.isTextual()) {
                            // 处理arguments是字符串的情况
                            JsonNode parsedArgs = objectMapper.readTree(argsNode.asText());
                            parsedArgs.fields().forEachRemaining(entry -> {
                                arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                            });
                        }
                    } catch (Exception e) {
                        log.warn("解析arguments失败: {}", e.getMessage());
                    }
                }

                log.info("解析到函数调用: functionName={}, arguments={}", functionName, arguments);
                return ToolCall.of(functionName, arguments);
            }
        }

        // 检查是否有tool字段（简化格式）
        if (root.has("tool")) {
            String toolName = root.get("tool").asText();
            if (toolName != null && !toolName.isBlank()) {
                Map<String, Object> arguments = new HashMap<>();
                if (root.has("arguments")) {
                    JsonNode argsNode = root.get("arguments");
                    if (argsNode.isObject()) {
                        argsNode.fields().forEachRemaining(entry -> {
                            arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                        });
                    }
                }

                log.info("解析到简化格式工具调用: toolName={}, arguments={}", toolName, arguments);
                return ToolCall.of(toolName, arguments);
            }
        }

        // 如果不包含工具调用，检查是否是纯文本响应
        if (root.has("content") && !root.has("tool_call") && !root.has("function_call")) {
            return null;
        }
        
        return null;
    }

    // 修复常见 JSON 格式问题：去除代码块标记、补齐闭合括号、移除尾随逗号、处理未转义引号
    private String fixJson(String json) {
        String fixed = json.trim();
        
        // 1. 移除Markdown代码块标记
        if (fixed.startsWith("```json")) {
            fixed = fixed.substring(7);
        } else if (fixed.startsWith("```")) {
            fixed = fixed.substring(3);
        }
        if (fixed.endsWith("```")) {
            fixed = fixed.substring(0, fixed.length() - 3);
        }
        fixed = fixed.trim();
        
        // 2. 处理缺少闭合括号的情况
        int openBraces = countOccurrences(fixed, '{');
        int closeBraces = countOccurrences(fixed, '}');
        while (closeBraces < openBraces) {
            fixed += "}";
            closeBraces++;
        }
        
        // 3. 处理缺少闭合方括号的情况
        int openBrackets = countOccurrences(fixed, '[');
        int closeBrackets = countOccurrences(fixed, ']');
        while (closeBrackets < openBrackets) {
            fixed += "]";
            closeBrackets++;
        }
        
        // 4. 移除尾随逗号
        fixed = fixed.replaceAll(",\\s*}", "}");
        fixed = fixed.replaceAll(",\\s*]", "]");
        
        // 5. 处理字符串中未转义的引号（简单处理）
        // 在JSON字符串中，未转义的引号会破坏结构，尝试修复
        fixed = fixUnescapedQuotes(fixed);
        
        return fixed;
    }

    // 修复 JSON 字符串值中未转义的引号，按后续字符是否为合法分隔符判断是否需转义
    private String fixUnescapedQuotes(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }
            
            if (c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }
            
            if (c == '"') {
                if (inString) {
                    // 检查下一个字符是否是有效的JSON字符
                    if (i + 1 < json.length()) {
                        char nextChar = json.charAt(i + 1);
                        if (nextChar != ',' && nextChar != '}' && nextChar != ']' && nextChar != ':') {
                            // 可能是未转义的引号，转义它
                            result.append("\\\"");
                            continue;
                        }
                    }
                    inString = false;
                } else {
                    inString = true;
                }
            }
            
            result.append(c);
        }
        
        return result.toString();
    }

    // 从文本中提取首个完整 JSON 片段（首 { 到匹配的 }），括号不匹配时返回 null
    private String extractJsonFragment(String text) {
        int start = text.indexOf('{');
        if (start == -1) {
            return null;
        }
        
        int end = text.lastIndexOf('}');
        if (end == -1 || end < start) {
            return null;
        }
        
        // 检查是否有完整的JSON结构
        String fragment = text.substring(start, end + 1);
        int openBraces = countOccurrences(fragment, '{');
        int closeBraces = countOccurrences(fragment, '}');
        
        if (openBraces == closeBraces) {
            return fragment;
        }
        
        // 尝试找到匹配的闭合括号
        int balance = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                balance++;
            } else if (text.charAt(i) == '}') {
                balance--;
                if (balance == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        
        return null;
    }

    // 统计字符在字符串中出现的次数
    private int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    // 将 JsonNode 递归转换为 Java 对象（文本/数值/布尔/数组/对象）
    private Object convertJsonNode(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            if (node.isInt() || node.isLong()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(convertJsonNode(item)));
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), convertJsonNode(entry.getValue()));
            });
            return map;
        } else {
            return null;
        }
    }

    // 构建包含工具定义的消息列表：系统提示词 + 历史对话（含工具消息）+ 当前用户输入
    private List<org.springframework.ai.chat.messages.Message> buildMessagesWithTools(String userInput,
            List<ToolDefinition> tools, List<Message> history) {
        
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        
        // 添加系统消息
        String systemPrompt = buildSystemPromptWithTools(tools);
        messages.add(new SystemMessage(systemPrompt));
        
        // 添加历史对话
        if (history != null && !history.isEmpty()) {
            for (Message msg : history) {
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else if ("assistant".equals(msg.getRole())) {
                    messages.add(new AssistantMessage(msg.getContent()));
                } else if ("system".equals(msg.getRole())) {
                    messages.add(new SystemMessage(msg.getContent()));
                } else if ("tool".equals(msg.getRole())) {
                    // 工具消息：包含工具调用或工具执行结果
                    StringBuilder toolContent = new StringBuilder();
                    if (msg.getToolCall() != null) {
                        toolContent.append("工具调用: ").append(msg.getToolCall().getToolName())
                                .append(", 参数: ").append(msg.getToolCall().getArguments());
                    }
                    if (msg.getToolResult() != null) {
                        if (toolContent.length() > 0) {
                            toolContent.append("\n");
                        }
                        toolContent.append("工具执行结果: ").append(msg.getToolResult().getResult());
                    }
                    // 将工具消息作为系统消息添加，让LLM知道工具执行结果
                    messages.add(new SystemMessage(toolContent.toString()));
                }
            }
        }
        
        // 添加用户当前输入
        messages.add(new UserMessage(userInput));
        
        return messages;
    }

    // ===== P1-1：公共抽取规则（两套提示词共用，消除重复文本）=====

    /** 地点提取规则：buildSystemPromptWithTools 和 buildPrompt 共用，统一编号式完整版 */
    private String buildLocationExtractionRules() {
        return """
            地点提取规则（提取 location 字段时严格遵守）：
            1. 识别"去XX"、"到XX"、"在XX"、"回XX"等介词后的场所名词作为 location
            2. 常见校园场所：食堂、图书馆、教室、操场、宿舍、超市、快递站、教学楼、体育馆、实验室、自习室、打印店等
            3. "去食堂吃饭" → location="食堂", title="吃饭"
            4. "去图书馆看书" → location="图书馆", title="看书"
            5. "到操场跑步" → location="操场", title="跑步"
            6. "去拿快递" → location="快递站", title="拿快递"
            7. "在三教开会" → location="三教", title="开会"
            8. "回宿舍" → location="宿舍", title="回宿舍"
            9. 地点词应从标题中排除，title 只保留动作/事项
            10. 如果没有明确地点，location 传/设为 null
            """;
    }

    /** 备注提取规则：buildSystemPromptWithTools 和 buildPrompt 共用，统一编号式完整版 */
    private String buildNoteExtractionRules() {
        return """
            备注提取规则（提取 note 字段时严格遵守）：
            1. 当用户输入中包含对提醒事项的补充说明、注意事项、需要准备的物品时，必须提取为 note 参数
            2. 常见模式："记得..."、"别忘了..."、"要带..."、"带上..."、"带XX"、"注意..."、"穿XX"等
            3. 物品类（雨伞、笔记本、借书证、学生证、礼物、水杯、钥匙等）一律放入 note，不要塞进 title
            4. "明天3点提醒我还书，记得带借书证" → title="还书", note="记得带借书证"
            5. "周五10点在三教开会，记得带笔记本电脑" → title="开会", location="三教", note="记得带笔记本电脑"
            6. "一分钟后提醒我带雨伞去快递站拿快递" → title="拿快递", location="快递站", note="带雨伞"
            7. "明天上午去图书馆，穿外套" → title="去图书馆", location="图书馆", note="穿外套"
            8. note 必须从 title 中排除，避免 title 出现"带XX"等冗余词
            9. 如果用户没有提供任何补充信息，note 传/设为 null
            """;
    }

    // 导出提醒规则：识别导出意图，日期范围缺失时必须先问清楚再调用工具
    private String buildExportRules() {
        return """
            导出提醒规则（用户要求导出/下载提醒时严格遵守）：
            1. 触发词："导出提醒"、"把提醒导出来"、"下载我的提醒"、"导出我的日程"等 → 调用 export_reminders
            2. 日期范围：用户明确说了（如"导出最近7天的提醒"、"导出这个月的"）→ 传 startDate/endDate；
               用户没说 → 必须先询问导出范围（如"想导出哪个时间段的提醒呢？全部还是最近一段？"），禁止直接调用工具
            3. 状态：默认导出全部四种状态（待推送/已完成/已取消/已过期），无需询问
            4. 格式：默认 Excel，无需询问；用户明确要图片时再说明支持 PNG
            5. 工具执行成功后，用自然温暖的语气告知导出条数，如"已经帮你把 N 条提醒导出来啦，文件马上开始下载～"
            """;
    }

    // 构建包含工具定义的系统提示词，拼入当前时间和工具列表
    private String buildSystemPromptWithTools(List<ToolDefinition> tools) {
        String currentDate = TimeUtils.getCurrentBeijingTime();

        StringBuilder toolsJson = new StringBuilder();
        if (tools != null && !tools.isEmpty()) {
            for (int i = 0; i < tools.size(); i++) {
                ToolDefinition tool = tools.get(i);
                if (i > 0) {
                    toolsJson.append(",\n");
                }
                toolsJson.append("  工具").append(i + 1).append(":\n");
                toolsJson.append("    名称: ").append(tool.getName()).append("\n");
                toolsJson.append("    描述: ").append(tool.getDescription()).append("\n");
                toolsJson.append("    参数: ").append(convertParametersToJson(tool.getParameters())).append("\n");
            }
        } else {
            toolsJson.append("  （无可用工具）");
        }

        String systemPrompt = buildCoreIdentity() + "\n\n"
                + buildJudgmentCriteria() + "\n\n"
                + buildWorkflow() + "\n\n"
                + buildRules() + "\n\n"
                + "当前时间：" + currentDate + "\n\n"
                + "可用工具列表：\n" + toolsJson + "\n\n"
                + buildLocationExtractionRules() + "\n\n"
                + buildNoteExtractionRules() + "\n\n"
                + buildExportRules() + "\n\n"
                + "请根据用户的请求，按照以上标准自主推理，决定是直接回复、主动检索、询问用户还是调用工具。";

        return systemPrompt;
    }

    // 核心身份：定义助手的角色定位与核心能力
    private String buildCoreIdentity() {
        return "你是一个智能提醒助手。你的核心能力是\"自主推理\"和\"主动预判\"，而不是执行开发者的规则清单。\n\n"
                + "你不需要开发者告诉你\"什么时候该做什么\"，你自己思考。";
    }

    // 三个判断标准：所有决策的根基
    private String buildJudgmentCriteria() {
        return """
                【三个判断标准——你所有决策的根基】

                标准1：这个信息值得长期记忆吗？
                思考：用户说的这句话，对未来决策有帮助吗？
                - 描述的是"长期状态"（健康、偏好、习惯、身份）还是"临时状态"（心情、当天状态）？
                - 未来遇到相关话题时，这句话能帮助我做出更好的决策吗？
                - 如果不记住，未来会不会错过重要的提醒？
                判断结果：值得记忆 → 调用 add_private_memory

                标准2：这件事和用户的哪些记忆有关？
                思考：用户要做的这件事，涉及什么领域？
                - 领域由你自己推理，不是查清单。比如：
                  - 吃火锅 → 可能涉及：过敏、忌口、肠胃、辣
                  - 去爬山 → 可能涉及：膝盖、体力、装备、天气
                  - 交作业 → 可能涉及：电脑、设备、截止日期
                判断结果：有关联可能 → 调用 retrieve_private_knowledge

                标准3：缺信息时应该问还是猜？
                - 如果缺关键信息（如时间、地点、具体事项），主动询问用户
                - 如果可以根据常识合理推测（如"爬山"一般在上午），可以给出建议并询问确认："你想几点出发呢？"
                - 禁止使用系统默认值替用户做决定
                """;
    }

    // 工作流程：每次对话都遵循的4步流程
    private String buildWorkflow() {
        return """
                【工作流程——每次对话都遵循】

                第1步：理解用户意图
                - 用户要我做什么？

                第2步：按照三个判断标准思考
                - 用户说的这句话，值得记忆吗？
                - 用户要做的事，和已有记忆有关吗？
                - 用户给的信息完整吗？

                第3步：执行
                - 如果需要记忆 → 调用 add_private_memory
                - 如果需要检索 → 调用 retrieve_private_knowledge
                - 如果缺信息 → 询问用户，不执行操作
                - 如果信息完整 → 调用对应工具（create_reminder 等）

                第4步：总结回复
                - 告诉用户你做了什么
                - 如果调用了工具，展示执行结果
                - 如果主动检索到相关记忆，在回复中体现
                """;
    }

    // 铁律：违反即严重错误
    private String buildRules() {
        return """
                【铁律——违反即严重错误】

                🚨🚨🚨【最高级别指令：工具调用输出格式铁律】🚨🚨🚨
                （此规则优先级高于所有其他规则，违反将导致用户请求彻底失败）

                当你决定调用工具时，你的回复必须直接是一个合法的 JSON 对象：以 { 开头，以 } 结尾。
                JSON 前后绝对不能出现任何自然语言——包括但不限于：
                ❌ "好的，马上为你创建..."
                ❌ "我这就去帮你..."
                ❌ "收到，正在..."
                ❌ 任何解释性前缀、后缀、换行、表情符号

                正确格式（严格照此输出，不要有任何变体）：
                ✅ {"tool_call":{"name":"工具名","arguments":{"参数1":"值1","参数2":"值2"}}}

                错误示例（绝对禁止）：
                ❌ 好的，我这就为你创建提醒。{"tool_call":{"name":"create_reminder",...}}
                ❌ {"tool_call":{"title":"拿快递","remindTime":"2026-10-20 16:56:00"}}  ← 缺少 name 字段
                ❌ 让我想想...\n{"tool_call":{...}}
                ❌ ```json\n{"tool_call":{...}}\n```  ← 禁止 markdown 包裹

                ⚠️ tool_call 里**必须**包含 name 字段，值为工具名（如 create_reminder、query_weather_now）。
                ⚠️ 参数统一放在 arguments 对象里，不要直接把参数当作 tool_call 的 sibling 字段。
                ⚠️ 如果你输出的 JSON 无法被系统解析，工具就不会被执行，用户等于白说。

                0. 先调用工具，再生成回复（最高优先级！）
                - 当你决定调用工具时，必须先输出 {"tool_call": {...}} JSON，然后系统会执行工具并返回结果
                - 收到工具执行结果后，你才能生成自然语言回复
                - 用户看不到工具调用过程，只看到你的最终回复。如果你只说"正在创建"但没真正调用工具，用户会收到一个空操作
                - 如果你不确定该不该调用工具，先调用工具，不要用语言替代行动

                1. 禁止假装知道：检索不到就说"我还没有这方面的信息"
                2. 禁止替用户做决定：缺信息就询问，不用默认值
                3. 禁止只执行不思考：用户说"去爬山"，你至少要想想"这涉及什么"
                4. 禁止在**最终自然语言回复**中输出工具调用 JSON：用户只看自然语言
                   （注意：中间步骤你输出的 tool_call JSON 会被系统拦截，用户看不到，但你仍需确保它格式正确）
                5. 禁止说"我帮你查询了"而不展示结果：查询类工具必须把结果转述给用户
                【输出格式】
                你可以使用 Markdown 语法来增强可读性：
                - 加粗：**文本** → 用于强调重点（时间、地点、提醒内容）
                - 斜体：*文本* → 用于次要说明
                - 换行：\n 分隔段落
                - 列表：- 或 1. 开头

                禁止使用代码块（```）或表格，这些在对话中不适用。
                加粗和斜体必须在同一行内成对出现，不要跨行使用。
                【决策前检索铁律】
                在给用户任何建议之前（如带伞、防晒、改时间等），必须先调用 retrieve_private_knowledge 查询用户是否有相关偏好。
                示例：用户说"明天去教学楼开会" → 先检索"带伞 教学楼 开会 偏好" → 命中"走地下通道不需要带伞" → 不提带伞

                【自动记忆铁律】
                当用户明确表达偏好或习惯时（如"我不需要带伞""我习惯走地下通道"），即使没有说"记住"，也必须调用 add_private_memory 存储。
                存储格式：用"你"称呼用户，如"你走地下通道去教学楼，下雨天不需要带伞"

                【天气判断逻辑】
                当用户提到要出门时，按以下流程：
                1. 判断是否需要出门（去教室/图书馆/海边/爬山等 → 是；在宿舍/在家 → 否）
                2. 如果需要出门，调用 query_weather_forecast 查天气
                3. 查天气后，先检索用户偏好，再给出建议
                4. 有"不需要带伞"等偏好 → 尊重用户，不提带伞
                5. 无相关偏好 → 正常给出建议
                【复合任务执行铁律】
                当用户请求涉及多个步骤时，你必须在所有步骤的工具都执行完后，才生成最终回复。

                判断标准：检查你的工具调用列表，是否包含了完成该任务所需的全部工具。
                - 如果还缺工具 → 继续调用，不允许提前生成回复
                - 如果全部工具已执行 → 可以生成最终回复

                示例：
                用户说"明天去爬山"
                  所需工具列表：[retrieve_private_knowledge, query_weather_forecast, create_reminder]
                  如果你只调用了前两个，就生成回复 → 违规
                  你必须再调用 create_reminder，然后才能回复

                用户说"明天3点开会"
                  所需工具列表：[create_reminder]
                  调用 create_reminder 后 → 可以回复

                【冲突处理策略识别 —— 必须遵守】
                创建提醒时，你必须先检查用户是否明确授权了冲突处理方式，然后调用 create_reminder 工具并传对应的 conflictStrategy 参数。

                ⚠️ 铁律：无论用户是否提到冲突处理方式，创建提醒都必须调用 create_reminder 工具。
                禁止用自然语言说"已为你创建提醒"而不调工具（这只是答应，不是执行）。

                判断标准（你自己推理，不要查规则清单）：
                1. 用户明确提到"覆盖""替换""取消旧的"等 → 传 conflictStrategy="OVERWRITE"
                2. 用户明确提到"允许同时""都保留""并存""撞了也没关系"等 → 传 conflictStrategy="ALLOW_COEXIST"
                3. 用户没提冲突相关的话 → 不传 conflictStrategy（默认 ASK，系统会推卡片）

                示例（理解用）：
                - "明天3点开会" → 调用工具，不传 conflictStrategy
                - "明天3点开会，冲突就覆盖" → 调用工具，传 OVERWRITE
                - "明天3点开会，允许同时" → 调用工具，传 ALLOW_COEXIST
                - "明天3点开会，撞了就一起" → 调用工具，传 ALLOW_COEXIST
                - "明天3点开会，如果冲突就算了" → 调用工具，不传（这是取消语义，由卡片处理）

                再次强调：以上所有情况，都必须真正调用 create_reminder 工具，不能只回复文字。

                【周期提醒冲突策略】
                周期提醒（create_recurring_reminder）同样支持冲突处理策略 conflictStrategy：
                - 用户说"每天...冲突就跳过""有冲突的部分不要" → 传 SKIP（跳过冲突日，创建无冲突部分）
                - 用户说"每天...冲突就覆盖""撞了就换掉" → 传 OVERWRITE（覆盖所有冲突日）
                - 用户说"每天...允许同时""撞了也没关系" → 传 ALLOW_COEXIST（全部创建，冲突日并存）
                - 用户没说 → 不传（默认 ASK，系统会推一张汇总卡片列出所有冲突日）

                注意：周期提醒支持 SKIP（跳过冲突日、保留其他天），这与单条提醒不同。
                单条提醒的"跳过"等同于取消，周期提醒的"跳过"只跳过冲突日。

                【周期提醒地点与备注】
                调用 create_recurring_reminder 时，如果用户提供了地点或备注（如"在图书馆"、"记得带笔"），
                请分别填入 location 和 note 参数。

                【多事项识别铁律】
                当用户一句话中包含多个提醒事项时，必须调用 create_multi_reminders，禁止拆成多次 create_reminder。

                判断标准（你自己推理）：
                - 用户输入用"、"、"和"、"然后"、"再"、"还要"、"另外"等连接词分隔多个事项
                - 用户输入包含多个时间点（如"3点...4点...5点..."）
                - 用户一句话描述了多个不同的事项

                示例（理解用）：
                - "明天3点开会、4点拿快递、5点去超市"
                  → 调用 create_multi_reminders(items=[开会, 拿快递, 去超市])
                  → ❌ 禁止：3次 create_reminder

                - "明天3点开会"
                  → 调用 create_reminder（单事项）
                  → ✅ 正确

                - "提醒我还书，还要提醒我带伞"
                  → 调用 create_multi_reminders(items=[还书, 带伞])

                - "5点开会、6点拿快递，冲突就跳过"
                  → 调用 create_multi_reminders(items=[...], conflictStrategy="SKIP")

                再次强调：多个事项必须用 create_multi_reminders，这是铁律。
                """;
    }

    // 将工具参数定义转换为 JSON 字符串，失败时返回空对象兜底
    private String convertParametersToJson(ToolDefinition.Parameters parameters) {
        if (parameters == null) {
            return "{\"type\": \"object\", \"properties\": {}, \"required\": []}";
        }
        
        try {
            Map<String, Object> paramsMap = new HashMap<>();
            paramsMap.put("type", parameters.getType());
            
            Map<String, Object> propertiesMap = new HashMap<>();
            if (parameters.getProperties() != null) {
                parameters.getProperties().forEach((name, property) -> {
                    Map<String, Object> propMap = new HashMap<>();
                    propMap.put("type", property.getType());
                    propMap.put("description", property.getDescription());
                    if (property.getItems() != null) {
                        propMap.put("items", Map.of("type", property.getItems()));
                    }
                    propertiesMap.put(name, propMap);
                });
            }
            paramsMap.put("properties", propertiesMap);
            paramsMap.put("required", parameters.getRequired() != null ? parameters.getRequired() : List.of());
            
            return objectMapper.writeValueAsString(paramsMap);
        } catch (Exception e) {
            log.warn("转换参数定义失败: {}", e.getMessage());
            return "{\"type\": \"object\", \"properties\": {}, \"required\": []}";
        }
    }

    // 构建解析提醒的 Prompt，要求 LLM 返回严格 JSON 并内置时间解析与时间段识别规则
    private String buildPrompt(String userInput) {
        String currentDate = TimeUtils.getCurrentBeijingTime();
        return """
            你是一个智能提醒助手。请解析用户的输入，提取提醒信息。
            
            当前时间：%s
            
            要求：
            1. 返回严格的JSON格式
            2. JSON包含以下字段：
               - title: 提醒事项名称（字符串，必填）
               - remindTime: 提醒时间（格式：yyyy-MM-dd HH:mm:ss，必填）
               - endTime: 结束时间（格式：yyyy-MM-dd HH:mm:ss，可选，用于时间段提醒）
               - intentType: 意图类型（REMINDER/QUERY/DELETE/UPDATE/CONFIRM/CANCEL/WEATHER/UNKNOWN）
               - location: 地点（字符串，可选）
               - priority: 优先级（LOW/NORMAL/HIGH，默认NORMAL）
               - note: 备注（字符串，可选，用户对提醒的补充说明，如"记得带学生证"、"带礼物"等）
               - advanceMinutes: 默认提前量（整数，可选。注意：对于"等会"/"等一下"等模糊时间不要设置此字段，后端会使用用户的默认提前量。相对时间"X分钟后/小时后"统一设为0，准时触发）

            时间解析规则：
            - "明天下午3点" → 当前日期+1天，15:00:00
            - "后天早上9点" → 当前日期+2天，09:00:00
            - "本周六晚上8点" → 本周六，20:00:00
            - "周一上午10点" → 最近的周一，10:00:00
            - "每天早上7点" → 今天，07:00:00（重复提醒）
            - "1分钟后"、"一分钟后"、"1分后" → 当前时间+1分钟，advanceMinutes=0（相对时间准时触发）
            - "5分钟后" → 当前时间+5分钟，advanceMinutes=0
            - "等会"、"待会"、"过一会儿"、"一会儿"、"等一下" → 当前时间，advanceMinutes不设置
            - "30分钟后" → 当前时间+30分钟，advanceMinutes=0
            - "1小时后" → 当前时间+60分钟，advanceMinutes=0
            - "现在"、"马上" → 当前时间，advanceMinutes=0
            - 如果无法解析时间，返回当前时间，advanceMinutes不设置（后端使用用户默认提前量）
            
            时间段识别规则（新增）：
            - 当用户输入包含时间段描述时，需要识别 endTime 字段
            - "明天下午3点到5点开会" → remindTime=明天15:00:00, endTime=明天17:00:00
            - "后天上午9点到11点半上课" → remindTime=后天09:00:00, endTime=后天11:30:00
            - "周五晚上8点到10点聚会" → remindTime=周五20:00:00, endTime=周五22:00:00
            - 关键词识别："到"、"至"、"-"、"~"、"之间"、"时间段" 等表示时间段
            - 如果用户只指定了开始时间，没有明确结束时间，则 endTime 设为 null
            - 如果用户说"X点到Y点"格式，需要同时提取开始和结束时间

            %s
            %s
            示例输入1（单点提醒）："明天下午3点还书"
            示例输出1：{"title":"还书","remindTime":"%s","endTime":null,"intentType":"REMINDER","location":null,"priority":"NORMAL","note":null}

            示例输入2（时间段提醒）："明天下午3点到5点开会"
            示例输出2：{"title":"开会","remindTime":"2026-07-30 15:00:00","endTime":"2026-07-30 17:00:00","intentType":"REMINDER","location":null,"priority":"NORMAL","note":null}

            示例输入3（带地点和备注）："周五上午10点在三教开会，记得带笔记本电脑"
            示例输出3：{"title":"开会","remindTime":"2026-08-01 10:00:00","endTime":null,"intentType":"REMINDER","location":"三教","priority":"NORMAL","note":"记得带笔记本电脑"}

            示例输入4（隐式地点）："明天中午去食堂吃饭"
            示例输出4：{"title":"吃饭","remindTime":"2026-07-31 12:00:00","endTime":null,"intentType":"REMINDER","location":"食堂","priority":"NORMAL","note":null}

            用户输入: %s
            """.formatted(currentDate, buildNoteExtractionRules(), buildLocationExtractionRules(), currentDate, userInput);
    }

    // 解析日期时间字符串，失败返回 null
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, TimeUtils.FORMATTER);
        } catch (Exception e) {
            log.warn("时间解析失败: {}", dateTimeStr);
            return null;
        }
    }

    // 智能解析用户的修改意图：结合当前确认卡片数据，判断用户想改哪个字段（时间/地点/标题/优先级）
    public EditIntent parseEditIntent(String userInput, Map<String, Object> currentData) {
        log.info("开始解析修改意图: userInput={}, currentData={}", userInput, currentData);

        try {
            String prompt = buildEditIntentPrompt(userInput, currentData);

            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText();
            log.info("修改意图解析LLM响应: {}", content);

            JsonNode root = objectMapper.readTree(content);

            String field = root.has("field") ? root.get("field").asText() : "unknown";
            String newValue = root.has("newValue") ? root.get("newValue").asText() : "";
            String reason = root.has("reason") ? root.get("reason").asText() : "";

            // 校验 field 取值范围
            if (!isValidField(field)) {
                log.warn("LLM返回的field无效: {}, 设为unknown", field);
                field = "unknown";
            }

            return EditIntent.builder()
                    .field(field)
                    .newValue(newValue)
                    .reason(reason)
                    .build();

        } catch (Exception e) {
            log.error("解析修改意图失败: userInput={}, error={}", userInput, e.getMessage(), e);
            return EditIntent.builder()
                    .field("unknown")
                    .newValue("")
                    .reason("解析失败")
                    .build();
        }
    }

    // 校验 field 取值是否在合法集合内
    private boolean isValidField(String field) {
        return "time".equals(field) || "location".equals(field)
                || "title".equals(field) || "priority".equals(field)
                || "unknown".equals(field);
    }

    // 构建修改意图解析的 Prompt，注入当前提醒信息和时间解析规则
    private String buildEditIntentPrompt(String userInput, Map<String, Object> currentData) {
        String currentTime = TimeUtils.format(TimeUtils.now());

        String title = currentData.getOrDefault("title", "").toString();
        String remindTime = currentData.getOrDefault("remindTime", "").toString();
        String location = currentData.getOrDefault("location", "").toString();
        String priority = currentData.getOrDefault("priority", Priority.NORMAL.name()).toString();

        return """
                你是一个智能提醒修改助手。用户想要修改当前的提醒信息，请分析用户的修改意图。

                当前时间：%s

                当前提醒信息：
                - 标题：%s
                - 时间：%s
                - 地点：%s
                - 优先级：%s

                用户输入：%s

                请分析用户想修改哪个字段，以及新的值是什么。

                返回严格的JSON格式，不要包含任何其他文本：
                {"field": "字段名", "newValue": "新值", "reason": "简短说明"}

                field 可选值：
                - "time": 修改时间（newValue格式：yyyy-MM-dd HH:mm:ss）
                - "location": 修改地点
                - "title": 修改标题
                - "priority": 修改优先级（newValue：LOW/NORMAL/HIGH）
                - "unknown": 无法识别修改意图

                时间解析规则：
                - "下午三点" → 当天 15:00:00
                - "明天下午3点" → 明天 15:00:00
                - "五点" → 当天 17:00:00
                - "改成明天" → 明天相同时间
                - "后天上午9点" → 后天 09:00:00

                示例：
                当前标题：开会，用户输入"改成下午三点" → {"field": "time", "newValue": "2026-07-26 15:00:00", "reason": "修改时间"}
                当前标题：开会，用户输入"地点改为会议室A" → {"field": "location", "newValue": "会议室A", "reason": "修改地点"}
                当前标题：开会，用户输入"改成讨论会" → {"field": "title", "newValue": "讨论会", "reason": "修改标题"}
                当前标题：开会，用户输入"优先级设为高" → {"field": "priority", "newValue": "HIGH", "reason": "修改优先级"}
                当前标题：开会，用户输入"你好" → {"field": "unknown", "newValue": "", "reason": "未识别到修改意图"}
                """.formatted(currentTime, title, remindTime, location, priority, userInput);
    }

    // 修改意图解析结果
    @Data
    @Builder
    public static class EditIntent {
        // 修改的字段名：time/location/title/priority/unknown
        private String field;
        // 新值
        private String newValue;
        // 简短说明
        private String reason;
    }

    // 解析结果记录类，承载 LLM 解析出的提醒字段
    public record ParsedResult(
            String title,
            LocalDateTime remindTime,
            LocalDateTime endTime,
            String intentType,
            String location,
            String priority,
            String note,
            Integer advanceMinutes
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String title;
            private LocalDateTime remindTime;
            private LocalDateTime endTime;
            private String intentType;
            private String location;
            private String priority;
            private String note;
            private Integer advanceMinutes;

            public Builder title(String title) {
                this.title = title;
                return this;
            }

            public Builder remindTime(LocalDateTime remindTime) {
                this.remindTime = remindTime;
                return this;
            }

            public Builder endTime(LocalDateTime endTime) {
                this.endTime = endTime;
                return this;
            }

            public Builder intentType(String intentType) {
                this.intentType = intentType;
                return this;
            }

            public Builder location(String location) {
                this.location = location;
                return this;
            }

            public Builder priority(String priority) {
                this.priority = priority;
                return this;
            }

            public Builder note(String note) {
                this.note = note;
                return this;
            }

            public Builder advanceMinutes(Integer advanceMinutes) {
                this.advanceMinutes = advanceMinutes;
                return this;
            }

            public ParsedResult build() {
                return new ParsedResult(title, remindTime, endTime, intentType, location, priority, note, advanceMinutes);
            }
        }
    }

    // 生成天气建议：根据天气状况、温度和提醒上下文产出一句贴心提示，天气正常时返回 null
    public String generateWeatherSuggestion(String weatherText, String temperature, String remindTitle, String location) {
        log.info("生成天气建议: weatherText={}, temperature={}, title={}, location={}", 
                weatherText, temperature, remindTitle, location);

        // 正常天气不生成建议
        if (isNormalWeather(weatherText)) {
            log.debug("天气正常，不生成建议");
            return null;
        }

        try {
            String currentTime = TimeUtils.getCurrentBeijingTime();
            String prompt = """
                    你是一个贴心的天气助手。根据以下信息生成一条简短的天气建议（15-20字以内），语气友好自然。

                    当前时间：%s
                    天气状况：%s
                    温度：%s℃
                    提醒事项：%s
                    提醒地点：%s

                    要求：
                    1. 如果天气正常（晴/多云），直接返回null
                    2. 使用适当的emoji：下雨🌧️、高温☀️、低温❄️、雷雨⛈️、雪❄️
                    3. 建议要结合提醒事项和地点，更贴心
                    4. 只返回建议内容，不要返回其他文字

                    示例：
                    天气：小雨，温度：22，提醒：出门，地点：公司 → 🌧️ 外面下雨，记得带伞哦～
                    天气：晴，温度：35，提醒：外出，地点：公园 → ☀️ 天气炎热，注意防晒～
                    天气：多云，温度：25，提醒：开会，地点：会议室 → null
                    """.formatted(currentTime, weatherText, temperature, remindTitle, location);

            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText().trim();

            if ("null".equalsIgnoreCase(content) || content.isEmpty()) {
                return null;
            }

            log.info("天气建议生成成功: {}", content);
            return content;

        } catch (Exception e) {
            log.error("生成天气建议失败: {}", e.getMessage(), e);
            return null;
        }
    }

    // 记忆库 AI 分类：从 MemoryCategory 枚举中为记忆正文选定一个分类，返回枚举名，失败返回 null（由调用方降级 OTHER）
    public String classifyMemoryCategory(String content) {
        log.info("LLM记忆分类: content={}", content);
        if (content == null || content.isBlank()) {
            return null;
        }

        String prompt = """
                给下面这条记忆选择一个分类，用于记忆库的分类筛选。

                记忆内容：%s

                可选分类（只允许从中选择一个，返回括号外的英文编码）：
                - PERSONAL_INFO：用户自身属性与状态（年级、专业、住址、性别、身高等）
                - ACTIVITY：近期或远期的事务（旅游、会议、实习、搬家、面试等）
                - EVENT：重要日期与纪念日（生日、纪念日、考试日等）
                - PLAN：长期目标或计划（考研、减重等需长期跟踪的目标）
                - EXAM_SCHEDULE：考试安排（时间确定、重要程度高的考试）
                - HEALTH_CONDITION：健康状况（过敏、高血压等）
                - DIET_PREFERENCE：饮食偏好（不吃辣、素食等）
                - HABIT：个人习惯，有固定时间或频率规律的行为（作息时间、睡前看书等）
                - INTEREST：兴趣爱好，只是喜欢、无固定时间规律的事（喜欢打篮球、画画等）
                - LANGUAGE_STYLE：语言风格偏好（喜欢简洁回答、语气可爱等）
                - OTHER：以上都不匹配

                要求：只返回一个英文分类编码，不要任何其他文字。
                """.formatted(content);

        try {
            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);
            String raw = chatModel.call(promptObj).getResult().getOutput().getText().trim();

            // 容忍 AI 输出前后缀：提取其中出现的合法枚举名
            for (MemoryCategory category : MemoryCategory.values()) {
                if (raw.contains(category.name())) {
                    return category.name();
                }
            }
            log.warn("记忆AI分类输出不合法: {}", raw);
            return null;
        } catch (Exception e) {
            log.warn("记忆AI分类失败，交由调用方降级: {}", e.getMessage());
            return null;
        }
    }

    // 记忆库 AI 生成标题：用一句话概括记忆正文核心内容，失败返回 null（由调用方降级截断）
    public String generateMemoryTitle(String content) {
        log.info("LLM生成记忆标题: content={}", content);
        if (content == null || content.isBlank()) {
            return null;
        }

        String prompt = """
                给下面这条记忆起一个简短标题，概括它的核心内容。

                记忆内容：%s

                要求：
                1. 不超过15个字
                2. 一般不写人称，直接概括事实；如果必须涉及人称，用"你"称呼用户
                3. 直接返回标题文字，不要任何解释、引号或句末标点
                """.formatted(content);

        try {
            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);
            String title = chatModel.call(promptObj).getResult().getOutput().getText().trim();
            // 去掉可能出现的引号包裹
            title = title.replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
            if (title.isEmpty()) {
                return null;
            }
            return title.length() <= 20 ? title : title.substring(0, 20);
        } catch (Exception e) {
            log.warn("记忆AI标题生成失败，交由调用方降级: {}", e.getMessage());
            return null;
        }
    }

    // 提醒查天气场景：LLM先判断location类型（
    // 返回类型：CITY（城市/区县）/ POI（地名/未填写）/ PROVINCE（省级）/ GARBAGE（乱码/无意义）
    public String judgeLocationType(String location) {
        log.info("LLM判断提醒地点类型: location={}", location);
        try {
            String prompt = """
                    判断这个提醒地点的类型，只返回类型，不选城市。

                    提醒地点：%s

                    判断规则：
                    1. 城市/区县（如"北京朝阳"、"上海"、"上海市-上海"、"朝阳"）→ CITY
                    2. POI/地名（如"图书馆"、"咖啡厅"、"公司"、"学校"、"卧室"、"家"）→ POI
                    3. 省级（如"广东"、"浙江省"、"江苏省"）→ PROVINCE
                    4. 乱码/无意义（如"asdf"、"!!!","12345"、"截止"、"明天"）→ GARBAGE
                    5. 空地点 → POI

                    只返回类型字符串（CITY/POI/PROVINCE/GARBAGE），不要返回其他文字。
                    """.formatted(location == null ? "" : location);

            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);
            String content = chatModel.call(promptObj).getResult().getOutput().getText().trim().toUpperCase();

            // 只取前几个字符，避免LLM返回多余文字
            if (content.contains("CITY")) return "CITY";
            if (content.contains("POI")) return "POI";
            if (content.contains("PROVINCE")) return "PROVINCE";
            return "GARBAGE";
        } catch (Exception e) {
            log.error("LLM判断地点类型失败: location={}, error={}", location, e.getMessage(), e);
            return "GARBAGE";
        }
    }

    // 提醒查天气场景：LLM从候选里选一个城市（不问用户，不判断类型）
    // candidatesWithCoord: [{displayName, latitude, longitude}, ...]
    // 返回 {type: CITY/GARBAGE, resolvedCity: 标准名, coord: "经度,纬度"}
    //   CITY = 选了候选，coord从候选里取
    //   GARBAGE = 选不了，不查
    public Map<String, Object> resolveReminderLocation(
            String location,
            List<Map<String, Object>> candidatesWithCoord) {
        Map<String, Object> result = new HashMap<>();
        log.info("LLM处理提醒地点: location={}, candidates={}", location, candidatesWithCoord);

        try {
            StringBuilder candidateList = new StringBuilder();
            if (candidatesWithCoord != null && !candidatesWithCoord.isEmpty()) {
                for (int i = 0; i < candidatesWithCoord.size(); i++) {
                    candidateList.append(i + 1).append(". ")
                            .append(candidatesWithCoord.get(i).get("displayName"))
                            .append("\n");
                }
            }

            String prompt = """
                    从城市搜索候选里选一个匹配提醒地点的城市。提醒地点已是城市/区县类型（类型判断已由其他步骤完成）。

                    提醒地点：%s
                    城市搜索候选（带编号）：
                    %s

                    选候选规则：
                    a. 用户说城市+区县（如"北京朝阳"）→ 选"省-市-区县"格式的那条（如北京市-北京-朝阳）
                    b. 用户只说城市（如"北京"、"上海"）→ 选"省-市"格式的那条，不选区县（如北京市-北京、上海市-上海）
                    c. 地点已是标准城市名（如"上海市-上海"）→ 选完全匹配的那条
                    d. 疑似错别字（如"上海宝成"→候选有"上海宝山"）→ 可以推测选最接近的
                    e. 同名多义地点（如"朝阳"在辽宁、北京、吉林都有，用户没限定省/市）→ 无法确定，选不了
                    f. 候选为空或都不匹配且无法推测 → 选不了

                    只返回JSON，格式：
                    {"type": "CITY或GARBAGE", "candidateIndex": "选中的候选编号(1-N)或0"}
                    type=CITY表示选了候选，type=GARBAGE表示选不了。
                    不要返回其他文字，不要自己写坐标。
                    """.formatted(location == null ? "" : location, candidateList.toString());

            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText().trim();

            // 解析JSON
            try {
                Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
                String type = (String) parsed.get("type");
                String candidateIndexStr = String.valueOf(parsed.get("candidateIndex"));
                int candidateIndex = 0;
                try {
                    candidateIndex = Integer.parseInt(candidateIndexStr.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                }

                result.put("type", type);

                // CITY类型：从候选列表里取真实displayName+coord
                if ("CITY".equals(type) && candidateIndex >= 1
                        && candidatesWithCoord != null && candidateIndex <= candidatesWithCoord.size()) {
                    Map<String, Object> selected = candidatesWithCoord.get(candidateIndex - 1);
                    String displayName = (String) selected.get("displayName");
                    Object lat = selected.get("latitude");
                    Object lon = selected.get("longitude");
                    String coord = lon + "," + lat;  // 和风API格式：经度,纬度
                    result.put("resolvedCity", displayName);
                    result.put("coord", coord);
                    log.info("LLM处理提醒地点结果: location={}, type=CITY, resolvedCity={}, coord={}",
                            location, displayName, coord);
                } else {
                    log.info("LLM处理提醒地点结果: location={}, type={}", location, type);
                }
                return result;

            } catch (Exception parseEx) {
                log.warn("LLM返回无法解析为JSON，默认不查: location={}, content={}", location, content);
                result.put("type", "GARBAGE");
                return result;
            }

        } catch (Exception e) {
            log.error("LLM处理提醒地点失败: location={}, error={}", location, e.getMessage(), e);
            result.put("type", "GARBAGE");
            return result;
        }
    }

    // 判断是否为正常天气：晴、多云、阴视为正常，其余需生成建议
    private boolean isNormalWeather(String weatherText) {
        if (weatherText == null || weatherText.isBlank()) {
            return true;
        }
        String text = weatherText.toLowerCase();
        // 正常天气：晴、多云、阴
        return text.contains("晴") || text.contains("多云") || text.contains("阴");
    }

    // 生成提醒话术模板：调用 LLM 生成含 {weather}/{title}/{memory} 占位符的一句话模板
    // memories 为空时模板只含 {weather}/{title}；调用失败返回 null，不影响主流程
    public String generateReminderTemplate(String title, List<String> memories) {
        try {
            boolean hasMemory = memories != null && !memories.isEmpty();
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("请为提醒生成一句话话术模板，包含占位符。要求：\n");
            promptBuilder.append("1. 模板必须包含 {weather}、{title} 两个占位符\n");
            if (hasMemory) {
                promptBuilder.append("2. 模板还应包含 {memory} 占位符\n");
            }
            promptBuilder.append("3. 语气自然，像朋友提醒\n");
            promptBuilder.append("4. 只返回模板本身，不要任何解释\n");
            promptBuilder.append("5. 不超过25字\n");
            promptBuilder.append("示例：\n");
            if (hasMemory) {
                promptBuilder.append("- {weather}，记得{title}，{memory}哦\n");
            } else {
                promptBuilder.append("- {weather}，该{title}啦\n");
            }
            promptBuilder.append("\n提醒标题：").append(title);

            String response = chatWithoutTools(promptBuilder.toString(), null);
            if (response == null || response.isBlank()) {
                log.warn("生成提醒模板返回空: title={}", title);
                return null;
            }

            // 清理换行和多余空白
            String template = response.replaceAll("\\s+", "").trim();
            // 校验模板至少包含 {title} 占位符
            if (!template.contains("{title}")) {
                log.warn("生成提醒模板不含 {title} 占位符: title={}, template={}", title, template);
                return null;
            }
            log.info("生成提醒模板成功: title={}, template={}", title, template);
            return template;
        } catch (Exception e) {
            log.warn("生成提醒模板异常，不影响主流程: title={}, error={}", title, e.getMessage());
            return null;
        }
    }
}
