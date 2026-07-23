package com.dormrepair.remindme.agent.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 事件发布器
 * <p>
 * 负责发布Agent相关事件，解耦组件之间的直接依赖
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 发布提醒创建事件
     *
     * @param userId              用户ID
     * @param reminderId          提醒ID
     * @param title               标题
     * @param remindTime          提醒时间
     * @param location            地点
     * @param confirmationRequired 是否需要确认
     */
    public void publishReminderCreated(Long userId, Long reminderId, String title, String remindTime, String location, boolean confirmationRequired) {
        ReminderCreatedEvent event = new ReminderCreatedEvent(userId, reminderId, title, remindTime, location, confirmationRequired);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒创建事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }

    /**
     * 发布提醒确认事件
     *
     * @param userId     用户ID
     * @param reminderId 提醒ID
     * @param title      标题
     */
    public void publishReminderConfirmed(Long userId, Long reminderId, String title) {
        ReminderConfirmedEvent event = new ReminderConfirmedEvent(userId, reminderId, title);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒确认事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }

    /**
     * 发布提醒取消事件
     *
     * @param userId     用户ID
     * @param reminderId 提醒ID
     * @param title      标题
     */
    public void publishReminderCancelled(Long userId, Long reminderId, String title) {
        ReminderCancelledEvent event = new ReminderCancelledEvent(userId, reminderId, title);
        applicationEventPublisher.publishEvent(event);
        log.info("发布提醒取消事件: userId={}, reminderId={}, title={}", userId, reminderId, title);
    }
}
