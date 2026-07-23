package com.dormrepair.remindme.agent.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提醒确认事件
 * <p>
 * 当用户确认提醒时发布此事件
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReminderConfirmedEvent extends AgentEvent {

    /**
     * 提醒ID
     */
    private Long reminderId;

    /**
     * 提醒标题
     */
    private String title;

    /**
     * 构造方法
     *
     * @param userId     用户ID
     * @param reminderId 提醒ID
     * @param title      标题
     */
    public ReminderConfirmedEvent(Long userId, Long reminderId, String title) {
        super(userId, "REMINDER_CONFIRMED");
        this.reminderId = reminderId;
        this.title = title;
    }
}
