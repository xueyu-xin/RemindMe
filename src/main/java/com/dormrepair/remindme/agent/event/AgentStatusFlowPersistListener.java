package com.dormrepair.remindme.agent.event;

import com.dormrepair.remindme.entity.AgentStatusFlow;
import com.dormrepair.remindme.repository.AgentStatusFlowRepository;
import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Agent 状态流落库旁路监听器
// 监听 AgentStatusEvent，在内存中按 flowId 积累步骤明细，终态（DONE/ERROR）时一次性写入 agent_status_flows 表。
// 设计原则：
//   1) 纯旁路：独立监听器，不修改任何现有事件链路，与 WS 推送监听器互不影响
//   2) 全吞异常：落库/序列化的任何异常仅记录日志，绝不影响 Agent 主流程、确认卡片和提醒功能
//   3) 有界缓冲：超过 TTL 或容量上限自动淘汰，服务异常中断也不会残留内存
//   4) 秒回跳过：DONE 且耗时 < 300ms 不落库，与前端「秒回不展示状态流」的行为保持一致
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusFlowPersistListener {

    private final AgentStatusFlowRepository agentStatusFlowRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // flowId -> 步骤缓冲区
    private final ConcurrentHashMap<String, FlowBuffer> buffers = new ConcurrentHashMap<>();

    // 缓冲区保活时长：超过 10 分钟未更新视为中断残留，直接淘汰
    private static final long BUFFER_TTL_MS = 10 * 60 * 1000L;

    // 缓冲区容量上限：防止异常场景下内存无限增长
    private static final int MAX_BUFFERS = 200;

    // 秒回跳过阈值：与前端 SKIP_THRESHOLD_MS 保持一致
    private static final long SKIP_THRESHOLD_MS = 300;

    // 主监听方法：异步执行（复用 agentStatusExecutor 专用线程池）
    @Async("agentStatusExecutor")
    @EventListener
    public void onAgentStatus(AgentStatusEvent event) {
        // 参数不完整直接忽略，不做任何副作用
        if (event == null || event.getUserId() == null || event.getStatus() == null
                || event.getFlowId() == null || event.getFlowId().isBlank()) {
            return;
        }
        try {
            handleEvent(event);
        } catch (Exception e) {
            // 落库旁路的任何异常都只记录日志，不向上抛
            log.warn("状态流落库旁路异常(忽略): flowId={}, status={}, error={}",
                    event.getFlowId(), event.getStatus(), e.getMessage());
        }
    }

    // 事件处理主逻辑：积累步骤，终态落库
    private void handleEvent(AgentStatusEvent event) {
        String flowId = event.getFlowId();
        AgentStatus status = event.getStatus();

        // 收到新 flow 的首个事件时顺带清理过期/超量缓冲
        if (status == AgentStatus.START_THINKING && !buffers.containsKey(flowId)) {
            purgeStaleBuffers();
        }

        FlowBuffer buffer = buffers.computeIfAbsent(flowId, k -> new FlowBuffer(event.getUserId()));
        buffer.lastUpdateMs = TimeUtils.toTimestamp(TimeUtils.now());

        // 单步明细：结构对齐前端 buildStepRow 的入参，刷新后可直接复用同一渲染逻辑
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepIndex", event.getStepIndex());
        step.put("status", status.name());
        step.put("displayName", status.getDisplayName());
        step.put("icon", status.getIcon());
        step.put("terminal", status.isTerminal());
        if (event.getExtra() != null && !event.getExtra().isEmpty()) {
            step.put("extra", event.getExtra());
        }
        if (event.getDurationMs() != null) {
            step.put("durationMs", event.getDurationMs());
        }
        synchronized (buffer) {
            buffer.steps.add(step);
        }

        // 非终态（DONE/ERROR）继续积累；TOOL_RESULT 是单步终态但不结束整轮
        if (status != AgentStatus.DONE && status != AgentStatus.ERROR) {
            return;
        }

        // 终态：移除缓冲后落库（移除即防重，重复终态事件不会再命中缓冲）
        buffers.remove(flowId);

        Long durationMs = event.getDurationMs();
        // DONE 秒回：与前端一致不落库
        if (status == AgentStatus.DONE && durationMs != null && durationMs < SKIP_THRESHOLD_MS) {
            return;
        }

        int totalSteps = event.getTotalSteps() != null ? event.getTotalSteps() : buffer.steps.size();
        saveFlow(event.getUserId(), flowId, status.name(), totalSteps, durationMs, buffer.steps);
    }

    // 写入一条状态流记录，异常只记日志
    private void saveFlow(Long userId, String flowId, String finalStatus,
                          int totalSteps, Long durationMs, List<Map<String, Object>> steps) {
        try {
            AgentStatusFlow row = new AgentStatusFlow();
            row.setFlowId(flowId);
            row.setUserId(userId);
            row.setFinalStatus(finalStatus);
            row.setTotalSteps(totalSteps);
            row.setDurationMs(durationMs);
            row.setStepsJson(objectMapper.writeValueAsString(steps));
            agentStatusFlowRepository.save(row);
            log.debug("状态流落库成功: userId={}, flowId={}, status={}, steps={}",
                    userId, flowId, finalStatus, totalSteps);
        } catch (Exception e) {
            log.warn("状态流落库失败(忽略): userId={}, flowId={}, error={}", userId, flowId, e.getMessage());
        }
    }

    // 清理过期缓冲 + 超量时淘汰最旧缓冲，防止内存残留
    private void purgeStaleBuffers() {
        if (buffers.isEmpty()) {
            return;
        }
        long now = TimeUtils.toTimestamp(TimeUtils.now());
        buffers.entrySet().removeIf(e -> now - e.getValue().lastUpdateMs > BUFFER_TTL_MS);
        while (buffers.size() > MAX_BUFFERS) {
            String oldest = null;
            long oldestMs = Long.MAX_VALUE;
            for (Map.Entry<String, FlowBuffer> e : buffers.entrySet()) {
                if (e.getValue().lastUpdateMs < oldestMs) {
                    oldestMs = e.getValue().lastUpdateMs;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            buffers.remove(oldest);
        }
    }

    // 单个 flow 的步骤缓冲区
    private static class FlowBuffer {
        final Long userId;
        final List<Map<String, Object>> steps = new ArrayList<>();
        volatile long lastUpdateMs = TimeUtils.toTimestamp(TimeUtils.now());

        FlowBuffer(Long userId) {
            this.userId = userId;
        }
    }
}

