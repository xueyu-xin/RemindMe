package com.dormrepair.remindme.agent.event;

import com.dormrepair.remindme.utils.TimeUtils;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

// Agent 事件基类
// 所有 Agent 相关事件的基类，包含用户 ID、时间戳和事件类型
@Data
public class AgentEvent {

    // 用户 ID
    private Long userId;

    // 事件时间戳
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime timestamp;

    // 事件类型
    private String eventType;

    // 构造方法
    // @param userId    用户 ID
    // @param eventType 事件类型
    public AgentEvent(Long userId, String eventType) {
        this.userId = userId;
        this.eventType = eventType;
        this.timestamp = TimeUtils.now();
    }
}
