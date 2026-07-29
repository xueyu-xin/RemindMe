package com.dormrepair.remindme.agent;

import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.event.ReminderCancelledEvent;
import com.dormrepair.remindme.agent.event.ReminderConfirmedEvent;
import com.dormrepair.remindme.agent.event.ReminderCreatedEvent;
import com.dormrepair.remindme.service.LLMService;
import com.dormrepair.remindme.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent核心调度器
 * <p>
 * 实现ReAct模式（思考-行动-观察循环）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory conversationMemory;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    @Value("${agent.enabled:false}")
    private boolean agentEnabled;

    /**
     * 最大迭代次数（防止无限循环）
     */
    private static final int MAX_ITERATIONS = 5;

    /**
     * 最大工具调用次数（控制工具链长度）
     */
    private static final int MAX_TOOL_CALLS = 5;

    /**
     * 处理用户请求的主入口
     *
     * @param userId 用户ID
     * @param userInput 用户输入
     * @return Agent响应
     */
    public AgentResponse processRequest(Long userId, String userInput) {
        return processRequest(userId, userInput, null);
    }

    /**
     * 处理用户请求（带上下文）
     *
     * @param userId 用户ID
     * @param userInput 用户输入
     * @param context 额外上下文（如GPS坐标），可为null
     * @return Agent响应
     */
    public AgentResponse processRequest(Long userId, String userInput, Map<String, Object> context) {
        log.info("Agent开始处理请求: userId={}, userInput={}", userId, userInput);

        try {
            // 保存用户输入到对话历史
            conversationMemory.addUserMessage(userId, userInput);

            // 如果有位置上下文，作为系统消息注入对话历史
            if (context != null && context.containsKey("latitude") && context.containsKey("longitude")) {
                Double lat = (Double) context.get("latitude");
                Double lng = (Double) context.get("longitude");
                String locationInfo = String.format(
                        "用户当前GPS坐标：纬度 %s，经度 %s，请调用 get_location 工具将坐标转换为地址",
                        lat, lng
                );
                conversationMemory.addSystemMessage(userId, locationInfo);
                log.info("请求包含GPS坐标: lat={}, lng={}", lat, lng);
            }

            // 记录本次对话的起始位置（用于兜底总结时只取本次对话的消息，避免把历史轮次扯进来）
            int currentTurnStartIndex = conversationMemory.getMessageCount(userId) - 1;

            // 获取对话历史
            List<Message> history = conversationMemory.getHistory(userId);

            // 获取可用工具
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();

            // ReAct循环：思考-行动-观察
            String llmResponse = null;
            Object lastToolResult = null;
            int iteration = 0;
            int toolCallCount = 0;
            int actedFormatBlockCount = 0;   // 连续中文演戏格式拦截次数（最多2次，避免死循环）
            int duplicateCallCount = 0;      // 连续重复调用相同工具+相同参数的次数（达到2次触发兜底总结）

            // 工具调用历史记录
            List<String> toolHistory = new java.util.ArrayList<>();

            // 已执行工具签名集合（toolName + 规范化参数），用于拦截 LLM 重复调用相同工具+相同参数
            java.util.Set<String> executedSignatures = new java.util.HashSet<>();

            while (iteration < MAX_ITERATIONS) {
                iteration++;
                log.info("Agent执行进度: 第 {}/{} 步, 已调用工具: {}", iteration, MAX_ITERATIONS, toolHistory);

                // 思考：调用LLM
                llmResponse = llmService.chatWithTools(userInput, tools, history);

                // 空响应重试：LLM 可能因提示词过长或超时返回空
                if (llmResponse == null || llmResponse.isBlank()) {
                    log.warn("LLM返回空响应，尝试简化重试 (iteration={})", iteration);
                    // 简化重试：用精简提示词直接问 LLM
                    String retryInput = "用户请求：" + userInput + "\n请调用合适的工具来处理。如果是创建提醒，调用 create_reminder 或 create_recurring_reminder。";
                    llmResponse = llmService.chatWithTools(retryInput, tools, history);
                    if (llmResponse == null || llmResponse.isBlank()) {
                        log.error("LLM两次返回空响应，返回降级错误");
                        String fallbackMsg = "抱歉，处理请求时出现问题，请稍后重试或换一种方式描述。";
                        conversationMemory.addAssistantMessage(userId, fallbackMsg);
                        return AgentResponse.text(fallbackMsg);
                    }
                    log.info("简化重试成功，LLM返回响应");
                }

                // 解析LLM响应（支持一次返回多个工具调用）
                List<ToolCall> llmToolCalls = llmService.parseToolCalls(llmResponse);

                if (llmToolCalls.isEmpty()) {
                    // ============================================================
                    // 反演戏格式拦截：检测到「工具调用:」/「工具执行结果:」
                    // 但 parseToolCalls 没解析成功 → 注入严格指令重新生成
                    // ============================================================
                    if (isActedFormat(llmResponse) && actedFormatBlockCount < 2) {
                        actedFormatBlockCount++;
                        log.warn("检测到LLM输出中文演戏格式（第{}次拦截），强制重跑。原始响应前200字：{}",
                                actedFormatBlockCount,
                                llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);
                        String strictMsg = "【严重警告】你刚才用的是内部演戏格式！错误内容包含「工具调用:」「工具执行结果:」，" +
                                "但这些不是真正的工具调用，用户一个操作也没有被执行！\n" +
                                "正确做法：需要调用工具时只能输出 纯JSON，严格按：\n" +
                                "  {\"tool_call\":{\"name\":\"工具名\",\"arguments\":{...}}}\n" +
                                "  JSON前后 严禁任何自然语言、说明、换行、示例。\n" +
                                "重新处理用户的原始请求：「" + userInput + "」";
                        history.add(ConversationMemory.Message.builder()
                                .role("system")
                                .content(strictMsg)
                                .build());
                        continue;
                    }

                    // 直接回复用户，循环结束
                    log.info("LLM直接回复，结束循环（迭代次数: {}, 工具调用次数: {}）", iteration, toolCallCount);

                    // 检查 LLM 输出是否包含未解析的 tool_call JSON 片段
                    if (containsToolCallFragment(llmResponse)) {
                        log.warn("LLM输出包含未解析的tool_call JSON片段，过滤后返回友好提示");
                        String friendlyMsg = "操作失败，请重试或换一种方式描述。";
                        conversationMemory.addAssistantMessage(userId, friendlyMsg);
                        return AgentResponse.text(friendlyMsg);
                    }

                    // 最终给用户的回复，先清理过程语前缀和演戏段
                    String finalReply = cleanFinalResponse(llmResponse);
                    log.info("Agent执行完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
                    conversationMemory.addAssistantMessage(userId, finalReply);
                    return buildAgentResponse(finalReply, lastToolResult, userId);
                }

                // 只要真正解析到 ToolCall 就重置演戏拦截计数
                actedFormatBlockCount = 0;

                // ============================================================
                // 行动：依次执行所有工具调用（支持 LLM 一次返回多个工具）
                // 每个工具单独执行、单独记录结果，受 MAX_TOOL_CALLS 限制
                // ============================================================
                for (ToolCall llmToolCall : llmToolCalls) {
                    // 检查是否达到最大工具调用次数
                    if (toolCallCount >= MAX_TOOL_CALLS) {
                        log.warn("达到最大工具调用次数 {}，跳过剩余工具调用", MAX_TOOL_CALLS);
                        break;
                    }

                    // ====================================================
                    // 重复调用检测：相同工具 + 相同参数（排除系统注入的 userId）
                    // 已成功执行过的，跳过执行，避免 LLM 陷入"反复存同一条记忆"死循环
                    // 连续重复 2 次即视为 LLM 卡死，强制走兜底总结退出循环
                    // ====================================================
                    String signature = toolCallSignature(llmToolCall);
                    if (executedSignatures.contains(signature)) {
                        duplicateCallCount++;
                        log.warn("检测到LLM重复调用相同工具+相同参数（第{}次），跳过执行: toolName={}, args={}",
                                duplicateCallCount, llmToolCall.getToolName(), llmToolCall.getArguments());

                        if (duplicateCallCount >= 2) {
                            // 连续 2 次重复 → LLM 卡死，强制走兜底总结
                            log.warn("连续{}次重复调用，强制触发兜底总结退出循环", duplicateCallCount);
                            // 只取本次对话的消息（从用户当前轮请求开始），不把历史轮次扯进来
                            List<Message> currentTurnHistory = extractCurrentTurnHistory(history, currentTurnStartIndex);
                            String summary = llmService.generateSummaryFromHistory(currentTurnHistory);
                            if (summary == null || summary.isBlank()) {
                                summary = "已为您处理完成，如需进一步帮助请告诉我～";
                            }
                            summary = cleanFinalResponse(summary);
                            log.info("重复调用兜底总结完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
                            conversationMemory.addAssistantMessage(userId, summary);
                            return buildAgentResponse(summary, lastToolResult, userId);
                        }

                        String dupMsg = "【系统提示】工具 " + llmToolCall.getToolName() +
                                " 已用相同参数执行过，不要重复调用。\n" +
                                "请直接生成最终回复给用户（说明已完成的内容），或处理其他尚未完成的任务。";
                        history.add(ConversationMemory.Message.builder()
                                .role("system")
                                .content(dupMsg)
                                .build());
                        continue;  // 跳过这个重复工具，继续下一个
                    } else {
                        // 遇到新的工具调用，重置重复计数
                        duplicateCallCount = 0;
                    }

                    // 执行工具调用
                    log.info("LLM决定调用工具({}/{}) : {}", toolCallCount + 1, MAX_TOOL_CALLS, llmToolCall.getToolName());
                    ToolCall toolCall = ToolCall.of(llmToolCall.getToolName(), llmToolCall.getArguments());
                    String toolResult = executeToolCall(userId, toolCall);
                    toolCallCount++;
                    executedSignatures.add(signature);

                    // 记录工具调用历史
                    toolHistory.add(llmToolCall.getToolName());
                    log.info("工具调用记录: {} → {}", toolCallCount, llmToolCall.getToolName());

                    // 保存工具调用和结果到对话历史
                    conversationMemory.addToolCall(userId, llmToolCall.getToolName(), llmToolCall.getArguments());

                    // 检查工具执行是否成功
                    boolean toolSuccess = true;
                    try {
                        Map<String, Object> resultMap = objectMapper.readValue(toolResult, Map.class);
                        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
                            toolSuccess = false;
                            log.warn("工具执行失败: {}", resultMap.get("error"));
                        }
                    } catch (Exception e) {
                        // JSON解析失败，视为成功（可能是其他格式）
                    }
                    conversationMemory.addToolResult(userId, llmToolCall.getToolName(), toolResult, toolSuccess);

                    // 检查工具执行结果是否包含sessionId（create_reminder工具触发确认流程）
                    AgentResponse confirmationResponse = buildConfirmationResponse(toolResult, userId);
                    if (confirmationResponse != null) {
                        log.info("工具结果包含sessionId，构建CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                        // 把 LLM 的引导语也落一条 assistant 消息到对话历史，避免"卡片下了但没文字"的打断感
                        if (confirmationResponse.getContent() != null && !confirmationResponse.getContent().isBlank()) {
                            conversationMemory.addAssistantMessage(userId, confirmationResponse.getContent());
                        }
                        return confirmationResponse;
                    }

                    // 检查工具结果是否标记任务完成（如 create_recurring_reminder 返回 shouldStop=true）
                    if (isTaskCompleted(toolResult)) {
                        log.info("工具结果标记任务完成（shouldStop=true），结束循环: toolName={}", llmToolCall.getToolName());
                        // 双重保障：即使 buildConfirmationResponse 首次没检测到，再次尝试
                        AgentResponse confirmResp = buildConfirmationResponse(toolResult, userId);
                        if (confirmResp != null) {
                            log.info("shouldStop分支二次检测到sessionId，返回CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                            if (confirmResp.getContent() != null && !confirmResp.getContent().isBlank()) {
                                conversationMemory.addAssistantMessage(userId, confirmResp.getContent());
                            }
                            return confirmResp;
                        }
                        conversationMemory.addAssistantMessage(userId, "周期提醒确认卡片已生成，请确认。");
                        // 返回自定义 CONFIRMATION，避免直接返回 TEXT 导致前端清空卡片
                        try {
                            Map<String, Object> resultMap = objectMapper.readValue(
                                    toolResult instanceof String ? (String) toolResult : objectMapper.writeValueAsString(toolResult),
                                    Map.class);
                            String sessionId = (String) resultMap.get("sessionId");
                            if (sessionId != null) {
                                log.warn("shouldStop分支手工构建CONFIRMATION响应: sessionId={}", sessionId);
                                String title = resultMap.get("title") != null ? String.valueOf(resultMap.get("title")) : "提醒";
                                String remindTime = resultMap.get("remindTime") != null ? String.valueOf(resultMap.get("remindTime")) : null;
                                String location = resultMap.get("location") != null ? String.valueOf(resultMap.get("location")) : null;
                                Map<String, Object> confirmData = new HashMap<>();
                                confirmData.put("title", title);
                                confirmData.put("remindTime", remindTime);
                                confirmData.put("location", location);
                                return AgentResponse.confirmation(sessionId, "📅 周期提醒请确认：", confirmData);
                            }
                        } catch (Exception e) {
                            log.warn("shouldStop分支解析失败: {}", e.getMessage());
                        }
                        return buildAgentResponse("周期提醒确认卡片已生成，请确认。", toolResult, userId);
                    }

                    // 如果工具执行失败，继续循环让LLM处理降级结果
                    if (!toolSuccess) {
                        log.info("工具执行失败，继续循环让LLM处理降级结果");
                    }

                    lastToolResult = toolResult;
                }  // end for (llmToolCalls)

                // 更新历史（工具结果已添加到对话历史中，不再修改userInput）
                // LLM下次迭代时会通过对话历史看到工具执行结果（包括失败结果）
                history = conversationMemory.getHistory(userId);
            }

            // 达到最大迭代次数：调用兜底总结模块，让 LLM 基于"本次对话"的消息生成
            // "已完成/未完成/需询问"三类总结，避免把最后一行 JSON 直接返回给用户
            // 注意：只取本次对话的消息，不把历史轮次扯进来，防止 LLM 把之前的任务也总结进去
            log.warn("达到最大迭代次数 {}，触发兜底总结（工具调用次数: {}）", MAX_ITERATIONS, toolCallCount);
            log.info("Agent执行完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
            List<Message> currentTurnHistory = extractCurrentTurnHistory(history, currentTurnStartIndex);
            String finalReply = llmService.generateSummaryFromHistory(currentTurnHistory);
            if (finalReply == null || finalReply.isBlank()) {
                // 兜底总结也失败 → 退回到清理最后一行响应
                finalReply = cleanFinalResponse(llmResponse);
                if (finalReply == null || finalReply.isBlank()
                        || finalReply.contains("{\"tool_call\"")) {
                    finalReply = "当前请求处理中步骤过多，请稍后重试或换一种方式描述。";
                }
            } else {
                finalReply = cleanFinalResponse(finalReply);
            }
            conversationMemory.addAssistantMessage(userId, finalReply);
            return buildAgentResponse(finalReply, lastToolResult, userId);

        } catch (Exception e) {
            log.error("Agent处理请求失败: userId={}, error={}", userId, e.getMessage(), e);
            return AgentResponse.error("处理请求时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从完整对话历史中切出"本次对话"的消息切片。
     * <p>
     * 用于兜底总结：只让 LLM 看到当前这一轮用户请求及其触发的工具调用/结果，
     * 不把历史轮次的对话扯进来，避免 LLM 把之前的任务也总结进去或产生幻觉。
     * </p>
     *
     * @param fullHistory 完整对话历史
     * @param startIndex 本次对话在完整历史中的起始下标
     * @return 本次对话的消息切片（起始下标越界时返回空列表）
     */
    private List<Message> extractCurrentTurnHistory(List<Message> fullHistory, int startIndex) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        if (startIndex < 0 || startIndex >= fullHistory.size()) {
            return new java.util.ArrayList<>(fullHistory);
        }
        return new java.util.ArrayList<>(fullHistory.subList(startIndex, fullHistory.size()));
    }

    /**
     * 执行具体的工具调用（带userId，含重试机制）
     *
     * @param userId 用户ID
     * @param toolCall 工具调用信息
     * @return 执行结果（JSON格式）
     */
    public String executeToolCall(Long userId, ToolCall toolCall) {
        String toolName = toolCall.getToolName();
        Map<String, Object> arguments = new HashMap<>(toolCall.getArguments());
        arguments.put("userId", userId);

        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                // 先规范化工具名：去空白、强制小写，避免尾部空格/大小写导致的"工具不存在"
                String normToolName = toolName == null ? "" : toolName.trim().toLowerCase();
                // 再做一次大小写不敏感的兜底查找（比如 LLM 写 Create_Reminder 也能对上）
                String actualToolName = toolRegistry.resolveToolName(normToolName);
                if (actualToolName == null) {
                    // 最后兜底：把下划线和横杠互相替换后再找一次
                    String swapped = normToolName.replace('_', '-');
                    if (!swapped.equals(normToolName)) {
                        actualToolName = toolRegistry.resolveToolName(swapped);
                    }
                    if (actualToolName == null) {
                        String errorMsg = "工具不存在: " + toolName + " (可用工具: " + toolRegistry.listToolNames() + ")";
                        log.warn(errorMsg);
                        return "{\"success\": false, \"error\": \"" + errorMsg.replace("\"", "'") + "\"}";
                    }
                }
                if (!actualToolName.equals(normToolName)) {
                    log.debug("工具名规范化匹配: 请求={} -> 实际注册名={}", toolName, actualToolName);
                }

                log.info("执行工具调用: userId={}, toolName={}, 尝试 {}/{}", userId, actualToolName, attempt, maxRetries);

                // 根据重试次数调整参数
                Map<String, Object> adjustedArgs = prepareArgumentsForRetry(arguments, attempt);

                // 执行工具
                Object result = toolRegistry.executeTool(actualToolName, adjustedArgs);

                // 构建成功结果
                String resultJson;
                if (result instanceof Map) {
                    // 如果返回值已经是 Map，直接序列化（如 ReminderService）
                    resultJson = serializeResult(result);
                    // 解析结果中的sessionId（create_reminder工具触发确认流程）
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        if (Boolean.TRUE.equals(resultMap.get("success")) && resultMap.containsKey("sessionId")) {
                            log.info("工具执行结果包含sessionId，标记为需要确认: toolName={}, sessionId={}",
                                    toolName, resultMap.get("sessionId"));
                        }
                    } catch (Exception e) {
                        log.warn("解析工具结果sessionId失败: {}", e.getMessage());
                    }
                } else {
                    // 如果返回值是 DTO 对象，包装一层（如 WeatherService）
                    resultJson = "{\"success\": true, \"data\": " + serializeResult(result) + "}";
                }

                log.info("工具执行成功: userId={}, toolName={}, 尝试次数={}", userId, toolName, attempt);
                return resultJson;

            } catch (Exception e) {
                lastException = e;
                log.warn("工具执行失败: userId={}, toolName={}, 尝试 {}/{}, error={}",
                        userId, toolName, attempt, maxRetries, e.getMessage());

                if (attempt >= maxRetries) {
                    break;
                }

                // 重试前等待（指数退避）
                try {
                    long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                    log.debug("工具调用失败，等待 {}ms 后重试", waitMs);
                    Thread.sleep(waitMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 所有重试失败，返回错误信息
        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        log.error("工具执行全部失败: userId={}, toolName={}, error={}", userId, toolName, errorMsg);
        return buildFallbackResult(toolName, errorMsg);
    }

    /**
     * 根据重试次数准备降级参数
     *
     * @param originalArgs 原始参数
     * @param attempt 当前重试次数（从1开始）
     * @return 调整后的参数
     */
    private Map<String, Object> prepareArgumentsForRetry(Map<String, Object> originalArgs, int attempt) {
        Map<String, Object> args = new HashMap<>(originalArgs);

        if (attempt == 1) {
            // 第1次：使用原参数
            return args;
        }

        if (attempt >= 2) {
            // 第2次：尝试去除"市"后缀
            if (args.containsKey("location")) {
                String location = (String) args.get("location");
                if (location != null && location.endsWith("市")) {
                    String newLocation = location.substring(0, location.length() - 1);
                    args.put("location", newLocation);
                    log.debug("参数降级(第{}次): 去除'市'后缀: {} → {}", attempt, location, newLocation);
                }
            }
        }

        if (attempt >= 3) {
            // 第3次：使用用户默认城市
            if (args.containsKey("userId")) {
                Long userId = (Long) args.get("userId");
                try {
                    Map<String, Object> preferences = userService.getUserPreferences(userId);
                    String defaultCity = (String) preferences.get("city");
                    if (defaultCity != null && !defaultCity.isBlank()) {
                        args.put("location", defaultCity);
                        log.debug("参数降级(第{}次): 使用用户默认城市: {}", attempt, defaultCity);
                    }
                } catch (Exception e) {
                    log.warn("获取用户默认城市失败: userId={}, error={}", userId, e.getMessage());
                }
            }
        }

        return args;
    }

    /**
     * 构建降级结果，供LLM生成友好回复
     *
     * @param toolName 工具名称
     * @param errorMessage 原始错误信息
     * @return 降级结果JSON
     */
    private String buildFallbackResult(String toolName, String errorMessage) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("fallback", true);

        // 根据工具类型生成友好的错误描述
        String friendlyMessage = switch (toolName) {
            case "query_weather_now", "query_weather_forecast" ->
                "天气查询失败，可能因为城市名不正确或网络问题。建议您检查城市名，或稍后再试。";
            case "create_reminder" ->
                "创建提醒失败，请确认提醒信息是否正确，稍后再试。";
            case "create_recurring_reminder" ->
                "创建周期提醒失败，请确认日期范围和时间是否正确，稍后再试。";
            case "update_reminder" ->
                "修改提醒失败，请确认提醒是否存在，稍后再试。";
            case "cancel_reminder" ->
                "取消提醒失败，请确认提醒是否存在，稍后再试。";
            case "cancel_reminders_by_date" ->
                "取消提醒失败，请确认日期和关键词是否正确，稍后再试。";
            case "get_reminder_detail" ->
                "查询提醒详情失败，请确认提醒ID是否正确，稍后再试。";
            case "get_user_preferences" ->
                "获取用户设置失败，请稍后再试。";
            case "set_user_preferences" ->
                "保存用户设置失败，请稍后再试。";
            case "get_current_time" ->
                "获取当前时间失败，请稍后再试。";
            case "calculate_date" ->
                "日期计算失败，请检查输入格式，稍后再试。";
            case "get_location" ->
                "获取位置信息失败，请稍后再试。";
            default ->
                "操作失败，请稍后再试。";
        };

        fallback.put("message", friendlyMessage);
        fallback.put("originalError", errorMessage);

        try {
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception e) {
            log.warn("序列化降级结果失败: {}", e.getMessage());
            return "{\"success\":false,\"fallback\":true,\"message\":\"操作失败，请稍后再试。\"}";
        }
    }

    /**
     * 序列化执行结果
     */
    private String serializeResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof String) {
            return "\"" + result.toString().replace("\"", "\\\"") + "\"";
        }
        if (result instanceof Number || result instanceof Boolean) {
            return result.toString();
        }
        // 支持 Map 和复杂对象的 JSON 序列化
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("序列化结果失败: {}", e.getMessage());
            return "\"" + result.getClass().getName() + "\"";
        }
    }

    /**
     * 构建统一的响应对象（带 userId，用于需要 LLM 生成确认卡片引导语时拿到对话历史）
     *
     * @param llmOutput  LLM输出内容
     * @param toolResult 工具执行结果（可能为null）
     * @param userId     当前用户ID
     * @return Agent响应
     */
    public AgentResponse buildAgentResponse(String llmOutput, Object toolResult, Long userId) {
        log.debug("构建Agent响应: llmOutput={}, toolResult={}", llmOutput, toolResult);

        // 检查是否是工具调用响应
        ToolCall toolCall = llmService.parseToolCall(llmOutput);
        if (toolCall != null) {
            return AgentResponse.toolCall(toolCall.getToolName(), toolCall.getArguments());
        }

        // 检查工具执行结果是否包含sessionId（create_reminder工具触发确认流程）
        if (toolResult != null && userId != null) {
            AgentResponse confirmationResponse = buildConfirmationResponse(toolResult, userId);
            if (confirmationResponse != null) {
                log.info("工具结果包含sessionId，返回CONFIRMATION类型响应");
                // 把 LLM 引导语也落 assistant 消息，防止"卡片打断回复"
                if (confirmationResponse.getContent() != null && !confirmationResponse.getContent().isBlank()) {
                    conversationMemory.addAssistantMessage(userId, confirmationResponse.getContent());
                }
                return confirmationResponse;
            }
        }

        // 检查是否包含错误信息
        if (llmOutput != null && llmOutput.contains("error")) {
            return AgentResponse.error(llmOutput);
        }

        // 过滤 LLM 输出中的 JSON 片段
        String cleanOutput = sanitizeLlmOutput(llmOutput);

        // 默认返回文本响应
        AgentResponse response = AgentResponse.text(cleanOutput);
        if (toolResult != null) {
            response.setToolResult(toolResult);
        }
        return response;
    }

    /**
     * 检查 LLM 输出是否包含未解析的 tool_call JSON 片段
     */
    private boolean containsToolCallFragment(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        return output.contains("\"tool_call\"") || output.contains("'tool_call'")
                || output.contains("\"tool_name\"") || output.contains("\"arguments\"");
    }

    /**
     * 生成工具调用签名（toolName + 规范化参数），用于重复调用检测。
     * <p>
     * 排除系统注入的 userId（每次都一样，不影响"是否重复"判断）。
     * 参数按 key 排序后拼接 value，保证 (A=1,B=2) 与 (B=2,A=1) 签名一致。
     * </p>
     */
    private String toolCallSignature(ToolCall toolCall) {
        if (toolCall == null) {
            return "";
        }
        java.util.TreeMap<String, String> sortedArgs = new java.util.TreeMap<>();
        if (toolCall.getArguments() != null) {
            for (Map.Entry<String, Object> e : toolCall.getArguments().entrySet()) {
                if (!"userId".equalsIgnoreCase(e.getKey())) {
                    sortedArgs.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        }
        return toolCall.getToolName() + "|" + sortedArgs;
    }

    /**
     * 检查是否是LLM的"中文演戏"格式（含「工具调用:」「工具执行结果:」字样）
     */
    private boolean isActedFormat(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return output.contains("工具调用:") || output.contains("工具调用：")
                || output.contains("工具执行结果:") || output.contains("工具执行结果：");
    }

    /**
     * 最终给用户的回复出口清理：
     * 1) 剥掉过程语前缀（好的我帮你/好的这就帮你/我先帮你查一下…）
     * 2) 整行删除「工具调用:」「工具执行结果:」演戏行 + 内联演戏段
     * 3) 清理留下的多余空行/空白
     * 4) 若整段被清空返回降级兜底句，避免空回复
     */
    private String cleanFinalResponse(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        String text = output;

        // ---- 2) 清理演戏行（整行匹配 / 行首匹配）----
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { sb.append('\n'); continue; }
            if (trimmed.startsWith("工具调用:") || trimmed.startsWith("工具调用：")
                    || trimmed.startsWith("工具执行结果:") || trimmed.startsWith("工具执行结果：")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        text = sb.toString();

        // 内联：删除所有「工具调用:...」「工具执行结果:...」到行末或下一段前
        text = text.replaceAll("(?m)(工具调用|工具执行结果)[:：][^\n]*", "");
        text = text.replaceAll("(?m)参数[:：][ \t]*\\{.*\\}[ \t]*", "");

        // ---- 1) 剥掉过程语前缀（整段开头，最多连续剥3层）----
        java.util.regex.Pattern[] processPrefixes = new java.util.regex.Pattern[] {
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*好[的啦的嘛]?[，,\\s\\.。～~\\-!！?？:：/]*我(先)?(这就|现在|马上|立刻)?(帮你|来帮你|给你|为你)(查一下|查|看看|看一下|处理|修改|调整|设置|记录|记|存|创建|建|添加|加|取消|删除|删|更新|改|做|安排|弄|完成)[^，,。.！!~\n～]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*让我(先)?(帮你|来帮你|给你|为你)(查一下|查|看看|看一下|处理|修改|调整|设置|记录|记|存)[^，,。.！!\n～]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*我先(来)?(帮你|处理|确认|看看|查一下)[^，,。.！!～\n]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*(这就|马上|立刻|立即)(就)?(帮你|来|开始|处理)[^，,。.！!～\n]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE)
        };
        int stripRound = 0;
        while (stripRound < 3) {
            boolean changed = false;
            for (java.util.regex.Pattern p : processPrefixes) {
                java.util.regex.Matcher m = p.matcher(text);
                if (m.find() && m.start() == 0) {
                    String before = text;
                    text = m.replaceFirst("");
                    if (!text.equals(before)) changed = true;
                }
            }
            if (!changed) break;
            stripRound++;
        }

        // 如果整段只剩"好的"、"好的～"之类的空话，直接清空
        if (text.trim().matches("^(好[的啦嘛]?[~～\\s。,.，!！?？:：]*){1,3}$")) {
            text = "";
        }

        // ---- 3) 清理多余空行/首尾空白 ----
        text = text.replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        // ---- 4) 兜底：如果整个回复被清干净了，给个降级回复 ----
        if (text.isEmpty()) {
            return "已处理完成，如需进一步帮助请随时告诉我。";
        }

        return text;
    }

    /**
     * 过滤 LLM 输出中的 JSON 片段，返回干净的自然语言文本
     */
    private String sanitizeLlmOutput(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        // 如果输出包含 tool_call JSON 片段，返回友好提示
        if (containsToolCallFragment(output)) {
            log.warn("过滤LLM输出中的JSON片段: {}", output.substring(0, Math.min(output.length(), 200)));
            return "操作失败，请重试或换一种方式描述。";
        }
        // 最终再走过程语/演戏段清理
        return cleanFinalResponse(output);
    }

    /**
     * 从工具执行结果中构建CONFIRMATION响应
     * <p>
     * 检查工具结果是否包含sessionId（create_reminder工具返回），
     * 如果包含则构建CONFIRMATION类型的响应。
     * <p>
     * 关键点：先让 LLM 基于提醒摘要生成一句自然语言引导（"我帮你创建了 X 提醒，请在下方卡片点确认/取消"），
     * 再把 CONFIRMATION 卡片 + 这句文字 一起下给前端，做到"提醒卡片不打断 LLM 的回复"。
     * </p>
     *
     * @param toolResult 工具执行结果（JSON字符串或Map）
     * @param userId     当前用户ID（用于获取对话历史并调用LLM生成引导语）
     * @return CONFIRMATION响应，如果不包含sessionId则返回null
     */
    private AgentResponse buildConfirmationResponse(Object toolResult, Long userId) {
        if (toolResult == null) {
            return null;
        }

        try {
            Map<String, Object> resultMap;
            if (toolResult instanceof String) {
                resultMap = objectMapper.readValue((String) toolResult, Map.class);
            } else if (toolResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) toolResult;
                resultMap = castedMap;
            } else {
                log.debug("buildConfirmationResponse: toolResult 类型 {} 不是 String/Map，跳过",
                        toolResult != null ? toolResult.getClass().getSimpleName() : "null");
                return null;
            }

            boolean success = Boolean.TRUE.equals(resultMap.get("success"));
            boolean hasSessionId = resultMap.containsKey("sessionId") && resultMap.get("sessionId") != null;
            log.debug("buildConfirmationResponse: success={}, hasSessionId={}, keys={}",
                    success, hasSessionId, resultMap.keySet());

            if (!success || !hasSessionId) {
                log.debug("buildConfirmationResponse: 条件不满足（success={}，hasSessionId={}），返回null", success, hasSessionId);
                return null;
            }

            String sessionId = (String) resultMap.get("sessionId");
            String title = (String) resultMap.get("title");
            String remindTime = (String) resultMap.get("remindTime");
            String location = (String) resultMap.get("location");
            boolean isRecurring = Boolean.TRUE.equals(resultMap.get("isRecurring"));
            log.info("buildConfirmationResponse: 检测到确认会话 sessionId={}, title={}, isRecurring={}", sessionId, title, isRecurring);

            Map<String, Object> confirmationData = new HashMap<>();
            confirmationData.put("title", title);
            confirmationData.put("remindTime", remindTime);
            confirmationData.put("location", location);
            if (resultMap.containsKey("endTime")) {
                confirmationData.put("endTime", resultMap.get("endTime"));
            }
            if (resultMap.containsKey("priority")) {
                confirmationData.put("priority", resultMap.get("priority"));
            }
            if (isRecurring) {
                confirmationData.put("isRecurring", true);
            }

            // 调用 LLM 生成一句自然语言引导（提醒卡片和文字一起返回，避免"卡片打断回复"）
            String llmLead = buildConfirmationLead(userId, title, remindTime, location, isRecurring);

            return AgentResponse.confirmation(sessionId, llmLead, confirmationData);
        } catch (Exception e) {
            log.warn("解析工具结果构建CONFIRMATION响应失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 调用 LLM 生成确认卡片前的引导文字（纯文本一句话，带标题/时间/地点即可，严禁再输出工具调用）。
     * LLM 不可用则用兜底默认文案。
     */
    private String buildConfirmationLead(Long userId, String title, String remindTime,
                                         String location, boolean isRecurring) {
        String defaultText = isRecurring
                ? "📅 我帮你解析了周期提醒，请在下方卡片确认："
                : "📋 我帮你解析了提醒信息，请在下方卡片确认：";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("以下是刚解析到的待确认提醒信息，请生成一句对用户的引导语，要求：\n");
            sb.append("- 直接告诉用户你创建了什么提醒（包含标题和时间），让用户确认或取消；\n");
            sb.append("- 不要输出任何JSON、工具调用、演戏格式；\n");
            sb.append("- 不要过程语前缀，不要表情堆砌，50字以内。\n\n");
            sb.append("提醒标题：").append(title == null ? "(未命名)" : title).append("\n");
            sb.append("提醒时间：").append(remindTime == null ? "(未设置)" : remindTime).append("\n");
            if (location != null && !location.isBlank()) {
                sb.append("提醒地点：").append(location).append("\n");
            }
            if (isRecurring) {
                sb.append("类型：周期提醒（按周期重复）\n");
            }

            String hint = sb.toString();
            List<ToolDefinition> noTools = List.of();  // 禁止再调用任何工具
            List<Message> history = conversationMemory.getHistory(userId);
            String reply = llmService.chatWithTools(hint, noTools, history);
            reply = sanitizeLlmOutput(reply);
            if (reply == null || reply.isBlank()) {
                return defaultText;
            }
            // 避免 LLM 又把引导语输出成"请确认：请确认：..."
            if (reply.contains("请确认") || reply.contains("确认") || reply.contains("取消")) {
                return reply;
            }
            return reply + "\n" + defaultText;
        } catch (Exception e) {
            log.warn("生成确认卡片引导语失败，使用兜底文案: {}", e.getMessage());
            return defaultText;
        }
    }

    /**
     * 检查工具结果是否标记任务完成（shouldStop=true 或 taskCompleted=true）
     *
     * @param toolResult 工具执行结果（JSON字符串或Map）
     * @return 如果标记任务完成则返回true
     */
    private boolean isTaskCompleted(Object toolResult) {
        if (toolResult == null) {
            return false;
        }
        try {
            Map<String, Object> resultMap;
            if (toolResult instanceof String) {
                resultMap = objectMapper.readValue((String) toolResult, Map.class);
            } else if (toolResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) toolResult;
                resultMap = castedMap;
            } else {
                return false;
            }
            return Boolean.TRUE.equals(resultMap.get("shouldStop"))
                    || Boolean.TRUE.equals(resultMap.get("taskCompleted"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取对话历史
     */
    public List<Message> getHistory(Long userId) {
        return conversationMemory.getHistory(userId);
    }

    /**
     * 清空对话历史
     */
    public void clearHistory(Long userId) {
        conversationMemory.clear(userId);
    }

    /**
     * 获取可用工具列表
     */
    public List<ToolDefinition> getAvailableTools() {
        return toolRegistry.getToolDefinitions();
    }

    /**
     * 获取工具数量
     */
    public int getToolCount() {
        return toolRegistry.getToolCount();
    }

    /**
     * 监听提醒创建事件
     */
    @EventListener
    public void onReminderCreated(ReminderCreatedEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒创建事件: userId={}, title={}", event.getUserId(), event.getTitle());
        try {
            String resultText = "提醒创建请求已发送: " + event.getTitle() + "，时间: " + event.getRemindTime();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, true);
        } catch (Exception e) {
            log.error("处理提醒创建事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * 监听提醒确认事件
     */
    @EventListener
    public void onReminderConfirmed(ReminderConfirmedEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒确认事件: userId={}, reminderId={}", event.getUserId(), event.getReminderId());
        try {
            String resultText = "用户已确认提醒创建: " + event.getTitle();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, true);

            // 调用LLM生成回复
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
            List<Message> history = conversationMemory.getHistory(event.getUserId());
            String llmResponse = llmService.chatWithTools(resultText, tools, history);
            conversationMemory.addAssistantMessage(event.getUserId(), llmResponse);
        } catch (Exception e) {
            log.error("处理提醒确认事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * 监听提醒取消事件
     */
    @EventListener
    public void onReminderCancelled(ReminderCancelledEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒取消事件: userId={}, reminderId={}", event.getUserId(), event.getReminderId());
        try {
            String resultText = "用户已取消提醒: " + event.getTitle();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, false);

            // 调用LLM生成回复
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
            List<Message> history = conversationMemory.getHistory(event.getUserId());
            String llmResponse = llmService.chatWithTools(resultText, tools, history);
            conversationMemory.addAssistantMessage(event.getUserId(), llmResponse);
        } catch (Exception e) {
            log.error("处理提醒取消事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * 处理确认结果（用户手动 确认 / 取消，以及 Scheduler 超时自动确认都会走到这里）。
     * <p>
     * 处理顺序：
     * 1. 把"确认/取消"当作 create_reminder 工具的执行结果写进对话历史；
     * 2. 调用 LLM 基于完整历史生成最终回复（传空工具列表，禁止 LLM 再输出工具调用）；
     * 3. sanitize 清理 JSON 片段 + 过程语/演戏段，空回复兜底为 "✅ 已确认提醒" / "❌ 已取消提醒"。
     * </p>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param confirmed 是否确认（true=确认/超时自动确认，false=取消）
     * @return Agent响应（TEXT 类型），不会被卡片打断
     */
    public AgentResponse handleConfirmationResult(Long userId, String sessionId, boolean confirmed) {
        log.info("处理确认结果: userId={}, sessionId={}, confirmed={}", userId, sessionId, confirmed);

        if (!agentEnabled) {
            log.debug("Agent未启用，跳过处理确认结果");
            return AgentResponse.text(confirmed ? "已确认" : "已取消");
        }

        String fallback = confirmed ? "✅ 已确认提醒创建。" : "❌ 已取消提醒创建。";

        try {
            String resultText = confirmed ? "用户已确认提醒创建" : "用户已取消提醒创建";

            // 添加工具执行结果到对话历史
            conversationMemory.addToolResult(userId, "create_reminder", resultText, confirmed);

            // 传 空工具列表：提醒的确认/取消结果已经落库，这里只让 LLM 生成"自然语言回复给用户"
            // 严禁再输出 ToolCall JSON
            List<ToolDefinition> noTools = List.of();
            List<Message> history = conversationMemory.getHistory(userId);

            String llmResponse = llmService.chatWithTools(resultText, noTools, history);
            String finalReply = sanitizeLlmOutput(llmResponse);
            if (finalReply == null || finalReply.isBlank()) {
                finalReply = fallback;
            }

            // 保存回复到对话历史（只存清理后的最终文案，避免落 JSON）
            conversationMemory.addAssistantMessage(userId, finalReply);
            log.info("确认结果处理完成: userId={}, confirmed={}, reply={}", userId, confirmed, finalReply);
            return AgentResponse.text(finalReply);

        } catch (Exception e) {
            log.error("处理确认结果失败: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage(), e);
            conversationMemory.addAssistantMessage(userId, fallback);
            return AgentResponse.text(fallback);
        }
    }

    /**
     * 获取Agent启用状态
     */
    public boolean isAgentEnabled() {
        return agentEnabled;
    }
}