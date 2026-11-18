package com.dormrepair.remindme.agent.event;

import com.dormrepair.remindme.service.WebSocketPushService;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// 私有记忆变更事件监听器：收到 RagService 发布的变更事件后，
// 向对应用户的 WebSocket /queue/messages 频道推送 PRIVATE_MEMORY_CHANGED 消息，
// 前端记忆库页面收到后静默刷新列表。推送失败仅记日志，不影响记忆写主流程。
@Slf4j
@Component
@RequiredArgsConstructor
public class PrivateMemoryChangedEventListener {

    private final WebSocketPushService webSocketPushService;

    // 监听记忆变更事件并同步推送到前端
    @EventListener
    public void onMemoryChanged(PrivateMemoryChangedEvent event) {
        if (event == null || event.getUserId() == null) {
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "PRIVATE_MEMORY_CHANGED");
            payload.put("action", event.getAction());
            payload.put("memoryId", event.getMemoryId());
            payload.put("timestamp", TimeUtils.toTimestamp(TimeUtils.now()));

            boolean ok = webSocketPushService.pushToUser(
                    event.getUserId(),
                    "/queue/messages",
                    payload
            );

            if (!ok) {
                log.warn("记忆变更推送失败(WS返回false): userId={}, action={}, memoryId={}",
                        event.getUserId(), event.getAction(), event.getMemoryId());
            } else {
                log.debug("记忆变更推送成功: userId={}, action={}, memoryId={}",
                        event.getUserId(), event.getAction(), event.getMemoryId());
            }
        } catch (Exception e) {
            // 推送异常不向上抛，避免影响记忆写入主流程
            log.error("记忆变更推送异常: userId={}, action={}, error={}",
                    event.getUserId(), event.getAction(), e.getMessage(), e);
        }
    }
}

