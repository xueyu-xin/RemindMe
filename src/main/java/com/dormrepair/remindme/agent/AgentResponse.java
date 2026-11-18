package com.dormrepair.remindme.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

// Agent 统一响应结构。封装纯文本、工具调用、确认卡片、错误四类输出，供 Controller 下发
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    // 响应类型：TEXT / TOOL_CALL / CONFIRMATION / ERROR
    private ResponseType type;

    // 面向用户的自然语言正文
    private String content;

    // type=TOOL_CALL 时：被调用的工具名
    private String toolName;

    // type=TOOL_CALL 时：工具参数 Map
    private Map<String, Object> toolArgs;

    // type=CONFIRMATION 时：是否需要用户二次确认
    private boolean needConfirmation;

    // type=CONFIRMATION 时：确认会话 ID，与 ConfirmationSession 对应
    private String sessionId;

    // type=CONFIRMATION 时：确认卡片所需结构化数据（title/remindTime/location/endTime 等）
    private Map<String, Object> confirmationData;

    // type=TEXT 时：附带的工具执行结果（供展示或调试用）
    private Object toolResult;

    // type=EXECUTION_RESULT 时：已创建的提醒 ID
    private Long reminderId;

    // 执行成功标记，失败时与 errorMessage 配套
    @Builder.Default
    private boolean success = true;

    // success=false 时：错误描述
    private String errorMessage;

    // 自然语言引导语：在卡片显示前展示的 LLM 生成提示
    private String leadMessage;

    // 响应状态：idle/processing/card_shown/done
    @Builder.Default
    private ResponseStatus status = ResponseStatus.IDLE;

    // 是否为临时状态消息（前端可据此清理）
    @Builder.Default
    private boolean isTemp = false;

    // 要替换的消息标识（前端据此更新已有消息而不是追加）
    private String replaceTarget;

    // 响应状态枚举
    public enum ResponseStatus {
        IDLE,
        PROCESSING,
        CARD_SHOWN,
        DONE
    }

    // 响应类型枚举
    public enum ResponseType {
        // 普通自然语言回复
        TEXT,
        // 要求执行一次工具调用
        TOOL_CALL,
        // 返回确认卡片，等待用户确认/取消
        CONFIRMATION,
        // 工具直接执行完成（如提醒已创建），展示执行结果卡片
        EXECUTION_RESULT,
        // 处理异常，走错误分支
        ERROR
    }

    // 快速构造文本响应
    public static AgentResponse text(String content) {
        return AgentResponse.builder()
                .type(ResponseType.TEXT)
                .content(content)
                .success(true)
                .build();
    }

    // 快速构造工具调用响应
    public static AgentResponse toolCall(String toolName, Map<String, Object> toolArgs) {
        return AgentResponse.builder()
                .type(ResponseType.TOOL_CALL)
                .toolName(toolName)
                .toolArgs(toolArgs)
                .success(true)
                .build();
    }

    // 快速构造确认响应
    public static AgentResponse confirmation(String content) {
        return AgentResponse.builder()
                .type(ResponseType.CONFIRMATION)
                .content(content)
                .leadMessage(content)
                .needConfirmation(true)
                .status(ResponseStatus.CARD_SHOWN)
                .success(true)
                .build();
    }

    // 快速构造确认响应（带 sessionId 与卡片数据）
    public static AgentResponse confirmation(String sessionId, String content, Map<String, Object> confirmationData) {
        return AgentResponse.builder()
                .type(ResponseType.CONFIRMATION)
                .content(content)
                .leadMessage(content)
                .needConfirmation(true)
                .sessionId(sessionId)
                .confirmationData(confirmationData)
                .status(ResponseStatus.CARD_SHOWN)
                .success(true)
                .build();
    }

    // 快速构造执行结果响应（提醒已直接创建，无需用户确认）
    public static AgentResponse executionResult(Long reminderId, String content, Map<String, Object> confirmationData) {
        return AgentResponse.builder()
                .type(ResponseType.EXECUTION_RESULT)
                .content(content)
                .leadMessage(content)
                .reminderId(reminderId)
                .confirmationData(confirmationData)
                .needConfirmation(false)
                .status(ResponseStatus.DONE)
                .success(true)
                .build();
    }

    // 快速构造错误响应
    public static AgentResponse error(String errorMessage) {
        return AgentResponse.builder()
                .type(ResponseType.ERROR)
                .errorMessage(errorMessage)
                .success(false)
                .build();
    }
}
