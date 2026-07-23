package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.ToolCall;
import com.dormrepair.remindme.agent.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM服务
 * <p>
 * 调用DeepSeek API进行时间解析和意图识别，支持Function Calling模式
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 解析用户输入（原有方法，保持兼容）
     */
    public ParsedResult parseReminder(String userInput) {
        log.info("开始解析用户输入: {}", userInput);
        String prompt = buildPrompt(userInput);

        try {
            log.info("构建Prompt完成，开始调用LLM...");
            UserMessage userMessage = new UserMessage(prompt);
            Prompt promptObj = new Prompt(userMessage);

            String content = chatModel.call(promptObj).getResult().getOutput().getText();

            log.info("LLM调用成功，响应内容: {}", content);

            JsonNode root = objectMapper.readTree(content);

            return ParsedResult.builder()
                    .title(root.has("title") ? root.get("title").asText() : null)
                    .remindTime(root.has("remindTime") ? parseDateTime(root.get("remindTime").asText()) : null)
                    .intentType(root.has("intentType") ? root.get("intentType").asText() : "UNKNOWN")
                    .location(root.has("location") ? root.get("location").asText() : null)
                    .priority(root.has("priority") ? root.get("priority").asText() : "NORMAL")
                    .build();

        } catch (Exception e) {
            log.error("LLM解析失败，错误类型: {}, 错误信息: {}", e.getClass().getName(), e.getMessage());
            log.error("完整异常堆栈:", e);
            return ParsedResult.builder()
                    .title(userInput)
                    .intentType("UNKNOWN")
                    .build();
        }
    }

    /**
     * 带工具调用的对话方法
     * 
     * @param userInput 用户当前输入
     * @param tools 可用工具列表
     * @param history 对话历史
     * @return LLM响应内容
     */
    public String chatWithTools(String userInput, List<ToolDefinition> tools, List<Message> history) {
        log.info("开始带工具调用的对话: userInput={}, toolCount={}, historySize={}", userInput, tools.size(), history.size());
        
        List<org.springframework.ai.chat.messages.Message> messages = buildMessagesWithTools(userInput, tools, history);
        
        Prompt prompt = new Prompt(messages);
        ChatResponse response = chatModel.call(prompt);
        
        String content = response.getResult().getOutput().getText();
        log.info("工具调用对话响应: {}", content);
        
        return content;
    }

    /**
     * 解析LLM响应，判断是否包含工具调用
     * 
     * @param llmResponse LLM响应内容
     * @return ToolCall对象，如果不是工具调用则返回null
     */
    public ToolCall parseToolCall(String llmResponse) {
        try {
            JsonNode root = objectMapper.readTree(llmResponse);
            
            // 检查是否包含工具调用
            if (root.has("tool_call")) {
                JsonNode toolCallNode = root.get("tool_call");
                String toolName = toolCallNode.has("name") ? toolCallNode.get("name").asText() : null;
                
                if (toolName != null && !toolName.isBlank()) {
                    Map<String, Object> arguments = new HashMap<>();
                    if (toolCallNode.has("arguments")) {
                        JsonNode argsNode = toolCallNode.get("arguments");
                        argsNode.fields().forEachRemaining(entry -> {
                            arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                        });
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
                        String argsStr = functionCallNode.get("arguments").asText();
                        try {
                            JsonNode argsNode = objectMapper.readTree(argsStr);
                            argsNode.fields().forEachRemaining(entry -> {
                                arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                            });
                        } catch (Exception e) {
                            log.warn("解析arguments失败: {}", argsStr);
                        }
                    }
                    
                    log.info("解析到函数调用: functionName={}, arguments={}", functionName, arguments);
                    return ToolCall.of(functionName, arguments);
                }
            }
            
            // 如果不包含工具调用，检查是否是纯文本响应
            if (root.has("content") && !root.has("tool_call") && !root.has("function_call")) {
                return null;
            }
            
            // 如果是纯文本字符串，不是JSON格式的工具调用
            if (llmResponse != null && !llmResponse.startsWith("{")) {
                log.debug("LLM返回纯文本回复，非工具调用");
                return null;
            }
            
            log.debug("未解析到工具调用，响应内容: {}", llmResponse);
            return null;
            
        } catch (Exception e) {
            log.debug("LLM返回纯文本回复，非工具调用: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将JsonNode转换为Java对象
     */
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

    /**
     * 构建包含工具定义的消息列表
     */
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
                }
            }
        }
        
        // 添加用户当前输入
        messages.add(new UserMessage(userInput));
        
        return messages;
    }

    /**
     * 构建包含工具定义的系统提示词
     */
    private String buildSystemPromptWithTools(List<ToolDefinition> tools) {
        String currentDate = LocalDateTime.now().format(FORMATTER);
        
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
        
        return """
            你是一个智能提醒助手。你可以调用工具来完成用户的请求。
            
            当前时间：%s
            
            可用工具列表：
            %s
            
            重要说明：
            1. 用户ID（userId）由系统自动处理，你不需要提供此参数。
            2. 工具参数中没有列出的参数（如userId）不要在arguments中包含。
            3. 如果用户请求设置提醒，请调用create_reminder工具。
            4. 如果用户询问日程安排，请调用query_reminders工具。
            5. 如果用户要求修改提醒，请调用update_reminder工具。
            6. 如果用户要求取消提醒，请调用cancel_reminder工具。
            7. 如果用户询问天气，请调用query_weather工具。
            
            响应格式要求：
            1. 如果需要调用工具，请返回JSON格式：
               {"tool_call": {"name": "工具名称", "arguments": {"参数名": "参数值"}}}
            2. 如果不需要调用工具，可以直接回复用户，返回自然语言文本。
            3. 如果已经收到工具执行结果，请根据结果用自然语言总结回复用户。
            
            请根据用户的请求，决定是直接回复还是调用工具。
            """.formatted(currentDate, toolsJson);
    }

    /**
     * 将参数定义转换为JSON字符串
     */
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

    /**
     * 原有方法：构建解析提醒的Prompt
     */
    private String buildPrompt(String userInput) {
        String currentDate = LocalDateTime.now().format(FORMATTER);
        return """
            你是一个智能提醒助手。请解析用户的输入，提取提醒信息。
            
            当前时间：%s
            
            要求：
            1. 返回严格的JSON格式
            2. JSON包含以下字段：
               - title: 提醒事项名称（字符串，必填）
               - remindTime: 提醒时间（格式：yyyy-MM-dd HH:mm:ss，必填）
               - intentType: 意图类型（REMINDER/QUERY/DELETE/UPDATE/CONFIRM/CANCEL/WEATHER/UNKNOWN）
               - location: 地点（字符串，可选）
               - priority: 优先级（LOW/NORMAL/HIGH，默认NORMAL）
            
            时间解析规则：
            - "明天下午3点" → 当前日期+1天，15:00:00
            - "后天早上9点" → 当前日期+2天，09:00:00
            - "本周六晚上8点" → 本周六，20:00:00
            - "周一上午10点" → 最近的周一，10:00:00
            - "每天早上7点" → 今天，07:00:00（重复提醒）
            - "5分钟后" → 当前时间+5分钟
            - "等会"、"待会"、"过一会儿"、"一会儿" → 当前时间+10分钟
            - "现在"、"马上" → 当前时间
            - 如果无法解析时间，返回当前时间+10分钟
            
            示例输入："明天下午3点还书"
            示例输出：{"title":"还书","remindTime":"%s","intentType":"REMINDER","location":null,"priority":"NORMAL"}
            
            用户输入: %s
            """.formatted(currentDate, currentDate, userInput);
    }

    /**
     * 解析日期时间字符串
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, FORMATTER);
        } catch (Exception e) {
            log.warn("时间解析失败: {}", dateTimeStr);
            return null;
        }
    }

    /**
     * 解析结果记录类（原有）
     */
    public record ParsedResult(
            String title,
            LocalDateTime remindTime,
            String intentType,
            String location,
            String priority
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String title;
            private LocalDateTime remindTime;
            private String intentType;
            private String location;
            private String priority;

            public Builder title(String title) {
                this.title = title;
                return this;
            }

            public Builder remindTime(LocalDateTime remindTime) {
                this.remindTime = remindTime;
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

            public ParsedResult build() {
                return new ParsedResult(title, remindTime, intentType, location, priority);
            }
        }
    }
}