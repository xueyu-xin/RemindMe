package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Agent响应类
 * <p>
 * 统一封装Agent的响应结果
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    /**
     * 响应类型
     */
    private ResponseType type;

    /**
     * 响应内容（文本）
     */
    private String content;

    /**
     * 工具名称（当type为tool_call时）
     */
    private String toolName;

    /**
     * 工具参数（当type为tool_call时）
     */
    private Map<String, Object> toolArgs;

    /**
     * 是否需要确认（当type为confirmation时）
     */
    private boolean needConfirmation;

    /**
     * 确认会话ID（当type为confirmation时）
     */
    private String sessionId;

    /**
     * 确认数据（当type为confirmation时，包含title、remindTime、location等）
     */
    private Map<String, Object> confirmationData;

    /**
     * 工具执行结果（当type为text且包含工具执行结果时）
     */
    private Object toolResult;

    /**
     * 是否成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 错误信息（当success为false时）
     */
    private String errorMessage;

    /**
     * 响应类型枚举
     */
    public enum ResponseType {
        /**
         * 纯文本响应
         */
        TEXT,

        /**
         * 工具调用
         */
        TOOL_CALL,

        /**
         * 需要用户确认
         */
        CONFIRMATION,

        /**
         * 错误响应
         */
        ERROR
    }

    /**
     * 创建文本响应
     */
    public static AgentResponse text(String content) {
        return AgentResponse.builder()
                .type(ResponseType.TEXT)
                .content(content)
                .success(true)
                .build();
    }

    /**
     * 创建工具调用响应
     */
    public static AgentResponse toolCall(String toolName, Map<String, Object> toolArgs) {
        return AgentResponse.builder()
                .type(ResponseType.TOOL_CALL)
                .toolName(toolName)
                .toolArgs(toolArgs)
                .success(true)
                .build();
    }

    /**
     * 创建确认响应
     */
    public static AgentResponse confirmation(String content) {
        return AgentResponse.builder()
                .type(ResponseType.CONFIRMATION)
                .content(content)
                .needConfirmation(true)
                .success(true)
                .build();
    }

    /**
     * 创建确认响应（带sessionId和确认数据）
     */
    public static AgentResponse confirmation(String sessionId, String content, Map<String, Object> confirmationData) {
        return AgentResponse.builder()
                .type(ResponseType.CONFIRMATION)
                .content(content)
                .needConfirmation(true)
                .sessionId(sessionId)
                .confirmationData(confirmationData)
                .success(true)
                .build();
    }

    /**
     * 创建错误响应
     */
    public static AgentResponse error(String errorMessage) {
        return AgentResponse.builder()
                .type(ResponseType.ERROR)
                .errorMessage(errorMessage)
                .success(false)
                .build();
    }
}