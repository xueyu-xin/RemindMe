package com.dormrepair.remindme.agent.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// 事件发布器
// 负责发布 Agent 相关事件，解耦组件之间的直接依赖
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    // ===== 提醒相关事件 =====

    // 发布提醒创建事件
    // @param userId              用户 ID
    // @param reminderId          提醒 ID
    // @param title               标题
    // @param remindTime          提醒时间
    // @param location            地点
    // @param confirmationRequired 是否需要确认
    public void publishReminderCreated(Long userId, Long reminderId, String title, String remindTime, String location, boolean confirmationRequired) {
        ReminderCreatedEvent event = new ReminderCreatedEvent(userId, reminderId, title, remindTime, location, confirmationRequired);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒创建事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }

    // 发布提醒确认事件
    // @param userId     用户 ID
    // @param reminderId 提醒 ID
    // @param title      标题
    public void publishReminderConfirmed(Long userId, Long reminderId, String title) {
        ReminderConfirmedEvent event = new ReminderConfirmedEvent(userId, reminderId, title);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒确认事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }

    // 发布提醒取消事件
    // @param userId     用户 ID
    // @param reminderId 提醒 ID
    // @param title      标题
    public void publishReminderCancelled(Long userId, Long reminderId, String title) {
        ReminderCancelledEvent event = new ReminderCancelledEvent(userId, reminderId, title);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒取消事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }

    // ===== Agent 状态流事件 =====

    // 发布 Agent 执行阶段事件（入口方法，自动构造 AgentStatusEvent）
    // 该方法调用后会立刻返回，事件发布和 WS 推送均在独立线程异步执行，不阻塞调用方
    public void publishAgentStatus(Long userId, AgentStatus status, String flowId, int stepIndex) {
        AgentStatusEvent event = new AgentStatusEvent(userId, status, flowId, stepIndex);
        applicationEventPublisher.publishEvent(event);
        log.debug("发布Agent状态事件: userId={}, flowId={}, step={}, status={}", userId, flowId, stepIndex, status);
    }

    // 发布带 extra 信息的 Agent 状态事件（用于带工具名/城市名/标题等附加信息）
    public void publishAgentStatus(Long userId, AgentStatus status, String flowId, int stepIndex,
                                   String extraKey, Object extraValue) {
        AgentStatusEvent event = new AgentStatusEvent(userId, status, flowId, stepIndex);
        event.addExtra(extraKey, extraValue);
        applicationEventPublisher.publishEvent(event);
        log.debug("发布Agent状态事件(extra): userId={}, flowId={}, step={}, status={}, {}={}",
                userId, flowId, stepIndex, status, extraKey, extraValue);
    }

    // 发布终态 Agent 状态事件（附带耗时、总步骤数）
    public void publishAgentStatusTerminal(Long userId, AgentStatus status, String flowId,
                                           int stepIndex, long durationMs, Integer totalSteps) {
        AgentStatusEvent event = AgentStatusEvent.terminal(userId, status, flowId, stepIndex, durationMs);
        if (totalSteps != null) event.setTotalSteps(totalSteps);
        applicationEventPublisher.publishEvent(event);
        log.debug("发布Agent终态事件: userId={}, flowId={}, step={}, status={}, duration={}ms, totalSteps={}",
                userId, flowId, stepIndex, status, durationMs, totalSteps);
    }

    // 发布终态 Agent 状态事件（重载：最后一个参数用作 ERROR 的 message）
    public void publishAgentStatusTerminal(Long userId, AgentStatus status, String flowId,
                                           int stepIndex, long durationMs, String errorMsg) {
        AgentStatusEvent event = AgentStatusEvent.terminal(userId, status, flowId, stepIndex, durationMs);
        if (errorMsg != null) event.addExtra("message", errorMsg);
        applicationEventPublisher.publishEvent(event);
        log.debug("发布Agent终态事件(ERROR): userId={}, flowId={}, step={}, status={}, duration={}ms, msg={}",
                userId, flowId, stepIndex, status, durationMs, errorMsg);
    }

    // 发布带工具信息的 Agent 状态事件（用于 CALLING_TOOL / TOOL_RESULT）
    // - toolName: 原始工具名
    // - toolCnName: 工具中文显示名
    // - detail: 附加详情（如参数摘要/结果摘要）
    public void publishAgentStatusWithTool(Long userId, AgentStatus status, String flowId, int stepIndex,
                                           String toolName, String toolCnName, String detail) {
        AgentStatusEvent event = new AgentStatusEvent(userId, status, flowId, stepIndex);
        if (toolName != null) event.addExtra("toolName", toolName);
        if (toolCnName != null) event.addExtra("toolCnName", toolCnName);
        if (detail != null && !detail.isBlank()) event.addExtra("detail", detail);
        applicationEventPublisher.publishEvent(event);
        log.debug("发布Agent工具状态事件: userId={}, flowId={}, step={}, status={}, tool={}, detail={}",
                userId, flowId, stepIndex, status, toolCnName, detail);
    }
}
