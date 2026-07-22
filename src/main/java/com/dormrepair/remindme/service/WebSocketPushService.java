package com.dormrepair.remindme.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket推送服务
 * <p>
 * 使用 convertAndSendToUser 向指定用户推送消息
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 向指定用户推送消息
     *
     * @param userId      用户ID
     * @param destination 目标路径（如 /queue/messages, /queue/confirmations）
     * @param payload     消息负载
     * @return 是否推送成功
     */
    public boolean pushToUser(Long userId, String destination, Object payload) {
        return pushToUser(String.valueOf(userId), destination, payload);
    }

    /**
     * 向指定用户推送消息（字符串userId版本）
     *
     * @param userId      用户ID
     * @param destination 目标路径（如 /queue/messages, /queue/confirmations）
     * @param payload     消息负载
     * @return 是否推送成功
     */
    public boolean pushToUser(String userId, String destination, Object payload) {
        log.info("🔔 pushToUser: userId={}, destination={}", userId, destination);
        try {
            messagingTemplate.convertAndSendToUser(userId, destination, payload);
            log.info("✅ 推送完成: userId={}, destination={}", userId, destination);
            return true;
        } catch (Exception e) {
            log.error("❌ 推送失败: userId={}, destination={}, error={}", userId, destination, e.getMessage(), e);
            return false;
        }
    }
}
