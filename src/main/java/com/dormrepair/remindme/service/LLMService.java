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
    private final UserService userService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 解析用户输入（原有方法，保持兼容，使用默认10分钟）
     */
    public ParsedResult parseReminder(String userInput) {
        return parseReminder(userInput, null);
    }

    /**
     * 解析用户输入，并根据用户ID获取其默认提前量
     */
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

            int advanceMinutes = root.has("advanceMinutes") ? root.get("advanceMinutes").asInt() : defaultAdvance;

            return ParsedResult.builder()
                    .title(root.has("title") ? root.get("title").asText() : null)
                    .remindTime(root.has("remindTime") ? parseDateTime(root.get("remindTime").asText()) : null)
                    .endTime(root.has("endTime") && !root.get("endTime").isNull() ? parseDateTime(root.get("endTime").asText()) : null)
                    .intentType(root.has("intentType") ? root.get("intentType").asText() : "UNKNOWN")
                    .location(root.has("location") ? root.get("location").asText() : null)
                    .priority(root.has("priority") ? root.get("priority").asText() : "NORMAL")
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
        
        if (content == null || content.isBlank()) {
            log.warn("LLM返回空响应！finishReason={}, tokensUsed={}",
                    response.getResult().getMetadata() != null ? response.getResult().getMetadata().getFinishReason() : "unknown",
                    response.getMetadata() != null && response.getMetadata().getUsage() != null
                            ? response.getMetadata().getUsage().getTotalTokens() : "unknown");
        }

        return content;
    }

    /**
     * 兜底总结方法：不挂任何工具，基于"本次对话"的消息让 LLM 生成最终自然语言总结。
     * <p>
     * 适用场景：
     * 1. Agent 达到最大迭代次数仍未结束循环（往往因为 LLM 陷入重复工具调用）；
     * 2. 连续多次检测到 LLM 重复调用相同工具+相同参数，强制收尾。
     * <p>
     * 调用方应只传入"本次对话"的消息切片（从用户当前轮请求开始），
     * 不能传全量历史——否则 LLM 会把历史轮次的任务也总结进去。
     * <p>
     * 要求 LLM 明确分三类回复用户：
     *   ✅ 已完成的、❌ 未完成的、❓ 需要向用户询问的。
     *
     * @param history 本次对话的消息切片（含用户原始请求、工具调用与结果）
     * @return 自然语言总结回复；若 LLM 失败返回 null
     */
    public String generateSummaryFromHistory(List<Message> history) {
        log.info("开始生成兜底总结回复: currentTurnHistorySize={}", history == null ? 0 : history.size());

        String summaryPrompt = """
                你是智能提醒助手的收尾总结模块。当前对话已达到执行上限或检测到重复调用，需要你根据【本次对话】的消息生成最终自然语言回复。

                ⚠️ 你看到的对话历史只包含【本次对话】的内容（用户当前这一轮请求及其触发的工具调用/结果），
                不包含之前的对话轮次。你只能基于这部分内容总结，绝对禁止提及本次对话之外的任务。

                请严格遵守以下规则：
                1. 严禁再次输出任何 {"tool_call": ...} 格式的 JSON，只能输出自然语言回复。
                2. 【反幻觉铁律】严禁编造、严禁脑补、严禁推测：
                   a) 不能把"没调用过工具"说成"已完成"；
                   b) 不能把"工具返回 success=false"说成"已成功"；
                   c) 不能把"工具返回的结果"以外的内容当成事实陈述给用户；
                   d) 工具返回 message 里写了什么，你才能说什么；message 没写的，绝对不能自己补充。
                   ❌ 错误示例（幻觉）：本次只调用了 update_period_settings，没调用 update_period_start，
                      但你回复"上次经期开始日期已记录为 6-29"——这是编造！
                   ✅ 正确做法：只说 update_period_settings 实际返回的内容（如"经期周期已更新为31天"），
                      对于未调用的部分，归到"❌ 未完成的"里说明"上次经期开始日期未设置"。
                3. 必须按三类清晰回复：
                   ✅ 已完成的：仅根据对话中工具返回 success=true 的内容，逐条说明完成了什么；
                   ❌ 未完成的：用户要求但工具未成功执行、或根本没调用对应工具的部分，说明原因；
                   ❓ 需要询问的：用户表述缺失关键参数的部分，明确向用户提问。
                4. 三类都要覆盖，没有某类就省略；至少要有"已完成的"或"未完成的"其中之一。
                5. 回复语气要自然、温暖，像朋友聊天，可适当使用 emoji。
                6. 不要再提及"系统提示""达到上限""重复调用"等内部细节。
                7. 严禁出现"已经记录过啦/之前已经完成/你已经说过啦"等冗余话术（除非工具结果明确返回"已存在相同日程"）。
                """;

        try {
            List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(summaryPrompt));

            if (history != null) {
                for (Message msg : history) {
                    if ("user".equals(msg.getRole())) {
                        messages.add(new UserMessage(msg.getContent()));
                    } else if ("assistant".equals(msg.getRole())) {
                        messages.add(new AssistantMessage(msg.getContent()));
                    } else if ("system".equals(msg.getRole())) {
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

    /**
     * 解析LLM响应，判断是否包含工具调用
     *
     * @param llmResponse LLM响应内容
     * @return ToolCall对象，如果不是工具调用则返回null
     */
    public ToolCall parseToolCall(String llmResponse) {
        List<ToolCall> calls = parseToolCalls(llmResponse);
        return calls.isEmpty() ? null : calls.get(0);
    }

    /**
     * 解析LLM响应中的所有工具调用（支持一次返回多个 {"tool_call":{...}}）。
     * <p>
     * 修复点：原 parseToolCall 仅用 Jackson readTree 读取第一个 JSON value，
     * 当 LLM 一次返回多行工具调用时，后面的会被丢弃。
     * 本方法用平衡括号扫描，逐个提取顶层 JSON 对象并解析为 ToolCall。
     * </p>
     *
     * @param llmResponse LLM响应内容
     * @return 工具调用列表（可能为空，但永不为null）
     */
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

    /**
     * 尝试把一段文本当作单个 JSON 解析为 ToolCall（含 fixJson 修复 + extractJsonFragment 兜底）
     */
    private ToolCall tryParseSingleJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 方式一：标准JSON解析
        try {
            JsonNode root = objectMapper.readTree(text);
            ToolCall result = parseStandardJson(root);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            log.debug("标准JSON解析失败: {}", e.getMessage());
        }

        // 方式二：尝试修复JSON后再解析
        String fixedJson = fixJson(text);
        if (!fixedJson.equals(text)) {
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
        String jsonFragment = extractJsonFragment(text);
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

        return null;
    }

    /**
     * 用平衡括号扫描，从文本中提取所有顶层 JSON 对象（{ ... } 配对）。
     * 用于 LLM 一次返回多个 {"tool_call":{...}} 拼在一起的情况。
     * 注意：会跳过字符串内部的 { }，避免被 JSON 字符串值里的括号干扰。
     */
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
            ╔══════════════════════════════════════════════════════════════════╗
            ║ 【最高优先级铁律 - 违反任何一条即视为严重错误！】                     ║
            ╠══════════════════════════════════════════════════════════════════╣
            ║ ① 先调用工具完成所有任务，再回复用户。                              ║
            ║   「对所有任务都有效，不仅限于RAG！查询/设置/提醒/删除/修改/记录」  ║
            ║                                                                    ║
            ║ ② 禁止任何"过程性语言"出现在回复里。                               ║
            ║   好的我帮你 / 好的这就帮你 / 现在帮你 / 先帮你查一下 /            ║
            ║   我先查一下 / 让我看看 / 这就帮你查 / 马上帮你处理 ……              ║
            ║   这些内部执行过程，绝对不能展示给用户看！                          ║
            ║                                                                    ║
            ║ ③ 多任务时先分析依赖，再决定合理执行顺序。                          ║
            ║   · 必须先"查到"才能"修改/取消"的，按依赖前置（先查后改）          ║
            ║   · 彼此独立的任务，按用户语句的意图相关性合理编排                 ║
            ║   · 禁止硬套"用户语句出现顺序"而破坏依赖（导致查不到ID改失败）     ║
            ║                                                                    ║
            ║ ④ 未完成的任务，不准说"已完成"。                                   ║
            ║   只有真正收到工具返回 success=true 的结果后，才能说完成了。        ║
            ║                                                                    ║
            ║ ⑤ 不允许"口头承诺式回复"。                                         ║
            ║   比如"把拿快递的提醒改到下午5点" → 不能只说"好的我帮你改"，       ║
            ║   必须先 query_reminders 查到ID，再 update_reminder 真正修改。    ║
            ║                                                                    ║
            ║ ⑥ 禁止任何"中文演戏格式"。                                         ║
            ║   不准写"工具调用: xxx, 参数: {…}"、不准写"工具执行结果: {…}"。    ║
            ║   需要调用工具时，只能输出 【纯JSON】：                             ║
            ║   {"tool_call":{"name":"工具名","arguments":{…}}}                 ║
            ║   纯JSON前后严禁夹杂任何自然语言、换行、解释。                     ║
            ║                                                                    ║
            ║ ⑦ 用户一句话多个任务时，最终回复必须分三类说明：                    ║
            ║   ✅ 成功的：明确说"已完成XXX、已完成YYY"（具体做了什么）          ║
            ║   ❌ 失败的：明确说"XXX没能完成，因为……"（失败原因）               ║
            ║   ❓ 缺参需要询问的：先不调任何工具，直接问用户缺的参数，           ║
            ║      并说明"请问你要查询/设置的是哪一个XXX？"，等用户下次回复再调。║
            ║   三类都要列出来，不能只说一部分。                                  ║
            ║                                                                    ║
            ║ ⑧ 禁止重复调用"已执行过的相同工具 + 相同参数"。                     ║
            ║   工具一旦返回 success=true，该任务即视为完成，不得再次用同样参数  ║
            ║   调同一工具（例如反复 add_private_memory 同一句"用户是大三学生"）。║
            ║   多任务时，完成一个就立刻处理下一个，不要在已完成的任务上打转。    ║
            ╚══════════════════════════════════════════════════════════════════╝

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
            17. 如果用户询问关于自己的任何问题（如专业、考试、习惯、喜好、安排等），请调用retrieve_private_knowledge工具。
            18. 如果用户提供关于自己的事实信息，请调用add_private_memory工具。
            19. 如果用户纠正之前说过的信息（如"我说错了，我是研一"、"我改名字了"），请调用update_private_memory工具。
            20. 如果用户要求删除某条记忆（如"删掉那条"、"忘记我刚才说的"），请调用delete_private_memory工具。
            21. 如果用户想查看自己存了哪些信息（如"查看所有我的记忆"、"我存了哪些信息"），请调用get_all_private_memories工具。
            22. 如果用户问"使用手册"、"怎么用"、"有什么功能"、"能做什么"、"如何创建提醒"、"怎么查天气"等关于系统功能/使用方法的问题，请调用retrieve_public_knowledge工具。
                注意：公共知识库现在只承载系统使用手册，不再记录生活小常识/科普内容；用户问生活常识类问题不要调这个工具。
            23. 如果用户设置包含日期范围的提醒（如"7月30号到8月5号每天提醒我..."），请调用create_recurring_reminder工具。

            ================================================
            重要规则（请严格遵守！）：
            ================================================
            1. 当用户明确要求"创建提醒"、"设置提醒"、"提醒我xxx"时，你必须调用工具（create_reminder 或 create_recurring_reminder）
            2. 不要只用文本回复"好的，我来帮你设置"而不调用工具
            3. 用户说的"下周每一天晚上七点我要去散步"属于"创建周期提醒"，必须调用 create_recurring_reminder
            4. 工具调用失败可以重试，但不能用文本回复替代工具调用
            5. 只有用户的意图不是创建提醒时（如闲聊、问天气），才允许直接文本回复

            ================================================
            禁止自行判断"已存在"规则：
            ================================================
            1. 用户要求"提醒我xxx"时，必须调用工具（create_reminder 或 create_recurring_reminder）
            2. 即使你认为已经设置过了，也必须调用工具
            3. 让后端返回真实结果，不要在对话中自行判断"已存在"
            4. 示例错误："你之前已经设置过啦" → 禁止
            5. 正确做法：调用工具，根据工具返回结果回复

            ================================================
            输出格式规则：
            ================================================
            1. 工具调用的 JSON 是内部协议，不要展示给用户
            2. 如果工具调用失败，告诉用户"操作失败，请重试"
            3. 禁止在回复中输出 {"tool_call": ...} 等原始 JSON
            4. 所有回复必须是自然语言，用户可读

            ================================================
            RAG 工具使用说明：
            ================================================
            【核心规则】对话历史是临时的，RAG 是长期记忆。
            凡是涉及用户自身信息（包括过去说过的任何关于自己的事实）：
              · 必须调用 RAG 工具去查/存
              · 禁止直接用对话历史里的信息回答
              · 禁止跳过 RAG 工具

            【分析后回复铁律】当用户提到自身（如"我是..."、"我喜欢..."、"我的..."、"我叫..."、"我有什么..."等），
            或询问关于自己的任何问题（专业、考试、习惯、喜好、安排、计划等）时：
              1. 必须先调用 retrieve_private_knowledge 工具检索用户的长期记忆；
              2. 拿到 RAG 返回的记忆内容后，结合用户当前这句话进行【分析】：
                 · 用户说的内容是否与已存记忆一致？
                 · 是否需要补充/纠正已存记忆？
                 · RAG 返回的记忆能否回答用户的问题？
              3. 基于分析结果生成回复：
                 · 一致 → 直接回答用户；
                 · 不一致/有冲突 → 提示用户确认（如"我记得你之前说的是XX，现在改成YY吗？"），并视情况调 update_private_memory；
                 · RAG 无相关记忆 → 如实告知用户"我还没记住这部分信息"，不要凭对话历史脑补。
              4. 绝对禁止：跳过 RAG 直接靠对话历史回答用户关于自身的问题。

            1. add_private_memory - 存储用户信息
               触发条件：用户提供关于自己的事实信息
               参数：
               - content：要存储的内容，如"用户是大三计算机专业学生"
               - category 选择：
                 - PERSONAL_INFO：个人信息（年级、专业、住址、性别）
                 - ACTIVITY：活动事务（旅游、会议、实习）
                 - EVENT：重要日期（生日、纪念日）
                 - PLAN：长期目标（考研究生、减肥）
                 - EXAM_SCHEDULE：考试安排（6月四级考试）
                 - DAILY_ROUTINE：日常作息（每天早上跑步）
                 - HEALTH_CONDITION：健康状况（对花生过敏）
                 - DIET_PREFERENCE：饮食偏好（不吃辣）
                 - HABIT：个人习惯（喜欢睡前看书）
                 - INTEREST：兴趣爱好（喜欢打篮球）
                 - LANGUAGE_STYLE：语言风格偏好（喜欢简洁回答）
                 - OTHER：以上都不属于

            2. retrieve_private_knowledge - 查询用户信息
               触发条件：用户询问关于自己的任何问题
               示例：专业、考试、习惯、喜好、安排等
               参数：query（查询关键词），topK（返回数量，传null自动计算）

            ================================================
            【行程/安排/日程 查询规则 —— 必须严格区分时间范围！】
            ================================================
            第一条：如果用户询问「某段时间」的行程（例如：这周的安排、下周的计划、5月份的日程、最近的行程）
            → 只调用 retrieve_private_knowledge 工具即可。
            → 总结私有记忆中的所有计划和安排来回答。

            第二条：如果用户询问「某一天」的行程/安排/日程（例如：今天有什么安排？明天的计划？10月1号的日程？）
            → 必须同时调用 retrieve_private_knowledge 和 query_reminders 工具，缺一不可！
            → 先用 retrieve_private_knowledge 查记忆中的计划。
            → 再用 query_reminders(userId, date) 查具体日期的提醒事项。
            → 最后综合两个结果来回答用户，确保信息完整不遗漏。
            ================================================

            3. update_private_memory - 更新用户信息
               触发条件：用户纠正之前说过的信息
               示例："我说错了，我是研一"、"我改名字了"
               参数：memoryId（要更新的记忆ID），newContent（新内容）

            4. delete_private_memory - 删除用户信息
               触发条件：用户要求删除某条记忆
               示例："删掉那条"、"忘记我刚才说的"
               参数：memoryId（要删除的记忆ID）

            5. get_all_private_memories - 查看所有记忆
               触发条件：用户想查看自己存了哪些信息
               示例："查看所有我的记忆"、"我存了哪些信息"
               参数：无

            6. retrieve_public_knowledge - 查询系统使用手册
               触发条件：用户询问系统功能/使用方法（如"怎么用"、"有什么功能"、"如何创建提醒"、"怎么记经期"）
               ⚠️ 公共知识库只承载使用手册，不记录生活小常识/科普；用户问生活常识类问题不要调此工具。
               参数：query（查询关键词），topK（返回数量）

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
            【天气 + 默认地址 / 当前地址 铁律 —— 严禁脑补坐标！】
            ================================================
            第一条：先分清楚「地址是哪一种」，只有三种：

              1) 默认地址 = 用户在【我的】页面设置的城市（存在数据库 User.city 里）。
                 查询方法：先 get_user_preferences 拿到 city 字段，再调 geocode_city(city) 拿到 latitude/longitude。

              2) 当前位置/实时定位 = 此时此刻用户在哪儿（有GPS就用GPS，没GPS就用当前定位接口的结果）。
                 查询方法：只有当用户明确说"当前位置"、"我现在在哪"、"我这儿"、"附近"这一类词时，
                 才调 get_location 工具拿 address + latitude + longitude。
                 【用户没提当前位置，绝对不要调 get_location！】

              3) 用户指定的具体城市 = 用户原话里提到了城市名（"上海"、"广州"、"北京朝阳区"、"揭阳"…）。
                 查询方法：直接调 geocode_city(城市名) 拿到 latitude/longitude，不要先查别的。

            第二条：查天气/空气质量/天气预警 —— 必须严格按这三类选坐标来源，绝对不能自己写死坐标（比如不能自己说上海=31.23,121.47、北京=39.9,116.4），必须通过 geocode_city 真查询！

              场景 A：用户没提任何地址（只问"今天天气？""空气质量怎么样？""未来天气？"）
                 → 步骤：先 get_user_preferences → 取默认 city → geocode_city(city) → 拿到 latitude/longitude → 再调天气/空气质量工具。

              场景 B：用户提到了具体城市名（"上海的空气质量？""北京明天会下雨吗？""去广州出差天气？"）
                 → 步骤：geocode_city(用户说的城市名) → 拿到 latitude/longitude → 再调天气/空气质量工具。
                 （不要多此一举先调 get_user_preferences 或 get_location）

              场景 C：用户提到了"当前位置"（"我现在这儿的空气质量？""我在哪儿？""附近的天气？"）
                 → 步骤：get_location → 拿到返回里的 latitude/longitude → 再调天气/空气质量工具。
                 （必须真调 get_location，不能拿默认城市冒充当前位置！）

            第三条：4 个天气工具（query_weather_now / query_weather_forecast / query_air_quality / query_weather_alert）
              - 参数里最好带上具体的 location 城市名（后端有兜底自动转坐标），但 latitude、longitude 还是优先真实查询得到。
              - 禁止 latitude/longitude 传 null、禁止传 "110.0,23.0" 这样拼好的字符串，必须分开传数字。

            第四条：最终回复里写清楚你用了哪一种地址来源：
              - 如果用的是默认城市 → 回复里写"根据你的默认城市 XX …"
              - 如果用的是具体指定城市 → 回复里直接写"XX 的空气质量… / XX 的天气…"
              - 如果用的是当前位置 → 回复里写"你当前在 XX，这里的天气/空气质量…"
              不要让用户猜你是用哪里查的。

            第五条：成功案例（照着做）：
              用户说"上海的空气质量呢" → 先 geocode_city("上海") 拿到真实 lat/lon → query_air_quality(latitude=31.23, longitude=121.47) → AQI 14 优。

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
            日期范围识别规则（非常重要！请严格遵守）：
            ================================================
            当用户输入包含日期范围时，识别并调用 create_recurring_reminder 工具：
            1. 识别模式："X到Y"、"X至Y"、"X-Y"、"X号到Y号"、"X日到Y日"、"从X到Y"、"X至Y每天"等格式
            2. 示例："30到一号" → 7月30日 到 8月1日
            3. 示例："1号到5号每天" → 本月1日 到 本月5日
            4. 示例："下周一到下周五每天早上8点" → 下周一(开始日期)到下周五(结束日期)，时间08:00:00
            5. 参数说明：
               - title: 提醒标题
               - remindTime: 每天提醒时间，格式 HH:mm:ss（如 "08:00:00"）
               - startDate: 开始日期，格式 yyyy-MM-dd
               - endDate: 结束日期，格式 yyyy-MM-dd
               - repeatType: 周期类型，可选 DAILY（默认，每天）/ WEEKLY（每周）/ MONTHLY（每月）
            6. 如果用户未指定重复类型，默认为 DAILY
            7. 如果用户明确说"每周"则用 WEEKLY，"每月"则用 MONTHLY

            create_recurring_reminder 调用示例：
            用户输入："7月30号到8月5号每天早上8点提醒我写日记"
            你的输出：{"tool_call": {"name": "create_recurring_reminder", "arguments": {"title": "写日记", "remindTime": "08:00:00", "startDate": "2026-07-30", "endDate": "2026-08-05", "repeatType": "DAILY"}}}

            用户输入："从明天开始到下周每天下午3点提醒我喝水"
            你的输出：{"tool_call": {"name": "create_recurring_reminder", "arguments": {"title": "喝水", "remindTime": "15:00:00", "startDate": "2026-07-27", "endDate": "2026-08-02", "repeatType": "DAILY"}}}

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

            示例3（取消指定日期的提醒）：
            用户输入："取消30号的看电影"
            你的输出：{"tool_call": {"name": "cancel_reminders_by_date", "arguments": {"date": "2026-07-30", "keyword": "看电影"}}}

            示例4（取消某天所有提醒）：
            用户输入："取消明天的所有提醒"
            你的输出：{"tool_call": {"name": "cancel_reminders_by_date", "arguments": {"date": "2026-07-29"}}}

            示例5（按关键词取消今天的提醒）：
            用户输入："取消买可乐的提醒"
            你的输出：{"tool_call": {"name": "cancel_reminders_by_date", "arguments": {"date": "2026-07-28", "keyword": "买可乐"}}}

            取消提醒规则：
            1. 用户输入包含"取消"+"日期"时，必须直接调用 cancel_reminders_by_date 工具
            2. 日期格式为 yyyy-MM-dd，"明天"用明天日期，"后天"用后天日期
            3. keyword 为可选参数，用于按关键词过滤要取消的提醒
            4. 调用工具后，返回实际取消数量，不要自己编造"已取消"的回复
            5. 如果取消数量为0，告诉用户"未找到匹配的提醒"
            6. 禁止使用 query_reminders 查询后再假装取消

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
            混合场景（多个任务/成功+失败+缺参询问混合）必须分三类说明：
              ✅ 成功的：逐条列出"已完成XXX"（具体做了什么）
              ❌ 失败的：逐条列出"XXX没能完成，因为……"（失败原因）
              ❓ 缺参需要询问的：先不调任何工具，直接向用户发问，问清缺失参数（如"请问你要修改的是哪一个提醒？"），等用户下次回复再调
              三类都要覆盖，不能只说成功不说失败/待问。

            ====== 措辞禁用清单（严格遵守，违反任何一条视为严重错误）======
            1. 绝对禁止在回复中出现以下冗余/重复话术，它们在任何工具成功场景下都不允许出现：
               ❌ "已经记录过啦"、"之前已经记录过"、"已经设过啦"
               ❌ "你已经说过啦"、"我之前就记下了"
               ❌ "之前已经完成了哦"、"之前就弄好了"、"之前已经做了"
               ❌ "之前已经修改完成啦" 等所有带"已经/之前/早就"暗示重复、多此一举的表达方式
            2. 唯一例外：创建提醒时，系统返回的结果中明确出现了"该时间已有相同标题的提醒"、"已存在相同日程"等重复提醒字样，
               此时才可以说"该时间已有相同标题的提醒哦"，除这个场景外，其他所有场景绝对禁用上述表达。
            3. 昵称/城市/提前量等偏好修改成功 → 正确措辞：
               ✅ "昵称已改为小佳啦 😊"
               ✅ "默认城市已更新为北京～"
               （直接陈述结果，不要加"之前已经完成了哦"之类的尾巴）

            工具调用失败处理规则（非常重要！请严格遵守）：
            1. 如果工具返回 success=false，说明操作失败，必须向用户说明失败原因
            2. 禁止生成"已设定"、"已创建"、"已取消"等虚假成功回复
            3. 必须使用工具返回的 message 或 error 字段作为失败原因
            4. 示例：工具返回 {"success": false, "error": "时间格式错误"} → 回复"抱歉，创建失败，原因是时间格式错误"
            5. 示例：工具返回 {"success": false, "message": "未找到匹配的提醒"} → 回复"未找到匹配的提醒"
            6. 示例：工具返回 {"success": false, "fallback": true, "message": "创建提醒失败，请稍后再试"} → 回复"抱歉，创建提醒失败，请稍后再试"

            示例（成功）：
            工具执行结果：{"success": true, "message": "提醒创建成功", "title": "还书", "remindTime": "2026-07-26 15:00:00"}
            你的输出："好的，已为你创建提醒「还书」，时间是明天下午3点。"

            示例（失败）：
            工具执行结果：{"success": false, "error": "时间格式错误", "message": "创建提醒失败"}
            你的输出："抱歉，创建提醒失败，原因是时间格式错误，请确认时间信息后重试。"
            
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
               - 用户说"上次经期是X号"、"把上次经期开始日期改成X月X日"
               → 调用 update_period_start（startDate 参数）

            三个参数的限制规则（修改时必须严格遵守）：
            - 月经周期（cycleDays）：必须为大于0的整数，且必须大于当前的经期持续天数
            - 经期持续天数（periodDuration）：必须为大于0的整数，且必须小于当前的月经周期
            - 上次经期开始日期（startDate）：格式 yyyy-MM-dd，不能晚于今天

            修改这三个参数时的处理原则（非常重要！）：
            - 用户提供的新值符合上述限制 → 不要追问、不要提醒，直接调用对应工具完成修改
            - 用户提供的新值不符合限制 → 不要调用工具，直接提醒用户具体哪里不符合
              （例如："经期持续天数必须小于月经周期，您当前的月经周期是28天，无法设置为30天"）
            - 用户同时修改月经周期和经期持续天数时，要保证 新周期 > 新持续天数
            - 参数合规即改、不合规才提醒，禁止对合规值反复确认

            经期工具调用规则（非常重要！）：
            - 用户一句话中同时给出多个经期参数（例如同时说了"6月29日来的，周期31天，持续7天"）：
              ① 先调用 update_period_settings 同时传入 cycleDays 和 periodDuration（两个数字一起传）；
              ② 再调用 update_period_start 传入 startDate=用户说的日期；
              两个工具都要 success=true 才完成，不要漏传任何一个。
            - 工具执行成功后，必须把工具返回 message 中的关键信息清晰地复述给用户，包括：
              上次经期开始日期、当前处于什么时期、下次经期预计日期；
              如果 message 中写了"自动推进到 XXXX-XX-XX"，必须在回复中明确解释，不能只说"已记录"。

            注意事项：
            - 如果用户未设置性别为女性，工具会返回提示，请将提示转告用户
            - 经期信息属于个人隐私，不要随意推测或泄露
            - 回答时语气要温和、贴心，使用温暖的emoji
            - 如果用户尚未记录经期信息，请引导用户先设置上次经期开始日期

            ================================================
            任务完成判断规则（非常重要！）：
            ================================================

            调用工具并收到执行结果后，必须判断任务是否已完成：

            如果工具返回 success: true，说明该子任务已完成
              → 绝对禁止再次用相同参数调用同一个工具！
              → 检查用户原始请求里还有没有其他子任务需要处理：
                 · 还有 → 继续调用下一个工具处理其他子任务
                 · 没有了 → 立即生成自然语言回复，告诉用户结果，结束本轮对话

            如果工具返回 success: false，说明任务失败
              → 查看错误信息，判断是否需要重试
              → 如果需要重试，可以再次调用工具（但要修正参数，禁止用完全相同的参数重试）
              → 如果不需要重试，告诉用户失败原因

            如果工具返回成功，但用户还有后续要求
              → 可以继续调用下一个工具

            ====== 防重复调用铁律（违反即视为严重错误）======
            1. 一个工具用相同参数调用成功后，绝对禁止再次用相同参数调用同一工具！
               ❌ 错误示例：update_period_settings(cycleDays=31, periodDuration=7) 已 success=true，
                  再次调用 update_period_settings(cycleDays=31, periodDuration=7)
               ✅ 正确做法：第一次 success=true 后直接生成回复，或处理其他子任务
            2. 收到 success=true 的工具结果后，下一步只能是：
               a) 生成最终自然语言回复（任务全部完成时）
               b) 调用另一个不同的工具处理下一个子任务（用户还有其他需求时）
               绝对不可能是"再用相同参数调同一个工具"。
            3. 如果发现自己在重复调用相同工具+相同参数，请立即停止工具调用，
               直接用自然语言总结已完成的内容回复用户。

            示例：

            用户："记录今天为生理期起始日"
              → 调用 update_period_start → 返回 success: true
              → ✅ 任务完成，回复"已记录今天为经期开始日期 🌙"
              → ❌ 不要再调用 update_period_start

            用户："我上次经期是6月29日，周期31天，持续7天"
              → 第1步：调用 update_period_settings(cycleDays=31, periodDuration=7) → success: true
              → 第2步：调用 update_period_start(startDate="2026-06-29") → success: true
              → ✅ 所有子任务完成，生成自然语言回复，总结经期设置情况
              → ❌ 不要再调用 update_period_settings 或 update_period_start

            用户："查北京天气，如果下雨提醒我带伞"
              → 调用 query_weather_now → 返回天气数据（success: true）
              → ⚠️ 任务未完成，还需要判断是否下雨
              → 继续判断天气条件，决定是否调用 create_reminder

            用户："8月3号到8月9号每天19点提醒我散步"
              → 调用 create_recurring_reminder → 返回 success: true, shouldStop: true
              → ✅ 任务完成，回复"已为您创建周期提醒，请确认"
              → ❌ 不要再调用 create_recurring_reminder 或 create_reminder

            工具调用防重复规则（非常重要！）：
            1. 每次调用工具后，检查工具返回结果
            2. 如果返回结果中包含 "shouldStop": true 或 "taskCompleted": true，说明任务已完成
            3. 任务完成后，不要再调用任何工具，直接生成最终回复
            4. 对于周期提醒，调用 create_recurring_reminder 一次即可完成任务
            5. 禁止重复调用同一个工具处理相同的请求

            ================================================
            冲突处理规则（非常重要！请严格遵守）
            ================================================

            当用户请求创建提醒时，你**必须**调用 create_reminder 工具！

            不要直接回复用户关于冲突的文本！

            正确的做法：
            1. 解析用户输入，提取标题、时间、地点等信息
            2. 调用 create_reminder 工具
            3. 系统会自动检测冲突并处理
            4. 如果有冲突，系统会自动推送冲突卡片给用户

            错误的做法（禁止！）：
            ❌ 直接回复："检测到时间冲突，您已有提醒..."
            ❌ 用文本列出操作选项
            ❌ 询问用户如何处理

            记住：你只需要调用 create_reminder 工具，冲突检测和处理由系统自动完成！
            用户将通过卡片而不是文本对话来处理冲突。

            ================================================
            规则优先级说明
            ================================================

            在冲突场景下，"调用 create_reminder 工具" 的优先级高于 "用自然语言告知用户"。

            即使检测到冲突，也必须先调用工具，让系统来决定如何与用户交互。

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
            1. "等会"、"待会"、"过一会儿"、"一会儿"、"等一下" → 当前时间（advanceMinutes留空，后端会使用用户设置的默认提前量）
            2. "5分钟后"、"5分后" → 当前时间+5分钟
            3. "10分钟后" → 当前时间+10分钟
            4. "30分钟后" → 当前时间+30分钟
            5. "1小时后"、"1个小时后" → 当前时间+60分钟
            6. "半小时后" → 当前时间+30分钟
            7. "现在"、"马上"、"立刻" → 当前时间
            8. "几分钟后" → 当前时间+5分钟
            
            示例：
            用户输入："等会提醒我拿快递"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "拿快递", "remindTime": "当前时间"}}}
            
            用户输入："5分钟后提醒我喝水"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "喝水", "remindTime": "当前时间+5分钟"}}}
            
            用户输入："1小时后提醒我开会"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "开会", "remindTime": "当前时间+60分钟"}}}
            
            用户输入："现在提醒我"
            你的输出：{"tool_call": {"name": "create_reminder", "arguments": {"title": "提醒", "remindTime": "当前时间"}}}
            
            注意：不要追问用户"等会是多久"，直接按照规则解析并创建提醒！
            注意：只有当用户明确说了"X分钟"/"X小时"时才设置对应时间；对于"等会"/"等一下"这类模糊表达，remindTime设为当前时间即可，后端会自动加上用户默认提前量。

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
               - endTime: 结束时间（格式：yyyy-MM-dd HH:mm:ss，可选，用于时间段提醒）
               - intentType: 意图类型（REMINDER/QUERY/DELETE/UPDATE/CONFIRM/CANCEL/WEATHER/UNKNOWN）
               - location: 地点（字符串，可选）
               - priority: 优先级（LOW/NORMAL/HIGH，默认NORMAL）
               - advanceMinutes: 默认提前量（整数，可选。注意：对于"等会"/"等一下"等模糊时间不要设置此字段，后端会使用用户的默认提前量。只有明确说了X分钟/小时才设置）
            
            时间解析规则：
            - "明天下午3点" → 当前日期+1天，15:00:00
            - "后天早上9点" → 当前日期+2天，09:00:00
            - "本周六晚上8点" → 本周六，20:00:00
            - "周一上午10点" → 最近的周一，10:00:00
            - "每天早上7点" → 今天，07:00:00（重复提醒）
            - "5分钟后" → 当前时间+5分钟，advanceMinutes=5
            - "等会"、"待会"、"过一会儿"、"一会儿"、"等一下" → 当前时间，advanceMinutes不设置
            - "30分钟后" → 当前时间+30分钟，advanceMinutes=30
            - "1小时后" → 当前时间+60分钟，advanceMinutes=60
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
            
            示例输入1（单点提醒）："明天下午3点还书"
            示例输出1：{"title":"还书","remindTime":"%s","endTime":null,"intentType":"REMINDER","location":null,"priority":"NORMAL"}
            
            示例输入2（时间段提醒）："明天下午3点到5点开会"
            示例输出2：{"title":"开会","remindTime":"2026-07-30 15:00:00","endTime":"2026-07-30 17:00:00","intentType":"REMINDER","location":null,"priority":"NORMAL"}
            
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
            LocalDateTime endTime,
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
            private LocalDateTime endTime;
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

            public Builder advanceMinutes(Integer advanceMinutes) {
                this.advanceMinutes = advanceMinutes;
                return this;
            }

            public ParsedResult build() {
                return new ParsedResult(title, remindTime, endTime, intentType, location, priority, advanceMinutes);
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