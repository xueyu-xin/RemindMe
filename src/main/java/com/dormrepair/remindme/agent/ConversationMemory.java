package com.dormrepair.remindme.agent;

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
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话记忆管理类
 * <p>
 * 维护每个用户的对话历史，支持添加、获取和清空操作
 * 会话过期时间为30分钟，基于最后访问时间判断
 * </p>
 */
@Slf4j
@Component
public class ConversationMemory {

    /**
     * 会话过期时间（分钟）
     */
    private static final int EXPIRATION_MINUTES = 30;

    /**
     * 用户对话记忆存储：用户ID -> 记忆条目
     */
    private final Map<Long, MemoryEntry> memoryStore = new ConcurrentHashMap<>();

    /**
     * 记忆条目，包含对话历史和最后访问时间
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class MemoryEntry {
        List<Message> messages;
        LocalDateTime lastAccessTime;
    }

    /**
     * 消息类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /**
         * 角色：user, assistant, system, tool
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        /**
         * 工具调用信息（可选）
         */
        private ToolCall toolCall;

        /**
         * 工具执行结果（可选）
         */
        private ToolResult toolResult;
    }

    /**
     * 工具调用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String toolName;
        private Map<String, Object> arguments;
    }

    /**
     * 工具执行结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResult {
        private String toolName;
        private Object result;
        private boolean success;
    }

    /**
     * 添加用户消息
     *
     * @param userId 用户ID
     * @param content 消息内容
     */
    public void addUserMessage(Long userId, String content) {
        Message message = Message.builder()
                .role("user")
                .content(content)
                .build();
        addMessage(userId, message);
    }

    /**
     * 添加助手消息
     *
     * @param userId 用户ID
     * @param content 消息内容
     */
    public void addAssistantMessage(Long userId, String content) {
        Message message = Message.builder()
                .role("assistant")
                .content(content)
                .build();
        addMessage(userId, message);
    }

    /**
     * 添加系统消息
     *
     * @param userId 用户ID
     * @param content 消息内容
     */
    public void addSystemMessage(Long userId, String content) {
        Message message = Message.builder()
                .role("system")
                .content(content)
                .build();
        addMessage(userId, message);
    }

    /**
     * 添加工具调用消息
     *
     * @param userId 用户ID
     * @param toolName 工具名称
     * @param arguments 工具参数
     */
    public void addToolCall(Long userId, String toolName, Map<String, Object> arguments) {
        Message message = Message.builder()
                .role("tool")
                .toolCall(ToolCall.builder()
                        .toolName(toolName)
                        .arguments(arguments)
                        .build())
                .build();
        addMessage(userId, message);
    }

    /**
     * 添加工具执行结果消息
     *
     * @param userId 用户ID
     * @param toolName 工具名称
     * @param result 执行结果
     * @param success 是否成功
     */
    public void addToolResult(Long userId, String toolName, Object result, boolean success) {
        Message message = Message.builder()
                .role("tool")
                .toolResult(ToolResult.builder()
                        .toolName(toolName)
                        .result(result)
                        .success(success)
                        .build())
                .build();
        addMessage(userId, message);
    }

    /**
     * 添加消息到对话历史（带自动截断）
     */
    private void addMessage(Long userId, Message message) {
        MemoryEntry entry = memoryStore.compute(userId, (key, existing) -> {
            if (existing == null) {
                List<Message> messages = new ArrayList<>();
                messages.add(message);
                return MemoryEntry.builder()
                        .messages(messages)
                        .lastAccessTime(LocalDateTime.now())
                        .build();
            } else {
                existing.getMessages().add(message);
                existing.setLastAccessTime(LocalDateTime.now());
                return existing;
            }
        });
        
        // 添加消息后自动截断
        if (entry.getMessages().size() > DEFAULT_HISTORY_LIMIT) {
            trimHistory(userId, DEFAULT_HISTORY_LIMIT);
        }
        
        log.debug("添加消息: userId={}, role={}, messageCount={}", userId, message.getRole(), getMessageCount(userId));
    }

    /**
     * 获取用户的对话历史
     *
     * @param userId 用户ID
     * @return 消息列表（如果会话已过期则返回空列表）
     */
    public List<Message> getHistory(Long userId) {
        cleanExpiredSessions();

        MemoryEntry entry = memoryStore.get(userId);
        if (entry == null) {
            return new ArrayList<>();
        }

        // 更新最后访问时间
        entry.setLastAccessTime(LocalDateTime.now());
        return new ArrayList<>(entry.getMessages());
    }

    /**
     * 获取最近N条消息
     *
     * @param userId 用户ID
     * @param limit 消息数量限制
     * @return 消息列表
     */
    public List<Message> getRecentHistory(Long userId, int limit) {
        List<Message> history = getHistory(userId);
        if (history.size() <= limit) {
            return history;
        }
        return history.subList(history.size() - limit, history.size());
    }

    /**
     * 获取对话历史的消息数量
     *
     * @param userId 用户ID
     * @return 消息数量
     */
    public int getMessageCount(Long userId) {
        MemoryEntry entry = memoryStore.get(userId);
        return entry != null ? entry.getMessages().size() : 0;
    }

    /**
     * 清空用户的对话历史
     *
     * @param userId 用户ID
     */
    public void clear(Long userId) {
        memoryStore.remove(userId);
        log.debug("清空对话历史: userId={}", userId);
    }

    /**
     * 清空所有对话历史
     */
    public void clearAll() {
        memoryStore.clear();
        log.info("清空所有对话历史");
    }

    /**
     * 检查用户是否有对话历史
     *
     * @param userId 用户ID
     * @return 是否有对话历史
     */
    public boolean hasHistory(Long userId) {
        MemoryEntry entry = memoryStore.get(userId);
        return entry != null && !entry.getMessages().isEmpty();
    }

    /**
     * 清理过期会话
     * 过期时间：最后访问时间超过30分钟
     */
    private void cleanExpiredSessions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(EXPIRATION_MINUTES);
        
        memoryStore.entrySet().removeIf(entry -> {
            MemoryEntry memoryEntry = entry.getValue();
            boolean expired = memoryEntry.getLastAccessTime().isBefore(threshold);
            if (expired) {
                log.debug("清理过期会话: userId={}", entry.getKey());
            }
            return expired;
        });
    }

    /**
     * 获取当前存储的会话数量
     */
    public int getSessionCount() {
        cleanExpiredSessions();
        return memoryStore.size();
    }

    /**
     * 默认历史消息上限
     */
    private static final int DEFAULT_HISTORY_LIMIT = 10;

    /**
     * 获取符合LLM API格式的消息列表
     * <p>
     * 将自定义Message转换为Spring AI的Message类型，供LLM调用使用
     * </p>
     *
     * @param userId 用户ID
     * @return Spring AI消息列表
     */
    public List<org.springframework.ai.chat.messages.Message> getHistoryForLLM(Long userId) {
        List<org.springframework.ai.chat.messages.Message> llmMessages = new ArrayList<>();
        
        List<Message> history = getHistory(userId);
        if (history.isEmpty()) {
            return llmMessages;
        }

        for (Message message : history) {
            String role = message.getRole();
            String content = message.getContent();
            
            // 如果是工具消息，构建工具相关内容
            if ("tool".equals(role)) {
                if (message.getToolCall() != null) {
                    content = buildToolCallContent(message.getToolCall());
                } else if (message.getToolResult() != null) {
                    content = buildToolResultContent(message.getToolResult());
                }
            }

            // 转换为Spring AI消息类型
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
                    // 工具消息作为assistant消息的一部分
                    llmMessage = new AssistantMessage(content);
                    break;
                default:
                    llmMessage = new UserMessage(content);
            }
            
            llmMessages.add(llmMessage);
        }
        
        return llmMessages;
    }

    /**
     * 构建工具调用内容
     */
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

    /**
     * 构建工具执行结果内容
     */
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

    /**
     * 截断对话历史，保留最近N条消息
     *
     * @param userId 用户ID
     * @param limit 保留的消息数量
     */
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

    /**
     * 使用默认限制截断对话历史
     *
     * @param userId 用户ID
     */
    public void trimHistory(Long userId) {
        trimHistory(userId, DEFAULT_HISTORY_LIMIT);
    }
}