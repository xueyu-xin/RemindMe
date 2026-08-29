package com.dormrepair.remindme.agent.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 私有记忆变更事件。任何页面或 Agent 对记忆的新增/删除都由 RagService 发布此事件，
// 监听器统一推送到 WebSocket，让记忆库页面与 Agent 聊天侧保持实时同步
@Getter
@RequiredArgsConstructor
public class PrivateMemoryChangedEvent {

    // 变更类型：ADD / DELETE
    private final String action;

    // 记忆归属用户 id，推送时用于定位用户频道
    private final Long userId;

    // 本次变更涉及的记忆文档 id
    private final String memoryId;
}
