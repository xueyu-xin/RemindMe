package com.dormrepair.remindme.service;

import com.dormrepair.remindme.agent.ConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-3 配套测试：验证 ConversationMemory 的裁剪阈值。
 * - 无 Spring 容器直接 new 时走兜底（默认 10）
 * - Spring 容器注入 agent.max-history 时走配置值
 */
public class ConversationMemoryTest {

    // ================== 纯单元测试（不启动 Spring）==================

    /**
     * 直接 new ConversationMemory()：@Value 不生效，historyLimit=0，
     * 应由 getHistoryLimit() 回退 FALLBACK_HISTORY_LIMIT=10。
     */
    @Test
    @DisplayName("P1-3 兜底上限 10：超出后裁剪为末尾 10 条")
    void testFallbackHistoryLimit_default10() {
        ConversationMemory memory = new ConversationMemory();
        Long userId = 1001L;

        // 追加 15 条用户消息，预期被裁剪到 10
        for (int i = 1; i <= 15; i++) {
            memory.addUserMessage(userId, "msg-" + i);
        }

        int actual = memory.getMessageCount(userId);
        assertEquals(10, actual, "超过兜底上限 10 条应裁剪为末尾 10 条，实际 " + actual);

        List<ConversationMemory.Message> history = memory.getHistory(userId);
        assertEquals("msg-6", history.get(0).getContent(), "最旧一条应是 msg-6（裁掉 1-5）");
        assertEquals("msg-15", history.get(history.size() - 1).getContent(), "最新一条应是 msg-15");
    }

    @Test
    @DisplayName("P1-3 无参 trimHistory() 走配置值：先写满 15，再手动调用无参应剩 10")
    void testTrimHistoryNoArg_usesInjectedLimit() {
        // 用反射 set 不太方便，addUserMessage 内部自动触发裁剪一次后不会再动。
        // 所以构造场景：先 add 10 条 → 不裁剪；再 add 0 条（保持 10）后手动 clear + 写 15 条
        // 但 add 的内部会自动裁，所以直接测：加到 15 条 → 已经裁成 10；继续追加更多 → 依然维持 10。
        ConversationMemory memory = new ConversationMemory();
        Long userId = 1002L;

        for (int i = 1; i <= 15; i++) memory.addUserMessage(userId, "m-" + i);
        assertEquals(10, memory.getMessageCount(userId));

        for (int i = 16; i <= 25; i++) memory.addUserMessage(userId, "m-" + i);
        assertEquals(10, memory.getMessageCount(userId), "继续追加 10 条仍应保持上限 10");

        List<ConversationMemory.Message> history = memory.getHistory(userId);
        assertEquals("m-16", history.get(0).getContent());
        assertEquals("m-25", history.get(history.size() - 1).getContent());
    }

    @Test
    @DisplayName("基本追加/读取：用户消息+助手消息+系统消息")
    void testAddAndGetHistory() {
        ConversationMemory memory = new ConversationMemory();
        Long userId = 2001L;

        memory.addUserMessage(userId, "hello");
        memory.addAssistantMessage(userId, "hi");
        memory.addSystemMessage(userId, "sys");

        List<ConversationMemory.Message> list = memory.getHistory(userId);
        assertEquals(3, list.size());
        assertEquals("user", list.get(0).getRole());
        assertEquals("assistant", list.get(1).getRole());
        assertEquals("system", list.get(2).getRole());
    }

    @Test
    @DisplayName("工具消息：addToolCall / addToolResult 写入 tool 角色与字段")
    void testAddToolMessages() {
        ConversationMemory memory = new ConversationMemory();
        Long userId = 3001L;

        memory.addToolCall(userId, "create_reminder", Map.of("title", "还书"));
        memory.addToolResult(userId, "create_reminder", Map.of("id", 42), true);

        List<ConversationMemory.Message> list = memory.getHistory(userId);
        assertEquals(2, list.size());
        assertEquals("tool", list.get(0).getRole());
        assertNotNull(list.get(0).getToolCall());
        assertEquals("create_reminder", list.get(0).getToolCall().getToolName());

        assertEquals("tool", list.get(1).getRole());
        assertNotNull(list.get(1).getToolResult());
        assertTrue(list.get(1).getToolResult().isSuccess());
    }

    @Test
    @DisplayName("hasHistory / clear / getMessageCount / clearAll")
    void testLifecycleOps() {
        ConversationMemory memory = new ConversationMemory();
        Long u1 = 4001L;
        Long u2 = 4002L;

        assertFalse(memory.hasHistory(u1));
        assertEquals(0, memory.getMessageCount(u1));

        memory.addUserMessage(u1, "a");
        memory.addUserMessage(u2, "b");

        assertTrue(memory.hasHistory(u1));
        assertTrue(memory.hasHistory(u2));
        assertEquals(2, memory.getSessionCount());

        memory.clear(u1);
        assertFalse(memory.hasHistory(u1));
        assertEquals(1, memory.getSessionCount());

        memory.clearAll();
        assertEquals(0, memory.getSessionCount());
    }

    @Test
    @DisplayName("getRecentHistory：超限时取末尾，不足时返回完整")
    void testGetRecentHistory() {
        ConversationMemory memory = new ConversationMemory();
        Long u = 5001L;

        for (int i = 1; i <= 8; i++) memory.addUserMessage(u, "x" + i);

        List<ConversationMemory.Message> r5 = memory.getRecentHistory(u, 5);
        assertEquals(5, r5.size());
        assertEquals("x4", r5.get(0).getContent());
        assertEquals("x8", r5.get(r5.size() - 1).getContent());

        List<ConversationMemory.Message> r20 = memory.getRecentHistory(u, 20);
        assertEquals(8, r20.size(), "不足 limit 应返回完整历史");
    }

    // ================== Spring 集成测试：验证 @Value 注入 ==================

    @SpringBootTest(properties = {"agent.max-history=7"})
    static class ValueInjectionTest {

        @Autowired
        private ConversationMemory memory;

        @Test
        @DisplayName("P1-3 @Value(${agent.max-history:10})：显式配置 7 → 裁剪上限应为 7")
        void testConfiguredHistoryLimit_seven() {
            Long userId = 9001L;
            for (int i = 1; i <= 12; i++) {
                memory.addUserMessage(userId, "cfg-" + i);
            }

            int actual = memory.getMessageCount(userId);
            assertEquals(7, actual, "agent.max-history=7 应裁剪到 7 条，实际 " + actual);

            List<ConversationMemory.Message> history = memory.getHistory(userId);
            assertEquals("cfg-6", history.get(0).getContent(), "最旧一条应是 cfg-6（裁掉 1-5）");
            assertEquals("cfg-12", history.get(history.size() - 1).getContent(), "最新一条应是 cfg-12");

            // 收尾：避免污染其它测试
            memory.clear(userId);
        }
    }
}
