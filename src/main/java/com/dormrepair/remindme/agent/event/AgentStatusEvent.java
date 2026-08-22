package com.dormrepair.remindme.agent.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

// Agent 状态推送事件
// 每次 Agent 进入新阶段（思考、调用工具、完成等）时发布此事件，
// 由异步监听器通过 WebSocket 推送给前端，驱动状态流 UI 显示。
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentStatusEvent extends AgentEvent {

    // 当前状态枚举
    private AgentStatus status;

    // 状态流唯一标识：同一次对话的所有状态事件共享同一个 flowId，
    // 前端据此把所有步骤聚合到同一个状态流气泡中。
    private String flowId;

    // 步骤序号：从 1 开始递增，用于前端排序和显示进度
    private int stepIndex;

    // 附加信息（可选）：如工具名、天气城市名、提醒标题等，便于前端显示更丰富的文案
    private Map<String, Object> extra;

    // 耗时（毫秒）：仅在 DONE 和 TOOL_RESULT 终态事件中填充
    private Long durationMs;

    // 总步骤数（仅在 DONE 事件中填充，用于生成摘要）
    private Integer totalSteps;

    // 快捷构造：基础状态事件
    public AgentStatusEvent(Long userId, AgentStatus status, String flowId, int stepIndex) {
        super(userId, "AGENT_STATUS");
        this.status = status;
        this.flowId = flowId;
        this.stepIndex = stepIndex;
        this.extra = new HashMap<>();
    }

    // 链式追加 extra 信息
    public AgentStatusEvent addExtra(String key, Object value) {
        if (this.extra == null) this.extra = new HashMap<>();
        if (key != null && value != null) this.extra.put(key, value);
        return this;
    }

    // 构造终态事件（DONE / TOOL_RESULT / ERROR），附带耗时
    public static AgentStatusEvent terminal(Long userId, AgentStatus status, String flowId,
                                            int stepIndex, long durationMs) {
        AgentStatusEvent e = new AgentStatusEvent(userId, status, flowId, stepIndex);
        e.setDurationMs(durationMs);
        return e;
    }
}
