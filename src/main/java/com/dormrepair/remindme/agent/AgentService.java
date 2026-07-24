package com.dormrepair.remindme.agent;

import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.event.ReminderCancelledEvent;
import com.dormrepair.remindme.agent.event.ReminderConfirmedEvent;
import com.dormrepair.remindme.agent.event.ReminderCreatedEvent;
import com.dormrepair.remindme.service.LLMService;
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

    @Value("${agent.enabled:false}")
    private boolean agentEnabled;

    /**
     * 最大迭代次数（防止无限循环）
     */
    private static final int MAX_ITERATIONS = 3;

    /**
     * 处理用户请求的主入口
     *
     * @param userId 用户ID
     * @param userInput 用户输入
     * @return Agent响应
     */
    public AgentResponse processRequest(Long userId, String userInput) {
        log.info("Agent开始处理请求: userId={}, userInput={}", userId, userInput);

        try {
            // 保存用户输入到对话历史
            conversationMemory.addUserMessage(userId, userInput);

            // 获取对话历史
            List<Message> history = conversationMemory.getHistory(userId);

            // 获取可用工具
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();

            // ReAct循环：思考-行动-观察
            String llmResponse = null;
            Object lastToolResult = null;
            int iteration = 0;

            while (iteration < MAX_ITERATIONS) {
                iteration++;
                log.debug("ReAct循环第 {} 次迭代", iteration);

                // 思考：调用LLM
                llmResponse = llmService.chatWithTools(userInput, tools, history);

                // 解析LLM响应
                ToolCall llmToolCall = llmService.parseToolCall(llmResponse);

                if (llmToolCall == null) {
                    // 直接回复用户，循环结束
                    log.info("LLM直接回复，结束循环");
                    conversationMemory.addAssistantMessage(userId, llmResponse);
                    return buildAgentResponse(llmResponse, lastToolResult);
                }

                // 行动：执行工具调用
                log.info("LLM决定调用工具: {}", llmToolCall.getToolName());
                ToolCall toolCall = ToolCall.of(llmToolCall.getToolName(), llmToolCall.getArguments());
                String toolResult = executeToolCall(userId, toolCall);

                // 保存工具调用和结果到对话历史
                conversationMemory.addToolCall(userId, llmToolCall.getToolName(), llmToolCall.getArguments());
                conversationMemory.addToolResult(userId, llmToolCall.getToolName(), toolResult, true);

                // 检查工具执行结果是否包含sessionId（create_reminder工具触发确认流程）
                AgentResponse confirmationResponse = buildConfirmationResponse(toolResult);
                if (confirmationResponse != null) {
                    log.info("工具结果包含sessionId，构建CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                    return confirmationResponse;
                }

                // 更新历史（工具结果已添加到对话历史中，不再修改userInput）
                // LLM下次迭代时会通过对话历史看到工具执行结果
                history = conversationMemory.getHistory(userId);
                lastToolResult = toolResult;
            }

            // 达到最大迭代次数，返回最后一次响应
            log.warn("达到最大迭代次数 {}，返回最后响应", MAX_ITERATIONS);
            conversationMemory.addAssistantMessage(userId, llmResponse);
            return buildAgentResponse(llmResponse, lastToolResult);

        } catch (Exception e) {
            log.error("Agent处理请求失败: userId={}, error={}", userId, e.getMessage(), e);
            return AgentResponse.error("处理请求时发生错误: " + e.getMessage());
        }
    }

    /**
     * 执行具体的工具调用（带userId）
     *
     * @param userId 用户ID
     * @param toolCall 工具调用信息
     * @return 执行结果（JSON格式）
     */
    public String executeToolCall(Long userId, ToolCall toolCall) {
        log.info("执行工具调用: userId={}, toolName={}, arguments={}", userId, toolCall.getToolName(), toolCall.getArguments());

        try {
            // 检查工具是否存在
            if (!toolRegistry.hasTool(toolCall.getToolName())) {
                String errorMsg = "工具不存在: " + toolCall.getToolName();
                log.warn(errorMsg);
                return "{\"success\": false, \"error\": \"" + errorMsg + "\"}";
            }

            // 将 userId 注入到工具参数中
            Map<String, Object> arguments = new java.util.HashMap<>(toolCall.getArguments());
            arguments.put("userId", userId);

            // 执行工具
            Object result = toolRegistry.executeTool(toolCall.getToolName(), arguments);

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
                                toolCall.getToolName(), resultMap.get("sessionId"));
                    }
                } catch (Exception e) {
                    log.warn("解析工具结果sessionId失败: {}", e.getMessage());
                }
            } else {
                // 如果返回值是 DTO 对象，包装一层（如 WeatherService）
                resultJson = "{\"success\": true, \"data\": " + serializeResult(result) + "}";
            }
            log.info("工具执行成功: userId={}, toolName={}, result={}", userId, toolCall.getToolName(), resultJson);
            return resultJson;

        } catch (Exception e) {
            log.error("工具执行失败: userId={}, toolName={}, error={}", userId, toolCall.getToolName(), e.getMessage(), e);
            return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
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