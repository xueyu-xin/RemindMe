package com.dormrepair.remindme.agent;

import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 对话历史内存存储。按用户维度承载消息、30 分钟过期自动清理；超过 N 条按"首条用户请求+最近 N-1 条"策略裁剪，并可转换为 Spring AI Message 供 LLM 消费
@Slf4j
@Component
public class ConversationMemory {

    // 会话过期阈值：距最后访问超过 30 分钟即被 cleanExpiredSessions 移除
    private static final int EXPIRATION_MINUTES = 30;

    // ===== P1-3：历史上限改为配置注入，对齐 application.yml 中 agent.max-history =====
    // 历史上限（配置注入）：application.yml agent.max-history=10，缺省默认值 10。
    // 注：旧版本 ConversationMemory 硬编码 DEFAULT_HISTORY_LIMIT=20，与 yml=10 不一致，已统一改为走配置。
    @Value("${agent.max-history:10}")
    private int historyLimit;

    // 兜底常量：若 historyLimit 被错误注入为 <=0（或单元测试直接 new ConversationMemory()），回退到 10。
    private static final int FALLBACK_HISTORY_LIMIT = 10;

    // 历史上限：首条用户请求 + 最近 N-1 条。优先取配置注入值，异常情况回退兜底。
    private int getHistoryLimit() {
        return historyLimit > 0 ? historyLimit : FALLBACK_HISTORY_LIMIT;
    }

    // 存储容器：userId → MemoryEntry（消息列表 + 最后访问时间），线程安全 ConcurrentHashMap
    private final Map<Long, MemoryEntry> memoryStore = new ConcurrentHashMap<>();

    // 单用户历史条目：消息列表 + 过期判断依据
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class MemoryEntry {
        List<Message> messages;
        LocalDateTime lastAccessTime;
    }

    // 通用消息结构。role 支持 user/assistant/system/tool；tool 时可携带 toolCall 或 toolResult
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        // 角色：user / assistant / system / tool
        private String role;
        // 正文内容；tool 消息可能为工具调用 JSON 或工具返回 JSON
        private String content;
        // role=tool 且为工具调用侧时：填充被调用的工具与参数
        private ToolCall toolCall;
        // role=tool 且为工具返回侧时：填充执行结果与 success 标记
        private ToolResult toolResult;
    }

    // 工具调用的最小描述：名 + 参数
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        // 被调用的工具名称
        private String toolName;
        // 调用参数键值对
        private Map<String, Object> arguments;
    }

    // 工具执行结果：工具名 + 返回对象 + 成功/失败
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResult {
        // 执行完成的工具名称
        private String toolName;
        // 工具返回的实际结果（字符串或对象）
        private Object result;
        // 执行是否成功：true=成功 false=失败
        private boolean success;
    }

    // ---------- 便捷追加方法 ----------

    // 追加用户输入消息到指定用户的会话历史
    public void addUserMessage(Long userId, String content) {
        Message message = Message.builder().role("user").content(content).build();
        addMessage(userId, message);
    }

    // 追加助手回复消息到指定用户的会话历史
    public void addAssistantMessage(Long userId, String content) {
        Message message = Message.builder().role("assistant").content(content).build();
        addMessage(userId, message);
    }

    // 追加系统提示消息到指定用户的会话历史
    public void addSystemMessage(Long userId, String content) {
        Message message = Message.builder().role("system").content(content).build();
        addMessage(userId, message);
    }

    // 追加一条"tool 角色"的工具调用记录
    public void addToolCall(Long userId, String toolName, Map<String, Object> arguments) {
        Message message = Message.builder()
                .role("tool")
                .toolCall(ToolCall.builder().toolName(toolName).arguments(arguments).build())
                .build();
        addMessage(userId, message);
    }

    // 追加一条"tool 角色"的工具执行结果记录（工具侧返回）
    public void addToolResult(Long userId, String toolName, Object result, boolean success) {
        Message message = Message.builder()
                .role("tool")
                .toolResult(ToolResult.builder().toolName(toolName).result(result).success(success).build())
                .build();
        addMessage(userId, message);
    }

    // ---------- 核心追加 + 裁剪 ----------

    // 追加消息到指定用户的历史；消息超过上限时触发 trimHistory；每次追加都会刷新 lastAccessTime
    private void addMessage(Long userId, Message message) {
        MemoryEntry entry = memoryStore.compute(userId, (key, existing) -> {
            if (existing == null) {
                List<Message> messages = new ArrayList<>();
                messages.add(message);
                return MemoryEntry.builder()
                        .messages(messages)
                        .lastAccessTime(TimeUtils.now())
                        .build();
            } else {
                existing.getMessages().add(message);
                existing.setLastAccessTime(TimeUtils.now());
                return existing;
            }
        });

        // 超过上限 → 按"首条用户请求 + 最近 N-1 条"策略裁剪（N 由 agent.max-history 配置）
        if (entry.getMessages().size() > getHistoryLimit()) {
            trimHistory(userId, getHistoryLimit());
        }

        log.debug("添加消息: userId={}, role={}, messageCount={}", userId, message.getRole(), getMessageCount(userId));
    }

    // ---------- 读取接口 ----------

    // 获取完整历史；每次读取前清理过期会话，读取后刷新 lastAccessTime
    public List<Message> getHistory(Long userId) {
        cleanExpiredSessions();

        MemoryEntry entry = memoryStore.get(userId);
        if (entry == null) {
            return new ArrayList<>();
        }

        entry.setLastAccessTime(TimeUtils.now());
        return new ArrayList<>(entry.getMessages());
    }

    // 取最近 limit 条；条数不足时返回完整历史
    public List<Message> getRecentHistory(Long userId, int limit) {
        List<Message> history = getHistory(userId);
        if (history.size() <= limit) {
            return history;
        }
        return history.subList(history.size() - limit, history.size());
    }

    // 返回消息条数，便于"本次对话起始下标"等场景计算
    public int getMessageCount(Long userId) {
        MemoryEntry entry = memoryStore.get(userId);
        return entry != null ? entry.getMessages().size() : 0;
    }

    // ---------- 清理接口 ----------

    // 清空指定用户的全部会话历史（用户主动重置时调用）
    public void clear(Long userId) {
        memoryStore.remove(userId);
        log.debug("清空对话历史: userId={}", userId);
    }

    // 清空系统内所有用户的会话历史（全局重置时调用）
    public void clearAll() {
        memoryStore.clear();
        log.info("清空所有对话历史");
    }

    // 判断指定用户是否存在已记录的会话历史
    public boolean hasHistory(Long userId) {
        MemoryEntry entry = memoryStore.get(userId);
        return entry != null && !entry.getMessages().isEmpty();
    }

    // 遍历 memoryStore 移除超过 EXPIRATION_MINUTES 没访问的会话；在 getHistory/统计方法中被动触发
    private void cleanExpiredSessions() {
        LocalDateTime threshold = TimeUtils.now().minusMinutes(EXPIRATION_MINUTES);

        memoryStore.entrySet().removeIf(entry -> {
            MemoryEntry memoryEntry = entry.getValue();
            boolean expired = memoryEntry.getLastAccessTime().isBefore(threshold);
            if (expired) {
                log.debug("清理过期会话: userId={}", entry.getKey());
            }
            return expired;
        });
    }

    // 返回存活会话数量（调用前会先清理过期的）
    public int getSessionCount() {
        cleanExpiredSessions();
        return memoryStore.size();
    }

    // ---------- Spring AI 消息适配 ----------

    // 将内部 Message 列表转为 Spring AI 的 Message 实现，用于 llmService.chat 调用
    // 工具消息：若有 toolCall 转为 tool_call JSON，若有 toolResult 转为 tool_result JSON，其它按 role 直接对应
    public List<org.springframework.ai.chat.messages.Message> getHistoryForLLM(Long userId) {
        List<org.springframework.ai.chat.messages.Message> llmMessages = new ArrayList<>();

        List<Message> history = getHistory(userId);
        if (history.isEmpty()) {
            return llmMessages;
        }

        for (Message message : history) {
            String role = message.getRole();
            String content = message.getContent();

            // tool 消息组装正文：toolCall JSON 或 toolResult JSON
            if ("tool".equals(role)) {
                if (message.getToolCall() != null) {
                    content = buildToolCallContent(message.getToolCall());
                } else if (message.getToolResult() != null) {
                    content = buildToolResultContent(message.getToolResult());
                }
            }

            // 按角色映射到 Spring AI 对应实现
            org.springframework.ai.chat.messages.Message llmMessage;
            switch (role) {
                case "user":
                    llmMessage = new UserMessage(content);
                    break;
                case "assistant":
                    llmMessage = new AssistantMessage(content);
                    break;
                case "system":
                    llmMessage = new SystemMessage(content);
                    break;
                case "tool":
                    // 工具消息按 assistant 归类，便于模型直接观察
                    llmMessage = new AssistantMessage(content);
                    break;
                default:
                    llmMessage = new UserMessage(content);
            }

            llmMessages.add(llmMessage);
        }

        return llmMessages;
    }

    // 工具调用 → JSON 字符串。ObjectMapper 失败则降级手写简化 JSON
    private String buildToolCallContent(ToolCall toolCall) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> toolCallMap = Map.of(
                    "tool_call", Map.of(
                            "name", toolCall.getToolName(),
                            "arguments", toolCall.getArguments()
                    )
            );
            return mapper.writeValueAsString(toolCallMap);
        } catch (JsonProcessingException e) {
            return "{\"tool_call\": {\"name\": \"" + toolCall.getToolName() + "\"}}";
        }
    }

    // 工具结果 → JSON 字符串
    private String buildToolResultContent(ToolResult toolResult) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> resultMap = Map.of(
                    "tool_result", Map.of(
                            "tool_name", toolResult.getToolName(),
                            "success", toolResult.isSuccess(),
                            "result", toolResult.getResult()
                    )
            );
            return mapper.writeValueAsString(resultMap);
        } catch (JsonProcessingException e) {
            return "{\"tool_result\": {\"tool_name\": \"" + toolResult.getToolName() + "\", \"success\": " + toolResult.isSuccess() + "}}";
        }
    }

    // ---------- 裁剪策略 ----------

    // 把用户历史裁剪到 limit 条；保留"末尾 limit 条"，首条用户原始请求在 N=agent.max-history 的策略下已自然处于末尾集合之前
    // （注：超过 N 即被裁剪，相当于丢弃中间较旧的消息，保留最新 N 条）
    public void trimHistory(Long userId, int limit) {
        MemoryEntry entry = memoryStore.get(userId);
        if (entry != null && entry.getMessages().size() > limit) {
            List<Message> messages = entry.getMessages();
            List<Message> trimmed = messages.subList(messages.size() - limit, messages.size());
            entry.setMessages(new ArrayList<>(trimmed));
            log.debug("截断对话历史: userId={}, 原数量={}, 截断后数量={}",
                    userId, messages.size() + limit, limit);
        }
    }

    // 使用配置注入的默认上限裁剪（agent.max-history，默认 10）
    public void trimHistory(Long userId) {
        trimHistory(userId, getHistoryLimit());
    }
}
