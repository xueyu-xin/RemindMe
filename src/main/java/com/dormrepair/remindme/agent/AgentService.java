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
    private static final int MAX_TOOL_CALLS = 3;

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

            // 获取对话历史
            List<Message> history = conversationMemory.getHistory(userId);

            // 获取可用工具
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();

            // ReAct循环：思考-行动-观察
            String llmResponse = null;
            Object lastToolResult = null;
            int iteration = 0;
            int toolCallCount = 0;

            while (iteration < MAX_ITERATIONS) {
                iteration++;
                log.debug("ReAct循环第 {} 次迭代，已调用工具 {} 次", iteration, toolCallCount);

                // 思考：调用LLM
                llmResponse = llmService.chatWithTools(userInput, tools, history);

                // 解析LLM响应
                ToolCall llmToolCall = llmService.parseToolCall(llmResponse);

                if (llmToolCall == null) {
                    // 直接回复用户，循环结束
                    log.info("LLM直接回复，结束循环（迭代次数: {}, 工具调用次数: {}）", iteration, toolCallCount);
                    conversationMemory.addAssistantMessage(userId, llmResponse);
                    return buildAgentResponse(llmResponse, lastToolResult);
                }

                // 检查是否达到最大工具调用次数
                if (toolCallCount >= MAX_TOOL_CALLS) {
                    log.warn("达到最大工具调用次数 {}，跳过工具调用", MAX_TOOL_CALLS);
                    conversationMemory.addAssistantMessage(userId, llmResponse);
                    return buildAgentResponse(llmResponse, lastToolResult);
                }

                // 行动：执行工具调用
                log.info("LLM决定调用工具({}/{}) : {}", toolCallCount + 1, MAX_TOOL_CALLS, llmToolCall.getToolName());
                ToolCall toolCall = ToolCall.of(llmToolCall.getToolName(), llmToolCall.getArguments());
                String toolResult = executeToolCall(userId, toolCall);
                toolCallCount++;

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
                AgentResponse confirmationResponse = buildConfirmationResponse(toolResult);
                if (confirmationResponse != null) {
                    log.info("工具结果包含sessionId，构建CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                    return confirmationResponse;
                }

                // 如果工具执行失败，继续循环让LLM处理降级结果
                // 不再直接退出循环，而是让LLM有机会根据失败结果生成友好回复
                if (!toolSuccess) {
                    log.info("工具执行失败，继续循环让LLM处理降级结果");
                }

                // 更新历史（工具结果已添加到对话历史中，不再修改userInput）
                // LLM下次迭代时会通过对话历史看到工具执行结果（包括失败结果）
                history = conversationMemory.getHistory(userId);
                lastToolResult = toolResult;
            }

            // 达到最大迭代次数，返回最后一次响应
            log.warn("达到最大迭代次数 {}，返回最后响应（工具调用次数: {}）", MAX_ITERATIONS, toolCallCount);
            conversationMemory.addAssistantMessage(userId, llmResponse);
            return buildAgentResponse(llmResponse, lastToolResult);

        } catch (Exception e) {
            log.error("Agent处理请求失败: userId={}, error={}", userId, e.getMessage(), e);
            return AgentResponse.error("处理请求时发生错误: " + e.getMessage());
        }
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
                log.info("执行工具调用: userId={}, toolName={}, 尝试 {}/{}", userId, toolName, attempt, maxRetries);

                // 根据重试次数调整参数
                Map<String, Object> adjustedArgs = prepareArgumentsForRetry(arguments, attempt);

                // 检查工具是否存在
                if (!toolRegistry.hasTool(toolName)) {
                    String errorMsg = "工具不存在: " + toolName;
                    log.warn(errorMsg);
                    return "{\"success\": false, \"error\": \"" + errorMsg + "\"}";
                }

                // 执行工具
                Object result = toolRegistry.executeTool(toolName, adjustedArgs);

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
            case "update_reminder" ->
                "修改提醒失败，请确认提醒是否存在，稍后再试。";
            case "cancel_reminder" ->
                "取消提醒失败，请确认提醒是否存在，稍后再试。";
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
     * 构建统一的响应对象
     *
     * @param llmOutput LLM输出内容
     * @param toolResult 工具执行结果（可能为null）
     * @return Agent响应
     */
    public AgentResponse buildAgentResponse(String llmOutput, Object toolResult) {
        log.debug("构建Agent响应: llmOutput={}, toolResult={}", llmOutput, toolResult);

        // 检查是否是工具调用响应
        ToolCall toolCall = llmService.parseToolCall(llmOutput);
        if (toolCall != null) {
            return AgentResponse.toolCall(toolCall.getToolName(), toolCall.getArguments());
        }

        // 检查工具执行结果是否包含sessionId（create_reminder工具触发确认流程）
        if (toolResult != null) {
            AgentResponse confirmationResponse = buildConfirmationResponse(toolResult);
            if (confirmationResponse != null) {
                log.info("工具结果包含sessionId，返回CONFIRMATION类型响应");
                return confirmationResponse;
            }
        }

        // 检查是否包含错误信息
        if (llmOutput != null && llmOutput.contains("error")) {
            return AgentResponse.error(llmOutput);
        }

        // 默认返回文本响应
        AgentResponse response = AgentResponse.text(llmOutput);
        if (toolResult != null) {
            response.setToolResult(toolResult);
        }
        return response;
    }

    /**
     * 从工具执行结果中构建CONFIRMATION响应
     * <p>
     * 检查工具结果是否包含sessionId（create_reminder工具返回），
     * 如果包含则构建CONFIRMATION类型的响应
     * </p>
     *
     * @param toolResult 工具执行结果（JSON字符串或Map）
     * @return CONFIRMATION响应，如果不包含sessionId则返回null
     */
    private AgentResponse buildConfirmationResponse(Object toolResult) {
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
                return null;
            }

            // 检查是否是成功的工具结果且包含sessionId
            if (!Boolean.TRUE.equals(resultMap.get("success")) || !resultMap.containsKey("sessionId")) {
                return null;
            }

            String sessionId = (String) resultMap.get("sessionId");
            String title = (String) resultMap.get("title");
            String remindTime = (String) resultMap.get("remindTime");
            String location = (String) resultMap.get("location");

            Map<String, Object> confirmationData = new HashMap<>();
            confirmationData.put("title", title);
            confirmationData.put("remindTime", remindTime);
            confirmationData.put("location", location);
            if (resultMap.containsKey("priority")) {
                confirmationData.put("priority", resultMap.get("priority"));
            }

            String content = "📋 我帮你解析了提醒信息，请确认：";
            return AgentResponse.confirmation(sessionId, content, confirmationData);
        } catch (Exception e) {
            log.warn("解析工具结果构建CONFIRMATION响应失败: {}", e.getMessage());
            return null;
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
     * 处理确认结果
     * <p>
     * 当用户在确认卡片上操作后，通知Agent
     * </p>
     *
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param confirmed 是否确认
     * @return Agent响应
     */
    public AgentResponse handleConfirmationResult(Long userId, String sessionId, boolean confirmed) {
        log.info("处理确认结果: userId={}, sessionId={}, confirmed={}", userId, sessionId, confirmed);

        if (!agentEnabled) {
            log.debug("Agent未启用，跳过处理确认结果");
            return AgentResponse.text(confirmed ? "已确认" : "已取消");
        }

        try {
            String resultText = confirmed ? "用户已确认提醒创建" : "用户已取消提醒创建";
            
            // 添加工具执行结果到对话历史
            conversationMemory.addToolResult(userId, "create_reminder", resultText, confirmed);

            // 调用LLM生成回复
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
            List<Message> history = conversationMemory.getHistory(userId);
            
            String llmResponse = llmService.chatWithTools(resultText, tools, history);
            
            // 保存回复到对话历史
            conversationMemory.addAssistantMessage(userId, llmResponse);

            return AgentResponse.text(llmResponse);

        } catch (Exception e) {
            log.error("处理确认结果失败: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage(), e);
            return AgentResponse.text(confirmed ? "✅ 已确认提醒" : "❌ 已取消提醒");
        }
    }

    /**
     * 获取Agent启用状态
     */
    public boolean isAgentEnabled() {
        return agentEnabled;
    }
}