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
        if (llmResponse == null || llmResponse.isBlank()) {
            log.debug("LLM响应为空");
            return null;
        }

        // 调试日志：打印原始响应（截断）
        String truncatedResponse = llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse;
        log.debug("开始解析LLM响应: {}", truncatedResponse);

        // 方式一：标准JSON解析
        try {
            JsonNode root = objectMapper.readTree(llmResponse);
            ToolCall result = parseStandardJson(root);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            log.debug("标准JSON解析失败: {}", e.getMessage());
        }

        // 方式二：尝试修复JSON后再解析
        String fixedJson = fixJson(llmResponse);
        if (!fixedJson.equals(llmResponse)) {
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

        // 方式三：从文本中提取JSON片段（正则表达式）
        String jsonFragment = extractJsonFragment(llmResponse);
        if (jsonFragment != null) {
            log.debug("提取到JSON片段: {}", jsonFragment.length() > 200 ? jsonFragment.substring(0, 200) + "..." : jsonFragment);
            try {
                JsonNode root = objectMapper.readTree(jsonFragment);
                ToolCall result = parseStandardJson(root);
                if (result != null) {
                    log.debug("提取JSON片段解析成功");
                    return result;
                }
            } catch (Exception e) {
                log.debug("提取JSON片段解析失败: {}", e.getMessage());
            }
        }

        // 如果是纯文本字符串，不是JSON格式的工具调用
        if (!llmResponse.trim().startsWith("{")) {
            log.debug("LLM返回纯文本回复，非工具调用");
            return null;
        }

        log.debug("未解析到工具调用，响应内容: {}", truncatedResponse);
        return null;
    }

    /**
     * 解析标准JSON格式的工具调用
     */
    private ToolCall parseStandardJson(JsonNode root) {
        // 检查是否包含工具调用（自定义格式）
        if (root.has("tool_call")) {
            JsonNode toolCallNode = root.get("tool_call");
            String toolName = toolCallNode.has("name") ? toolCallNode.get("name").asText() : null;
            
            if (toolName != null && !toolName.isBlank()) {
                Map<String, Object> arguments = new HashMap<>();
                if (toolCallNode.has("arguments")) {
                    JsonNode argsNode = toolCallNode.get("arguments");
                    if (argsNode.isObject()) {
                        argsNode.fields().forEachRemaining(entry -> {
                            arguments.put(entry.getKey(), convertJsonNode(entry.getValue()));
                        });
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

    /**
     * 修复常见的JSON格式问题
     */
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

    /**
     * 修复JSON字符串中未转义的引号
     */
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

    /**
     * 从文本中提取JSON片段
     */
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

    /**
     * 统计字符串中字符出现的次数
     */
    private int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
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
            7. 如果用户询问当前天气，请调用query_weather_now工具。
            8. 如果用户询问未来天气，请调用query_weather_forecast工具。
            9. 如果用户询问空气质量，请调用query_air_quality工具。
            10. 如果用户询问天气预警，请调用query_weather_alert工具。

            ================================================
            时间复用规则（非常重要！请严格遵守）：
            ================================================
            当用户说"再提醒我..."、"也提醒我..."、"同样提醒我..."、"再帮我提醒..."时，表示复用上一个提醒的时间：
            1. 从对话历史（包括工具调用记录和工具执行结果）中查找最近一次创建提醒时使用的 remindTime
            2. 如果找到，新提醒使用相同的 remindTime
            3. 如果对话历史中没有找到任何提醒时间，请追问用户具体时间，不要使用当前时间或默认时间

            示例：
            对话历史：用户说"明天下午4点提醒我喝咖啡"，工具执行结果包含 remindTime: "2026-07-26 16:00:00"
            用户输入："再提醒我带伞"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "带伞", "remindTime": "2026-07-26 16:00:00", "location": null, "priority": "NORMAL"}}}

            对话历史：无任何提醒创建记录
            用户输入："再提醒我带伞"
            你的输出："请问你想在什么时间提醒你带伞呢？"

            ================================================
            响应格式要求（非常重要！请严格遵守）：
            ================================================
            
            【情况一：需要调用工具】
            如果需要调用工具来完成用户请求，请返回严格的JSON格式，不要包含任何其他文本：
            {"tool_call": {"name": "工具名称", "arguments": {"参数名": "参数值"}}}
            
            工具调用示例：
            示例1（创建提醒）：
            用户输入："明天下午3点提醒我还书"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "还书", "remindTime": "2026-07-26 15:00:00"}}}
            
            示例2（查询日程）：
            用户输入："我今天有什么安排？"
            你的输出：{"tool_call": {"name": "query_reminders", "arguments": {"date": "2026-07-25"}}}
            
            示例3（取消提醒）：
            用户输入："取消还书的提醒"
            你的输出：{"tool_call": {"name": "cancel_reminder", "arguments": {"reminderId": 1}}}
            
            【情况二：不需要调用工具】
            如果不需要调用工具，可以直接用自然语言回复用户。
            
            示例：
            用户输入："你好"
            你的输出："你好！我是智能提醒助手，可以帮你设置提醒、查询日程和天气。请问有什么需要帮助的吗？"
            
            【情况三：收到工具执行结果后】
            如果已经收到工具执行结果，需要根据结果用自然语言总结回复用户。
            
            示例：
            工具执行结果：{"success": true, "message": "提醒创建成功", "title": "还书", "remindTime": "2026-07-26 15:00:00"}
            你的输出："好的，已为你创建提醒「还书」，时间是明天下午3点。"
            
            ================================================
            注意事项：
            ================================================
            1. 调用工具时，只返回JSON，不要返回"好的，我将调用xxx工具"等多余文字。
            2. 参数值必须与工具定义中的类型匹配（字符串用引号，数字不用引号）。
            3. 只调用必要的工具，不要重复调用。
            4. 如果收到工具执行结果，一定要用自然语言总结给用户，不要再调用工具。
            
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