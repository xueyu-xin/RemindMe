package com.dormrepair.remindme.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * WebSocket配置
 * <p>
 * 配置STOMP协议的WebSocket消息代理
 * </p>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(ServerHttpRequest request,
                                                      org.springframework.web.socket.WebSocketHandler wsHandler,
                                                      Map<String, Object> attributes) {
                        // 优先从 HTTP header 获取 user-id
                        String userId = request.getHeaders().getFirst("user-id");

                        // 如果 header 中没有，尝试从查询参数获取
                        if (userId == null || userId.isEmpty()) {
                            if (request instanceof ServletServerHttpRequest) {
                                ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                                userId = servletRequest.getServletRequest().getParameter("user-id");
                            }
                        }

                        // 默认值
                        if (userId == null || userId.isEmpty()) {
                            userId = "1";
                            log.warn("⚠️ user-id 未找到，使用默认值: 1");
                        }

                        log.info("🔐 HandshakeHandler - 设置 Principal: {}", userId);

                        final String finalUserId = userId;
                        return new Principal() {
                            @Override
                            public String getName() {
                                return finalUserId;
                            }
                        };
                    }
                })
                .withSockJS();
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        log.info("🔌 WebSocket连接事件 - Principal: {}", user != null ? user.getName() : "null");
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String destination = accessor.getDestination();
        log.info("📝 WebSocket订阅事件 - Principal: {}, 目的地: {}",
                user != null ? user.getName() : "null",
                destination);
    }
}
