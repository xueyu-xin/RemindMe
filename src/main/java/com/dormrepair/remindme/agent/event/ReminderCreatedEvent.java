package com.dormrepair.remindme.agent.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

// 提醒创建事件
// 当 Agent 创建提醒时发布此事件
@Data
@EqualsAndHashCode(callSuper = true)
public class ReminderCreatedEvent extends AgentEvent {

    // 提醒 ID
    private Long reminderId;

    // 提醒标题
    private String title;

    // 提醒时间
    private String remindTime;

    // 提醒地点
    private String location;

    // 是否需要确认
    private boolean confirmationRequired;

    // 构造方法
    // @param userId              用户 ID
    // @param reminderId          提醒 ID
    // @param title               标题
    // @param remindTime          提醒时间
    // @param location            地点
    // @param confirmationRequired 是否需要确认
    public ReminderCreatedEvent(Long userId, Long reminderId, String title, String remindTime, String location, boolean confirmationRequired) {
        super(userId, "REMINDER_CREATED");
        this.reminderId = reminderId;
        this.title = title;
        this.remindTime = remindTime;
        this.location = location;
        this.confirmationRequired = confirmationRequired;
    }
}
