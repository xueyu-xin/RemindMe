package com.dormrepair.remindme.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

// WebSocket 推送底层封装：基于 SimpMessagingTemplate 把消息定点投递到某个用户的订阅频道
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    private final SimpMessagingTemplate messagingTemplate;

    // Long 型 userId 重载，内部转字符串后委托给字符串版本
    // userId   目标用户ID
    // destination 订阅路径，如 /queue/messages、/queue/confirmations
    // payload  消息内容
    public boolean pushToUser(Long userId, String destination, Object payload) {
        return pushToUser(String.valueOf(userId), destination, payload);
    }

    // 实际推送逻辑：调用 convertAndSendToUser 定点投递；异常时返回 false，不向上抛
    // userId   目标用户ID（字符串形式）
    // destination 订阅路径
    // payload  消息内容
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
