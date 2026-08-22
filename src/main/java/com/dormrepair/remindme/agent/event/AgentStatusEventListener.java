package com.dormrepair.remindme.agent.event;

import com.dormrepair.remindme.service.WebSocketPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Agent 状态事件异步监听器
// 监听 AgentStatusEvent，通过专用线程池异步推送到前端 WebSocket，
// 与 Agent ReAct 主循环完全解耦，确保推送行为不阻塞任何业务逻辑。
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusEventListener {

    private final WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 主监听方法：异步执行（使用 agentStatusExecutor 专用线程池）
    // 推送失败仅记录日志，不回滚不影响主流程
    @Async("agentStatusExecutor")
    @EventListener
    public void onAgentStatus(AgentStatusEvent event) {
        if (event == null || event.getUserId() == null || event.getStatus() == null) {
            return;
        }

        try {
            Map<String, Object> payload = buildPayload(event);
            // 统一推送到 /user/queue/messages 通道，type=AGENT_STATUS 供前端分发
            boolean ok = webSocketPushService.pushToUser(
                    event.getUserId(),
                    "/queue/messages",
                    payload
            );

            if (!ok) {
                log.warn("Agent状态推送失败(WS返回false): userId={}, flowId={}, status={}",
                        event.getUserId(), event.getFlowId(), event.getStatus());
            } else {
                log.debug("Agent状态推送成功: userId={}, flowId={}, step={}, status={}",
                        event.getUserId(), event.getFlowId(), event.getStepIndex(), event.getStatus());
            }
        } catch (Exception e) {
            // 推送异常不向上抛，避免影响 Spring Event 后续监听者
            log.error("Agent状态推送异常: userId={}, flowId={}, status={}, error={}",
                    event.getUserId(), event.getFlowId(), event.getStatus(), e.getMessage(), e);
        }
    }

    // 构建 WebSocket 消息 payload，结构与前端约定一致：
    // {
    //   type: "AGENT_STATUS",
    //   status: "THINKING",         AgentStatus.name()（前端主读取字段）
    //   statusCode: "THINKING",     兼容别名
    //   flowId: "...",              同一次对话共享，用于前端聚合步骤
    //   stepIndex: 1,               步骤序号
    //   displayName: "正在理解意图", 中文显示
    //   icon: "💭",                 emoji 图标
    //   terminal: false,            是否终态（打勾/结束）
    //   extra: {...},               附加信息
    //   durationMs: 123,            耗时（仅终态）
    //   totalSteps: 5,              总步骤数（仅 DONE）
    //   timestamp: "..."            事件时间戳
    // }
    private Map<String, Object> buildPayload(AgentStatusEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "AGENT_STATUS");
        map.put("flowId", event.getFlowId());
        map.put("stepIndex", event.getStepIndex());

        AgentStatus s = event.getStatus();
        // 同时放 status 和 statusCode，前端主用 status，保持兼容
        map.put("status", s.name());
        map.put("statusCode", s.name());
        map.put("displayName", s.getDisplayName());
        map.put("icon", s.getIcon());
        map.put("terminal", s.isTerminal());

        if (event.getExtra() != null && !event.getExtra().isEmpty()) {
            map.put("extra", event.getExtra());
        }
        if (event.getDurationMs() != null) {
            map.put("durationMs", event.getDurationMs());
        }
        if (event.getTotalSteps() != null) {
            map.put("totalSteps", event.getTotalSteps());
        }
        if (event.getTimestamp() != null) {
            map.put("timestamp", event.getTimestamp().toString());
        }
        return map;
    }
}
