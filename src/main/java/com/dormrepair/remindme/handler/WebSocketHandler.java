package com.dormrepair.remindme.handler;

import com.dormrepair.remindme.service.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket消息处理器
 * <p>
 * 拦截WebSocket消息，处理连接、消息发送等事件
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHandler implements ChannelInterceptor {

    private final ObjectMapper objectMapper;
    private final PushService pushService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            handleConnect(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            handleSubscribe(accessor);
        } else if (command == StompCommand.SEND) {
            handleSend(message, accessor);
        } else if (command == StompCommand.DISCONNECT) {
            handleDisconnect(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String userId = accessor.getFirstNativeHeader("user-id");
        log.info("WebSocket连接: userId={}", userId);
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        String userId = accessor.getFirstNativeHeader("user-id");
        log.info("WebSocket订阅: userId={}, destination={}", userId, destination);
    }

    private void handleSend(Message<?> message, StompHeaderAccessor accessor) {
        try {
            String payload = new String((byte[]) message.getPayload());
            JsonNode root = objectMapper.readTree(payload);

            String type = root.has("type") ? root.get("type").asText() : "UNKNOWN";
            String userId = accessor.getFirstNativeHeader("user-id");

            log.debug("WebSocket消息: userId={}, type={}", userId, type);

            switch (type) {
                case "REMINDER_READ" -> handleReminderRead(root, userId);
                case "HEARTBEAT" -> handleHeartbeat(userId);
                case "CHAT_MESSAGE" -> handleChatMessage(root, userId);
                default -> log.warn("未知消息类型: {}", type);
            }

        } catch (Exception e) {
            log.error("WebSocket消息处理失败: ", e);
        }
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        String userId = accessor.getFirstNativeHeader("user-id");
        log.info("WebSocket断开连接: userId={}", userId);
    }

    private void handleReminderRead(JsonNode root, String userId) {
        if (root.has("reminderId")) {
            Long reminderId = root.get("reminderId").asLong();
            log.info("用户已读提醒: userId={}, reminderId={}", userId, reminderId);
        }
    }

    private void handleHeartbeat(String userId) {
        log.debug("心跳检测: userId={}", userId);
    }

    private void handleChatMessage(JsonNode root, String userId) {
        if (root.has("message")) {
            String message = root.get("message").asText();
            log.debug("聊天消息: userId={}, message={}", userId, message);
        }
    }
}
