package com.dormrepair.remindme.agent.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

// 提醒取消事件
// 当用户取消提醒时发布此事件
@Data
@EqualsAndHashCode(callSuper = true)
public class ReminderCancelledEvent extends AgentEvent {

    // 提醒 ID
    private Long reminderId;

    // 提醒标题
    private String title;

    // 构造方法
    // @param userId     用户 ID
    // @param reminderId 提醒 ID
    // @param title      标题
    public ReminderCancelledEvent(Long userId, Long reminderId, String title) {
        super(userId, "REMINDER_CANCELLED");
        this.reminderId = reminderId;
        this.title = title;
    }
}
