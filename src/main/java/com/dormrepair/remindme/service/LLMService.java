package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.ToolCall;
import com.dormrepair.remindme.agent.ToolDefinition;
import com.dormrepair.remindme.utils.TimeUtils;
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
                    .advanceMinutes(root.has("advanceMinutes") ? root.get("advanceMinutes").asInt() : 10)
                    .build();

        } catch (Exception e) {
            log.error("LLM解析失败，错误类型: {}, 错误信息: {}", e.getClass().getName(), e.getMessage());
            log.error("完整异常堆栈:", e);
            return ParsedResult.builder()
                    .title(userInput)
                    .intentType("UNKNOWN")
                    .advanceMinutes(10)
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
            11. 如果用户询问当前时间、今天日期、星期几，请调用get_current_time工具。
            12. 如果用户要求计算日期（如"3天后"、"下周一"、"两周后"），请调用calculate_date工具。
            13. 如果用户询问个人偏好设置（如默认城市、默认提前量、时区），请调用get_user_preferences工具。
            14. 如果用户要求修改偏好设置（如更改城市、提前量、昵称），请调用set_user_preferences工具。
            15. 如果用户询问某个提醒的具体详情（如"第一个提醒是什么时间"、"具体信息"），请调用get_reminder_detail工具。
            16. 如果用户询问位置（如"我在哪"、"当前位置"），请调用get_location工具。
            17. 如果用户询问个人信息（如"我是什么专业"、"我大几了"），请调用retrieve_private_knowledge工具。
            18. 如果用户提供个人信息（如"我是大三学生"），请调用add_private_memory工具。
            19. 如果用户修改个人信息，请调用update_private_memory工具。
            20. 如果用户要求删除个人信息，请调用delete_private_memory工具。
            21. 如果用户想看所有记忆，请调用get_all_private_memories工具。
            22. 如果用户询问生活小技巧（如"夏天怎么省电"），请调用retrieve_public_knowledge工具。

            ================================================
            RAG 工具使用说明：
            ================================================
            1. retrieve_private_knowledge - 查询用户已存储的记忆
               使用场景：用户问关于自己的问题，如"我是什么专业"、"我大几了"、"我喜欢吃什么"
               参数：query（查询关键词），topK（返回数量，传null自动计算）

            2. add_private_memory - 存储用户记忆
               使用场景：用户主动提供自己的信息，如"我是大三学生"、"我喜欢吃苹果"
               参数：
               - content：要存储的内容，如"用户是大三计算机专业学生"
               - category：记忆分类，从以下选择：
                 * PERSONAL_INFO - 用户个人信息（年级、专业、住址、性别等）
                 * ACTIVITY - 用户要做的事情（旅游、会议、实习等）
                 * EVENT - 重要日期（生日、纪念日等）
                 * COURSE - 固定课程安排（"每周三有数学课"）
                 * PLAN - 长期目标（"考研究生"、"减肥10斤"）
                 * EXAM_SCHEDULE - 考试安排（"6月20日期末考试"）
                 * DAILY_ROUTINE - 日常作息（"每天早上7点起床"）
                 * HEALTH_CONDITION - 健康状况（"对海鲜过敏"）
                 * DIET_PREFERENCE - 饮食偏好（"不吃辣"）
                 * HABIT - 个人习惯（"喜欢睡前看书"）
                 * INTEREST - 兴趣爱好（"喜欢打篮球"）
                 * LANGUAGE_STYLE - 语言风格偏好（"喜欢简洁回答"）
                 * OTHER - 以上都不属于的

            3. update_private_memory - 更新已存储的记忆
               使用场景：用户纠正之前说过的信息，如"我说错了，我是信息与计算科学专业"
               参数：memoryId（要更新的记忆ID），newContent（新内容）

            4. delete_private_memory - 删除记忆
               使用场景：用户要求删除某条记忆
               参数：memoryId（要删除的记忆ID）

            5. get_all_private_memories - 查看所有记忆
               使用场景：用户说"查看所有我的记忆"、"我存了哪些信息"
               参数：无

            6. retrieve_public_knowledge - 查询公共知识库
               使用场景：用户问生活小技巧、通用知识，如"夏天怎么省电"、"考试前要注意什么"
               参数：query（查询关键词），topK（返回数量，默认5）

            ================================================
            get_location 工具调用规则（非常重要！请严格遵守）
            ================================================
            当对话历史中包含 "用户当前GPS坐标" 信息时：
            1. 调用 get_location 工具时，必须将 latitude 和 longitude 作为参数传入
            2. 正确示例：
               {"tool_call": {"name": "get_location", "arguments": {"latitude": 23.1291, "longitude": 113.2644}}}
            3. 错误示例（会导致定位失败）：
               {"tool_call": {"name": "get_location", "arguments": {}}}

            当对话历史中不包含 GPS 坐标信息时：
            1. 调用 get_location 工具时，arguments 传空对象
            2. 示例：
               {"tool_call": {"name": "get_location", "arguments": {}}}

            记住：有坐标必须传坐标，不能传空！坐标值直接从对话历史中提取。

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

            RAG 补充说明：
            - 对于个人信息类的查询，优先调用RAG工具而非直接回答。
            - RAG工具和提醒工具可以组合使用。
            
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
            工具链调用规则（重要！）：
            ================================================
            你可以连续调用多个工具（最多3个）来完成复杂任务
            每次调用后，观察执行结果，决定是否需要继续调用
            示例：先查询天气 → 再创建提醒 → 综合回复
            如果工具调用失败，不要继续调用下一个工具，直接回复用户
            达到最大工具调用次数后，必须结束并回复用户

            ================================================
            条件判断规则（非常重要！必须严格遵守）：
            ================================================

            当用户说"如果...就..."、"如果...则..."、"假如...就..."时，你需要按以下步骤执行：

            步骤1：调用工具获取数据
              - 如果是天气条件，调用 query_weather_now 获取天气
              - 如果是日程条件，调用 query_reminders 获取日程
              - 如果是其他条件，调用对应的工具

            步骤2：根据返回的数据判断条件是否满足
              - 下雨判断：weather.text 包含"雨"或"暴雨"或"大雨"
              - 高温判断：weather.temp > 35°C
              - 低温判断：weather.temp < 5°C
              - 其他条件：根据常识判断

            步骤3：根据判断结果采取行动
              - 条件满足 → 调用 create_reminder 创建提醒，提醒标题根据场景自动生成
              - 条件不满足 → 直接回复用户，说明原因，不创建提醒

            示例1：用户说"查北京天气，如果下雨提醒我带伞"
              → 调用 query_weather_now(location="北京")
              → 返回：{"text": "晴", "temp": 29}
              → 判断：晴 ≠ 下雨 → 条件不满足
              → 回复："北京天气晴，不用带伞～"

            示例2：用户说"查北京天气，如果下雨提醒我带伞"
              → 调用 query_weather_now(location="北京")
              → 返回：{"text": "雨", "temp": 20}
              → 判断：text 包含"雨" → 条件满足
              → 调用 create_reminder(title="出门带伞", remindTime=当前时间+10分钟)
              → 回复："北京正在下雨，已创建带伞提醒 🌂"

            示例3：用户说"查广州天气，如果高温提醒我多喝水"
              → 调用 query_weather_now(location="广州")
              → 返回：{"text": "晴", "temp": 38}
              → 判断：temp > 35 → 条件满足
              → 调用 create_reminder(title="记得多喝水", remindTime=当前时间+10分钟)
              → 回复："广州今天高温38°C，已创建多喝水提醒 💧"

            重要提醒：
            1. 条件判断必须基于工具返回的实际数据，不能凭空猜测
            2. 如果条件满足，必须调用 create_reminder 创建提醒
            3. 如果条件不满足，必须明确告诉用户原因
            4. 提醒时间默认设置为当前时间后10分钟（用户未指定具体时间时）

            ================================================
            女性健康规则：
            ================================================

            当用户询问以下内容时，调用对应工具：

            1. 查询经期状态：
               - 用户说"我现在是什么时期"、"我现在是安全期吗"、"我在排卵期吗"、"我什么时候排卵"
               → 调用 get_period_status

            2. 查询下次经期：
               - 用户说"我下次经期是什么时候"、"月经什么时候来"、"下次月经"、"生理期什么时候"
               → 调用 get_next_period

            3. 记录经期开始：
               - 用户说"经期来了"、"今天来月经了"、"月经开始了"、"我来例假了"
               → 调用 update_period_start

            4. 更新经期设置：
               - 用户说"周期改成30天"、"经期持续5天"、"我的周期是35天"
               → 调用 update_period_settings

            注意事项：
            - 如果用户未设置性别为女性，工具会返回提示，请将提示转告用户
            - 经期信息属于个人隐私，不要随意推测或泄露
            - 回答时语气要温和、贴心，使用温暖的emoji
            - 如果用户尚未记录经期信息，请引导用户先设置上次经期开始日期

            ================================================
            任务完成判断规则（非常重要！）：
            ================================================

            调用工具并收到执行结果后，必须判断任务是否已完成：

            如果工具返回 success: true，说明任务已完成
              → 立即生成自然语言回复，告诉用户结果
              → 不要再调用任何工具，直接结束

            如果工具返回 success: false，说明任务失败
              → 查看错误信息，判断是否需要重试
              → 如果需要重试，可以再次调用工具
              → 如果不需要重试，告诉用户失败原因

            如果工具返回成功，但用户还有后续要求
              → 可以继续调用下一个工具

            示例：

            用户："记录今天为生理期起始日"
              → 调用 update_period_start → 返回 success: true
              → ✅ 任务完成，回复"已记录今天为经期开始日期 🌙"
              → ❌ 不要再调用 update_period_start

            用户："查北京天气，如果下雨提醒我带伞"
              → 调用 query_weather_now → 返回天气数据（success: true）
              → ⚠️ 任务未完成，还需要判断是否下雨
              → 继续判断天气条件，决定是否调用 create_reminder

            ================================================
            天气建议生成规则：
            ================================================
            查询天气后，根据天气状况生成贴心建议
            语气要自然、友好，像朋友聊天一样
            建议长度控制在15-20个字以内，简洁明了
            使用适当的emoji：
            下雨：🌧️
            高温：☀️
            低温：❄️
            下雪：❄️
            雷雨：⛈️
            示例：
            下雨："🌧️ 预报有雨，记得带伞哦～"
            高温："☀️ 天气炎热，注意防暑多喝水～"
            低温："❄️ 气温较低，注意保暖～"

            ================================================
            天气查询交互规则：
            ================================================
            用户只问"今天天气怎么样"时：
            先返回天气信息
            如果天气异常（下雨/高温/低温），主动询问是否需要创建提醒
            追问用户具体时间："你今天大概什么时候出门？我可以在你出门前提醒你带伞。"
            用户提供时间后：
            调用create_reminder创建提醒
            提醒标题根据场景自动生成（如"出门带伞"）
            确认创建成功
            用户明确要求"下雨提醒我"或"天气不好提醒我"时：
            直接创建提醒，使用默认提前量（10分钟）
            无需追问时间

            ================================================
            个人信息实时查询规则（必须严格遵守！）：
            ================================================
            当用户询问以下个人信息时，必须调用对应的工具从数据库实时获取最新数据：
            "我的城市是什么"、"默认城市是哪里" → 必须调用 get_user_preferences 工具
            "我的默认提前量是多少" → 必须调用 get_user_preferences 工具
            "我的昵称是什么" → 必须调用 get_user_preferences 工具

            ⚠️ 重要：即使对话历史中已有这些信息（也绝对不能使用历史数据，必须重新调用工具获取最新数据。
            原因：用户可能在个人设置页面修改了这些信息，历史数据可能已经过期。

            ================================================
            注意事项：
            ================================================
            1. 调用工具时，只返回JSON，不要返回"好的，我将调用xxx工具"等多余文字。
            2. 参数值必须与工具定义中的类型匹配（字符串用引号，数字不用引号）。
            3. 只调用必要的工具，不要重复调用。
            4. 如果收到工具执行结果，一定要用自然语言总结给用户，不要再调用工具。

            ================================================
            个人信息查询规则（必须严格遵守！）：
            ================================================
            当用户询问以下个人信息时，必须调用对应的工具从数据库实时获取最新数据：
            1. "我的城市是什么"、"默认城市是哪里" → 必须调用 get_user_preferences 工具
            2. "我的默认提前量是多少" → 必须调用 get_user_preferences 工具
            3. "我的昵称是什么" → 必须调用 get_user_preferences 工具

            ⚠️ 重要：即使对话历史中已有这些信息（如之前查询过），也绝对不能使用历史数据，必须重新调用工具获取最新数据。

            原因：用户可能在个人设置页面修改了这些信息，历史数据可能已经过期。

            ================================================
            模糊时间解析规则（非常重要！请严格遵守）：
            ================================================
            当用户使用以下模糊时间表达时，请自动解析为具体时间：
            1. "等会"、"待会"、"过一会儿"、"一会儿" → 当前时间+10分钟
            2. "5分钟后"、"5分后" → 当前时间+5分钟
            3. "10分钟后" → 当前时间+10分钟
            4. "30分钟后" → 当前时间+30分钟
            5. "1小时后"、"1个小时后" → 当前时间+60分钟
            6. "半小时后" → 当前时间+30分钟
            7. "现在"、"马上"、"立刻" → 当前时间
            8. "几分钟后" → 当前时间+5分钟
            
            示例：
            用户输入："等会提醒我拿快递"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "拿快递", "remindTime": "当前时间+10分钟"}}}
            
            用户输入："5分钟后提醒我喝水"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "喝水", "remindTime": "当前时间+5分钟"}}}
            
            用户输入："1小时后提醒我开会"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "开会", "remindTime": "当前时间+60分钟"}}}
            
            用户输入："现在提醒我"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "提醒", "remindTime": "当前时间"}}}
            
            注意：不要追问用户"等会是多久"，直接按照规则解析并创建提醒！

            ================================================
            位置查询规则：
            ================================================
            当用户询问位置相关问题时（如"我在哪""当前位置"等）：
            1. 检查对话历史中是否有用户传来的 GPS 坐标信息
            2. 如果有坐标，调用 get_location 工具，传入 latitude 和 longitude
            3. 如果没有坐标，调用 get_location 工具，不传经纬度（由工具从数据库读取）
            4. 工具返回地址后，用自然语言回复用户

            示例1（有GPS坐标）：
            - 对话历史包含：用户当前GPS坐标：纬度 23.1291，经度 113.2644
            - 用户输入："我在哪"
            - Agent输出：{"tool_call": {"name": "get_location", "arguments": {"latitude": 23.1291, "longitude": 113.2644}}}
            - 工具返回：{"success": true, "address": "广东省-广州市-荔湾区", "source": "gps"}
            - Agent回复："📍 你当前在广东省广州市荔湾区，已更新你的默认城市"

            示例2（无GPS坐标，有数据库记录）：
            - 用户输入："我在哪"
            - Agent输出：{"tool_call": {"name": "get_location", "arguments": {}}}
            - 工具返回：{"success": true, "address": "广东省-广州市-天河区", "source": "database"}
            - Agent回复："你的默认城市是广东省广州市天河区"

            示例3（无GPS坐标，无数据库记录）：
            - 用户输入："我在哪"
            - Agent输出：{"tool_call": {"name": "get_location", "arguments": {}}}
            - 工具返回：{"success": false, "message": "未设置默认城市，请在设置中配置"}
            - Agent回复："你还没有设置默认城市，请在设置页面配置哦～"

            ================================================
            回复风格要求（必须遵守）：
            ================================================

            1. 简洁自然：回复要像朋友聊天一样自然，不要像机器人念稿子
               - 错误："您的默认城市是广东省-广州市-荔湾区。"
               - 正确："你的默认城市是广州荔湾区哦～😊"

            2. 口语化表达：使用"你"而不是"您"（除非用户明确要求正式语气），使用日常用语
               - 错误："我已为您成功创建提醒。"
               - 正确："好嘞，已经帮你记下了！"

            3. 适当使用表情符号：在合适的地方添加表情，让回复更有温度
               - 确认成功：✅ 或 😊
               - 提醒创建：📌 或 ⏰
               - 查询结果：📋 或 📍
               - 错误提示：⚠️ 或 😅

            4. 省略冗余信息：不要重复用户已经知道的信息
               - 错误："您的默认城市是广东省-广州市-荔湾区，您可以在个人设置中修改默认城市。"
               - 正确："你的默认城市是广州荔湾区～"

            5. 根据场景调整语气：
               - 创建提醒时：积极、热情（"好嘞！"、"记下了！"）
               - 查询信息时：清晰、直接（"你最近有3个提醒"）
               - 出错时：友好、帮助性（"没找到这个提醒，要不要重新确认一下？"）
               - 确认操作时：简洁、确认（"好的，已更新"）

            6. 格式规范：
               - 城市名称显示为"城市名+区名"（如"广州荔湾"），不需要显示"广东省-广州市-荔湾区"这种完整路径
               - 时间显示为"明天下午4点"而不是"2026-07-26 16:00:00"
               - 提醒列表使用编号显示

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
               - advanceMinutes: 默认提前量（整数，可选，默认10分钟）
            
            时间解析规则：
            - "明天下午3点" → 当前日期+1天，15:00:00
            - "后天早上9点" → 当前日期+2天，09:00:00
            - "本周六晚上8点" → 本周六，20:00:00
            - "周一上午10点" → 最近的周一，10:00:00
            - "每天早上7点" → 今天，07:00:00（重复提醒）
            - "5分钟后" → 当前时间+5分钟，advanceMinutes=5
            - "等会"、"待会"、"过一会儿"、"一会儿" → 当前时间+10分钟，advanceMinutes=10
            - "30分钟后" → 当前时间+30分钟，advanceMinutes=30
            - "1小时后" → 当前时间+60分钟，advanceMinutes=60
            - "现在"、"马上" → 当前时间，advanceMinutes=0
            - 如果无法解析时间，返回当前时间+10分钟，advanceMinutes=10
            
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
     * 智能解析用户的修改意图
     * <p>
     * 根据用户输入和当前确认卡片数据，分析用户想修改哪个字段（时间/地点/标题/优先级）
     * </p>
     *
     * @param userInput   用户输入的修改文本
     * @param currentData 当前确认卡片中的完整数据
     * @return EditIntent 对象，包含 field 和 newValue
     */
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

    /**
     * 校验 field 是否合法
     */
    private boolean isValidField(String field) {
        return "time".equals(field) || "location".equals(field)
                || "title".equals(field) || "priority".equals(field)
                || "unknown".equals(field);
    }

    /**
     * 构建修改意图解析的 Prompt
     */
    private String buildEditIntentPrompt(String userInput, Map<String, Object> currentData) {
        String currentTime = TimeUtils.format(TimeUtils.now());

        String title = currentData.getOrDefault("title", "").toString();
        String remindTime = currentData.getOrDefault("remindTime", "").toString();
        String location = currentData.getOrDefault("location", "").toString();
        String priority = currentData.getOrDefault("priority", "NORMAL").toString();

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

    /**
     * 修改意图解析结果
     */
    @Data
    @Builder
    public static class EditIntent {
        /** 修改的字段名：time/location/title/priority/unknown */
        private String field;
        /** 新值 */
        private String newValue;
        /** 简短说明 */
        private String reason;
    }

    /**
     * 解析结果记录类（原有）
     */
    public record ParsedResult(
            String title,
            LocalDateTime remindTime,
            String intentType,
            String location,
            String priority,
            Integer advanceMinutes
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
            private Integer advanceMinutes;

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

            public Builder advanceMinutes(Integer advanceMinutes) {
                this.advanceMinutes = advanceMinutes;
                return this;
            }

            public ParsedResult build() {
                return new ParsedResult(title, remindTime, intentType, location, priority, advanceMinutes);
            }
        }
    }

    /**
     * 生成天气建议
     * <p>
     * 根据天气信息和提醒标题生成贴心建议
     * </p>
     *
     * @param weatherText   天气状况文字描述（如"晴"、"小雨"、"雷阵雨"）
     * @param temperature   温度（摄氏度）
     * @param remindTitle   提醒标题（用于上下文）
     * @param location      提醒地点（用于上下文）
     * @return 天气建议字符串，如果天气正常则返回null
     */
    public String generateWeatherSuggestion(String weatherText, String temperature, String remindTitle, String location) {
        log.info("生成天气建议: weatherText={}, temperature={}, title={}, location={}", 
                weatherText, temperature, remindTitle, location);

        // 正常天气不生成建议
        if (isNormalWeather(weatherText)) {
            log.debug("天气正常，不生成建议");
            return null;
        }

        try {
            String currentTime = TimeUtils.now().format(FORMATTER);
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

    /**
     * 判断是否为正常天气
     */
    private boolean isNormalWeather(String weatherText) {
        if (weatherText == null || weatherText.isBlank()) {
            return true;
        }
        String text = weatherText.toLowerCase();
        // 正常天气：晴、多云、阴
        return text.contains("晴") || text.contains("多云") || text.contains("阴");
    }
}