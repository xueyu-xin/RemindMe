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

// WebSocket STOMP 协议配置。启用消息代理、注册握手端点并绑定用户身份，用于实时提醒推送、Agent 对话流式输出等场景
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 消息代理三要素配置
    // enableSimpleBroker("/topic","/queue") - 启用内存型简单消息代理，/topic 用于广播、/queue 用于点对点
    // setApplicationDestinationPrefixes("/app")- 客户端发往服务端处理方法的消息前缀，如 @MessageMapping("/hello") 实际地址为 /app/hello
    // setUserDestinationPrefix("/user")     - 用户专属队列前缀，服务端 convertAndSendToUser("123","/xxx") 实际订阅路径为 /user/123/xxx
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    // 注册 STOMP 握手端点：客户端通过 /ws 建立 WebSocket 连接
    // setAllowedOriginPatterns("*")- 放开跨域，任意域名客户端都能发起握手
    // setHandshakeHandler          - 自定义握手阶段的用户身份绑定：从请求里解析 user-id，包装成 Principal，后续 convertAndSendToUser 才能按用户精准推送
    // withSockJS()                 - 启用 SockJS 回退，浏览器不支持原生 WebSocket 时自动降级为长轮询等替代传输
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    // 握手阶段决定当前连接绑定到哪个用户身份 Principal
                    // request    - 握手 HTTP 请求（含 header 与 query）
                    // wsHandler  - WebSocket 处理器
                    // attributes - 握手属性表
                    @Override
                    protected Principal determineUser(ServerHttpRequest request,
                                                      org.springframework.web.socket.WebSocketHandler wsHandler,
                                                      Map<String, Object> attributes) {
                        // 第 1 优先级：从 HTTP 请求头 user-id 获取
                        String userId = request.getHeaders().getFirst("user-id");

                        // 第 2 优先级：header 没有时降级从查询参数 ?user-id=xxx 取（兼容部分客户端不支持自定义 header 的情况）
                        if (userId == null || userId.isEmpty()) {
                            if (request instanceof ServletServerHttpRequest) {
                                ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                                userId = servletRequest.getServletRequest().getParameter("user-id");
                            }
                        }

                        // 兜底值：仍未拿到时给默认 1，保证连接不会因身份缺失失败，日志打警告提示排查
                        if (userId == null || userId.isEmpty()) {
                            userId = "1";
                            log.warn("⚠️ user-id 未找到，使用默认值: 1");
                        }

                        log.info("🔐 HandshakeHandler - 设置 Principal: {}", userId);

                        // 用解析出的 userId 构造匿名 Principal，返回后即绑定到此连接上
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

    // 监听 WebSocket 连接建立完成事件，日志记录当前连接绑定的用户身份，便于排查"推送收不到"类问题
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        log.info("🔌 WebSocket连接事件 - Principal: {}", user != null ? user.getName() : "null");
    }

    // 监听客户端订阅目的地事件，日志记录哪个用户订阅了哪个 topic/queue，协助排查订阅路径错误
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
