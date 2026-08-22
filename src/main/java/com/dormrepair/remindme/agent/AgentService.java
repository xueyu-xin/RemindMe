package com.dormrepair.remindme.agent;

import com.dormrepair.remindme.agent.ConversationMemory.Message;
import com.dormrepair.remindme.agent.event.AgentStatus;
import com.dormrepair.remindme.agent.event.AgentStatusMapper;
import com.dormrepair.remindme.agent.event.EventPublisher;
import com.dormrepair.remindme.agent.event.ReminderCancelledEvent;
import com.dormrepair.remindme.agent.event.ReminderConfirmedEvent;
import com.dormrepair.remindme.agent.event.ReminderCreatedEvent;
import com.dormrepair.remindme.service.LLMService;
import com.dormrepair.remindme.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Agent 核心调度器。实现 ReAct（思考-行动-观察）循环：解析用户输入→调用 LLM→决定是否执行工具→收集结果→再次迭代，
// 直至生成最终回复；集成空响应降级、演戏格式拦截、重复调用去重+卡死兜底、确认卡片生成、事件监听等能力；
// 同时通过 EventPublisher 异步发布 AgentStatusEvent，驱动前端状态流显示。
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory conversationMemory;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final EventPublisher eventPublisher;

    // 启动开关：可通过配置 agent.enabled=false 临时关闭 Agent
    @Value("${agent.enabled:false}")
    private boolean agentEnabled;

    // 最大迭代次数：ReAct 主循环最多执行 5 轮，防止无节制消耗 token
    private static final int MAX_ITERATIONS = 5;

    // 最大工具调用次数：单次请求最多执行 5 次工具，避免长链路
    private static final int MAX_TOOL_CALLS = 5;

    // 统一发布 DONE 终态事件并返回结果，每个 return 调用它避免重复代码；
    // 事件发布失败静默吞异常，不影响正常业务返回（保障回复速度优先）
    private AgentResponse finishWith(String flowId, long startTime, int stepCount, Long userId, AgentResponse resp) {
        try {
            long dur = System.currentTimeMillis() - startTime;
            eventPublisher.publishAgentStatusTerminal(userId, AgentStatus.DONE, flowId, stepCount, dur, stepCount);
        } catch (Exception ignore) {
            log.warn("finishWith发布DONE事件失败(忽略): flowId={}, error={}", flowId, ignore.getMessage());
        }
        return resp;
    }

    // 发布 ERROR 终态事件并返回错误响应（异常出口调用）
    private AgentResponse finishWithError(String flowId, long startTime, int stepCount, Long userId, String errorMsg) {
        try {
            long dur = System.currentTimeMillis() - startTime;
            eventPublisher.publishAgentStatusTerminal(userId, AgentStatus.ERROR, flowId, stepCount, dur, errorMsg);
        } catch (Exception ignore) {
            log.warn("finishWithError发布ERROR事件失败(忽略): flowId={}, error={}", flowId, ignore.getMessage());
        }
        return AgentResponse.error(errorMsg);
    }

    // 主入口：无上下文时直接调用带上下文重载
    public AgentResponse processRequest(Long userId, String userInput) {
        return processRequest(userId, userInput, null);
    }

    // 处理用户请求完整流程：
    // 1) 落盘 userInput + 可选 GPS 上下文 system message；
    // 2) 进入 ReAct 循环：LLM 响应→解析 tool_call→执行→落结果；
    // 3) 达到 MAX_ITERATIONS 仍未出最终回复时走兜底总结模块。
    public AgentResponse processRequest(Long userId, String userInput, Map<String, Object> context) {
        log.info("Agent开始处理请求: userId={}, userInput={}", userId, userInput);

        // ---------- 状态流上下文初始化 ----------
        // flowId：同一次对话共享，前端用来把所有步骤聚合到一个状态流气泡
        final String flowId = UUID.randomUUID().toString();
        final long startTime = System.currentTimeMillis();
        // step 计数器（数组包装以便 lambda/内部类访问）
        final int[] stepHolder = { 0 };
        // 每次调用 +1 返回新序号
        java.util.function.Supplier<Integer> nextStep = () -> { stepHolder[0] += 1; return stepHolder[0]; };

        // Agent 未启用时仍发布 START + DONE（极简两步：开始→直接返回），保持前端状态流一致可观测
        if (!agentEnabled) {
            eventPublisher.publishAgentStatus(userId, AgentStatus.START_THINKING, flowId, nextStep.get());
            String fallback = "收到：" + userInput;
            conversationMemory.addUserMessage(userId, userInput);
            conversationMemory.addAssistantMessage(userId, fallback);
            return finishWith(flowId, startTime, stepHolder[0], userId, AgentResponse.text(fallback));
        }

        // 入口：发布 START_THINKING
        eventPublisher.publishAgentStatus(userId, AgentStatus.START_THINKING, flowId, nextStep.get());

        try {
            conversationMemory.addUserMessage(userId, userInput);

            // GPS 坐标上下文：注入为 system 消息，提示模型调用 get_location
            if (context != null && context.containsKey("latitude") && context.containsKey("longitude")) {
                Double lat = (Double) context.get("latitude");
                Double lng = (Double) context.get("longitude");
                String locationInfo = String.format(
                        "用户当前GPS坐标：纬度 %s，经度 %s，请调用 get_location 工具将坐标转换为地址",
                        lat, lng
                );
                conversationMemory.addSystemMessage(userId, locationInfo);
                log.info("请求包含GPS坐标: lat={}, lng={}", lat, lng);
            }

            // 记录本次对话起始下标，后续兜底总结切片用
            int currentTurnStartIndex = conversationMemory.getMessageCount(userId) - 1;

            List<Message> history = conversationMemory.getHistory(userId);
            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();

            // 迭代内的计数器与状态
            String llmResponse = null;
            Object lastToolResult = null;
            int iteration = 0;
            int toolCallCount = 0;
            int actedFormatBlockCount = 0;   // 连续"中文演戏格式"拦截计数器，超过 2 次不再注入提示，防止死循环
            int duplicateCallCount = 0;      // 连续"相同工具+相同参数"重复调用计数，达 2 次强制兜底退出
            List<String> toolHistory = new java.util.ArrayList<>();
            // 已执行签名表：拦截 LLM 陷入"重复调同一工具、参数完全一致"的死循环
            java.util.Set<String> executedSignatures = new java.util.HashSet<>();

            // ============================================================
            // ReAct 主循环
            // ============================================================
            while (iteration < MAX_ITERATIONS) {
                iteration++;
                log.info("Agent执行进度: 第 {}/{} 步, 已调用工具: {}", iteration, MAX_ITERATIONS, toolHistory);

                // ---------- 状态流：正在思考（理解意图/生成下一步）----------
                eventPublisher.publishAgentStatus(userId, AgentStatus.THINKING, flowId, nextStep.get());

                // 思考：LLM 生成响应或 tool_call JSON
                llmResponse = llmService.chatWithTools(userInput, tools, history);

                // 空响应降级：连续两次为空则返回友好错误，避免卡死
                if (llmResponse == null || llmResponse.isBlank()) {
                    log.warn("LLM返回空响应，尝试简化重试 (iteration={})", iteration);
                    // 重试提示需覆盖常见工具场景，并引导 LLM 基于历史工具结果继续，避免只引导创建提醒导致天气查询等场景卡死
                    String retryInput = "用户请求：" + userInput + "\n请根据对话历史中的工具调用结果继续处理："
                            + "如需创建提醒，调用 create_reminder 或 create_recurring_reminder；"
                            + "如已通过 geocode_city 获取坐标，请调用 query_weather_now 查询实时天气或 query_weather_forecast 查询预报；"
                            + "如所有必要工具已调用完毕，请基于结果直接生成最终自然语言回复。";
                    llmResponse = llmService.chatWithTools(retryInput, tools, history);
                    if (llmResponse == null || llmResponse.isBlank()) {
                        log.error("LLM两次返回空响应，返回降级错误");
                        String fallbackMsg = "抱歉，处理请求时出现问题，请稍后重试或换一种方式描述。";
                        conversationMemory.addAssistantMessage(userId, fallbackMsg);
                        return finishWith(flowId, startTime, stepHolder[0], userId, AgentResponse.text(fallbackMsg));
                    }
                    log.info("简化重试成功，LLM返回响应");
                }

                // 解析 tool_call（支持一次响应多个工具）
                List<ToolCall> llmToolCalls = llmService.parseToolCalls(llmResponse);

                if (llmToolCalls.isEmpty()) {
                    // ---------- 演戏格式拦截 ----------
                    // 若内容里有"工具调用:""工具执行结果:"等字样但 parse 没命中，
                    // 说明 LLM 在用"中文演戏格式"表演调用而非输出真正 JSON；注入警告让其重跑
                    if (isActedFormat(llmResponse) && actedFormatBlockCount < 2) {
                        actedFormatBlockCount++;
                        log.warn("检测到LLM输出中文演戏格式（第{}次拦截），强制重跑。原始响应前200字：{}",
                                actedFormatBlockCount,
                                llmResponse.length() > 200 ? llmResponse.substring(0, 200) + "..." : llmResponse);
                        String strictMsg = "【严重警告】你刚才用的是内部演戏格式！错误内容包含「工具调用:」「工具执行结果:」，" +
                                "但这些不是真正的工具调用，用户一个操作也没有被执行！\n" +
                                "正确做法：需要调用工具时只能输出 纯JSON，严格按：\n" +
                                "  {\"tool_call\":{\"name\":\"工具名\",\"arguments\":{...}}}\n" +
                                "  JSON前后 严禁任何自然语言、说明、换行、示例。\n" +
                                "重新处理用户的原始请求：「" + userInput + "」";
                        history.add(ConversationMemory.Message.builder()
                                .role("system")
                                .content(strictMsg)
                                .build());
                        continue;
                    }

                    // 无工具调用 → 作为最终自然语言回复
                    log.info("LLM直接回复，结束循环（迭代次数: {}, 工具调用次数: {}）", iteration, toolCallCount);

                    // ---------- 状态流：正在生成最终回复 ----------
                    eventPublisher.publishAgentStatus(userId, AgentStatus.GENERATING_REPLY, flowId, nextStep.get());

                    // 如果最终回复里残留"tool_call" JSON 片段，替换为友好提示
                    if (containsToolCallFragment(llmResponse)) {
                        log.warn("LLM输出包含未解析的tool_call JSON片段，过滤后返回友好提示");
                        String friendlyMsg = "操作失败，请重试或换一种方式描述。";
                        conversationMemory.addAssistantMessage(userId, friendlyMsg);
                        return finishWith(flowId, startTime, stepHolder[0], userId, AgentResponse.text(friendlyMsg));
                    }

                    // 清理过程语+演戏段，保存最终回复并返回
                    String finalReply = cleanFinalResponse(llmResponse);
                    log.info("Agent执行完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
                    conversationMemory.addAssistantMessage(userId, finalReply);
                    return finishWith(flowId, startTime, stepHolder[0], userId,
                            buildAgentResponse(finalReply, lastToolResult, userId));
                }

                // 真正解析到 ToolCall，重置演戏拦截计数
                actedFormatBlockCount = 0;

                // ---------- 工具执行循环 ----------
                for (ToolCall llmToolCall : llmToolCalls) {
                    if (toolCallCount >= MAX_TOOL_CALLS) {
                        log.warn("达到最大工具调用次数 {}，跳过剩余工具调用", MAX_TOOL_CALLS);
                        break;
                    }

                    // ---------- 重复调用拦截 ----------
                    // 计算 toolName + 排序后参数组成的签名；已存在表示相同工具相同参数被调过，LLM 陷入了死循环
                    String signature = toolCallSignature(llmToolCall);
                    if (executedSignatures.contains(signature)) {
                        duplicateCallCount++;
                        log.warn("检测到LLM重复调用相同工具+相同参数（第{}次），跳过执行: toolName={}, args={}",
                                duplicateCallCount, llmToolCall.getToolName(), llmToolCall.getArguments());

                        if (duplicateCallCount >= 2) {
                            // 连续 2 次 → 视为 LLM 卡死，强制调用兜底总结退出循环
                            log.warn("连续{}次重复调用，强制触发兜底总结退出循环", duplicateCallCount);

                            // ---------- 状态流：正在生成最终回复（兜底总结）----------
                            eventPublisher.publishAgentStatus(userId, AgentStatus.GENERATING_REPLY, flowId, nextStep.get());

                            List<Message> currentTurnHistory = extractCurrentTurnHistory(history, currentTurnStartIndex);
                            String summary = llmService.generateSummaryFromHistory(currentTurnHistory);
                            if (summary == null || summary.isBlank()) {
                                summary = "已为您处理完成，如需进一步帮助请告诉我～";
                            }
                            summary = cleanFinalResponse(summary);
                            log.info("重复调用兜底总结完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
                            conversationMemory.addAssistantMessage(userId, summary);
                            return finishWith(flowId, startTime, stepHolder[0], userId,
                                    buildAgentResponse(summary, lastToolResult, userId));
                        }

                        // 注入提示让 LLM 直接出最终回复或处理尚未完成的子任务
                        String dupMsg = "【系统提示】工具 " + llmToolCall.getToolName() +
                                " 已用相同参数执行过，不要重复调用。\n" +
                                "请立刻切换到其他尚未完成的任务继续执行（例如天气查询、其他子任务），" +
                                "或如果所有任务都已执行完毕，直接生成最终回复给用户（按已完成/未完成/需询问分类说明）。";
                        history.add(ConversationMemory.Message.builder()
                                .role("system")
                                .content(dupMsg)
                                .build());
                        continue;
                    } else {
                        // 命中新工具，重置重复计数
                        duplicateCallCount = 0;
                    }

                    // ---------- 状态流：正在调用工具 ----------
                    eventPublisher.publishAgentStatusWithTool(userId, AgentStatus.CALLING_TOOL, flowId, nextStep.get(),
                            llmToolCall.getToolName(), AgentStatusMapper.toChinese(llmToolCall.getToolName()),
                            AgentStatusMapper.extractToolDetail(llmToolCall.getToolName(), llmToolCall.getArguments()));

                    // 执行工具并落盘
                    log.info("LLM决定调用工具({}/{}) : {}", toolCallCount + 1, MAX_TOOL_CALLS, llmToolCall.getToolName());
                    ToolCall toolCall = ToolCall.of(llmToolCall.getToolName(), llmToolCall.getArguments());
                    String toolResult = executeToolCall(userId, toolCall, userInput);
                    toolCallCount++;
                    executedSignatures.add(signature);
                    toolHistory.add(llmToolCall.getToolName());
                    log.info("工具调用记录: {} → {}", toolCallCount, llmToolCall.getToolName());

                    // ---------- 状态流：工具执行完成（返回结果）----------
                    eventPublisher.publishAgentStatusWithTool(userId, AgentStatus.TOOL_RESULT, flowId, nextStep.get(),
                            llmToolCall.getToolName(), AgentStatusMapper.toChinese(llmToolCall.getToolName()),
                            AgentStatusMapper.formatToolResultBrief(toolResult));

                    conversationMemory.addToolCall(userId, llmToolCall.getToolName(), llmToolCall.getArguments());

                    // 从工具返回 JSON 中解析 success 字段，写入 tool_result 的成功标记
                    boolean toolSuccess = true;
                    try {
                        Map<String, Object> resultMap = objectMapper.readValue(toolResult, Map.class);
                        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
                            toolSuccess = false;
                            log.warn("工具执行失败: {}", resultMap.get("error"));
                        }
                    } catch (Exception e) {
                        // 解析失败视为成功（DTO 结构或非 JSON 字符串）
                    }
                    conversationMemory.addToolResult(userId, llmToolCall.getToolName(), toolResult, toolSuccess);

                    // 天气查询工具链引导：get_user_preferences 返回 city 后，显式提示 LLM 调用 query_weather_now
                    if ("get_user_preferences".equals(llmToolCall.getToolName())) {
                        try {
                            Map<String, Object> prefMap = objectMapper.readValue(toolResult, Map.class);
                            Object city = prefMap.get("city");
                            if (city != null && !city.toString().isBlank() && Boolean.TRUE.equals(prefMap.get("success"))) {
                                String weatherHint = "已获取用户默认城市：" + city + "。如果用户在查询天气，请立即调用 query_weather_now(location=\"" + city + "\") 查询实时天气，不要再重复调用 get_user_preferences。";
                                history.add(ConversationMemory.Message.builder()
                                        .role("system")
                                        .content(weatherHint)
                                        .build());
                                log.info("注入天气查询引导: userId={}, city={}", userId, city);
                            }
                        } catch (Exception e) {
                            // 解析失败忽略
                        }
                    }

                    // 含 sessionId → 返回确认卡片响应（create_reminder / create_recurring_reminder 场景）
                    AgentResponse confirmationResponse = buildConfirmationResponse(toolResult, userId);
                    if (confirmationResponse != null) {
                        log.info("工具结果包含sessionId，构建CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                        // 把 LLM 引导语落盘，避免"卡片下了没文字"造成会话断层
                        if (confirmationResponse.getContent() != null && !confirmationResponse.getContent().isBlank()) {
                            conversationMemory.addAssistantMessage(userId, confirmationResponse.getContent());
                        }
                        // ---------- 状态流：生成最终回复（确认卡片）----------
                        eventPublisher.publishAgentStatus(userId, AgentStatus.GENERATING_REPLY, flowId, nextStep.get());
                        return finishWith(flowId, startTime, stepHolder[0], userId, confirmationResponse);
                    }

                    // shouldStop=true 标记任务完成（周期提醒会用到）；若附带 sessionId 走确认卡片
                    if (isTaskCompleted(toolResult)) {
                        log.info("工具结果标记任务完成（shouldStop=true），结束循环: toolName={}", llmToolCall.getToolName());
                        // ---------- 状态流：生成最终回复 ----------
                        eventPublisher.publishAgentStatus(userId, AgentStatus.GENERATING_REPLY, flowId, nextStep.get());
                        AgentResponse confirmResp = buildConfirmationResponse(toolResult, userId);
                        if (confirmResp != null) {
                            log.info("shouldStop分支二次检测到sessionId，返回CONFIRMATION响应: toolName={}", llmToolCall.getToolName());
                            if (confirmResp.getContent() != null && !confirmResp.getContent().isBlank()) {
                                conversationMemory.addAssistantMessage(userId, confirmResp.getContent());
                            }
                            return finishWith(flowId, startTime, stepHolder[0], userId, confirmResp);
                        }
                        conversationMemory.addAssistantMessage(userId, "周期提醒确认卡片已生成，请确认。");
                        // 兜底手工构造 CONFIRMATION，避免误返回 TEXT 导致确认卡片丢失
                        try {
                            Map<String, Object> resultMap = objectMapper.readValue(
                                    toolResult instanceof String ? (String) toolResult : objectMapper.writeValueAsString(toolResult),
                                    Map.class);
                            String sessionId = (String) resultMap.get("sessionId");
                            if (sessionId != null) {
                                log.warn("shouldStop分支手工构建CONFIRMATION响应: sessionId={}", sessionId);
                                String title = resultMap.get("title") != null ? String.valueOf(resultMap.get("title")) : "提醒";
                                String remindTime = resultMap.get("remindTime") != null ? String.valueOf(resultMap.get("remindTime")) : null;
                                String location = resultMap.get("location") != null ? String.valueOf(resultMap.get("location")) : null;
                                Map<String, Object> confirmData = new HashMap<>();
                                confirmData.put("title", title);
                                confirmData.put("remindTime", remindTime);
                                confirmData.put("location", location);
                                return finishWith(flowId, startTime, stepHolder[0], userId,
                                        AgentResponse.confirmation(sessionId, "📅 周期提醒请确认：", confirmData));
                            }
                        } catch (Exception e) {
                            log.warn("shouldStop分支解析失败: {}", e.getMessage());
                        }
                        return finishWith(flowId, startTime, stepHolder[0], userId,
                                buildAgentResponse("周期提醒确认卡片已生成，请确认。", toolResult, userId));
                    }

                    // 工具失败 → 不退出，让下一轮 LLM 拿到失败结果后自行降级
                    if (!toolSuccess) {
                        log.info("工具执行失败，继续循环让LLM处理降级结果");
                    }

                    lastToolResult = toolResult;
                }

                // 刷新历史，供下一轮思考使用
                history = conversationMemory.getHistory(userId);
            }

            // 达到 MAX_ITERATIONS：调用"本次对话切片"的兜底总结，给出已完成/未完成/需询问三类结果
            log.warn("达到最大迭代次数 {}，触发兜底总结（工具调用次数: {}）", MAX_ITERATIONS, toolCallCount);

            // ---------- 状态流：正在生成最终回复（兜底总结）----------
            eventPublisher.publishAgentStatus(userId, AgentStatus.GENERATING_REPLY, flowId, nextStep.get());

            log.info("Agent执行完成: userId={}, 总步骤={}, 调用工具={}", userId, iteration, toolHistory);
            List<Message> currentTurnHistory = extractCurrentTurnHistory(history, currentTurnStartIndex);
            String finalReply = llmService.generateSummaryFromHistory(currentTurnHistory);
            if (finalReply == null || finalReply.isBlank()) {
                // 兜底总结也失败 → 降级到清理最后一行响应；再不行给友好提示
                finalReply = cleanFinalResponse(llmResponse);
                if (finalReply == null || finalReply.isBlank()
                        || finalReply.contains("{\"tool_call\"")) {
                    finalReply = "当前请求处理中步骤过多，请稍后重试或换一种方式描述。";
                }
            } else {
                finalReply = cleanFinalResponse(finalReply);
            }
            conversationMemory.addAssistantMessage(userId, finalReply);
            return finishWith(flowId, startTime, stepHolder[0], userId,
                    buildAgentResponse(finalReply, lastToolResult, userId));

        } catch (Exception e) {
            log.error("Agent处理请求失败: userId={}, error={}", userId, e.getMessage(), e);
            return finishWithError(flowId, startTime, stepHolder[0], userId, "处理请求时发生错误: " + e.getMessage());
        }
    }

    // 从完整历史切出"本次对话"的消息切片（startIndex 起算）。
    // 兜底总结只看本次请求+工具结果，不把之前轮次的对话拉进来，防止幻觉
    private List<Message> extractCurrentTurnHistory(List<Message> fullHistory, int startIndex) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        if (startIndex < 0 || startIndex >= fullHistory.size()) {
            return new java.util.ArrayList<>(fullHistory);
        }
        return new java.util.ArrayList<>(fullHistory.subList(startIndex, fullHistory.size()));
    }

    // 执行一次工具调用，含 3 次重试：
    // - 第 1 次原始参数；
    // - 第 2 次去除地点末尾"市"；
    // - 第 3 次替换用户默认城市；
    // 失败时构建 fallback JSON 供 LLM 拿到友好描述
    public String executeToolCall(Long userId, ToolCall toolCall, String userInput) {
        String toolName = toolCall.getToolName();
        Map<String, Object> arguments = new HashMap<>(toolCall.getArguments());
        arguments.put("userId", userId);
        // 传递用户原始输入，供 create_reminder 工具检测"提醒我"等倒计时场景关键词
        if (userInput != null && !userInput.isBlank()) {
            arguments.put("originalInput", userInput);
        }

        int maxRetries = 3;
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            attempt++;
            try {
                // 工具名规范化：去空白+小写+下划线横杠互换，兼容写法误差
                String normToolName = toolName == null ? "" : toolName.trim().toLowerCase();
                String actualToolName = toolRegistry.resolveToolName(normToolName);
                if (actualToolName == null) {
                    String swapped = normToolName.replace('_', '-');
                    if (!swapped.equals(normToolName)) {
                        actualToolName = toolRegistry.resolveToolName(swapped);
                    }
                    if (actualToolName == null) {
                        String errorMsg = "工具不存在: " + toolName + " (可用工具: " + toolRegistry.listToolNames() + ")";
                        log.warn(errorMsg);
                        return "{\"success\": false, \"error\": \"" + errorMsg.replace("\"", "'") + "\"}";
                    }
                }
                if (!actualToolName.equals(normToolName)) {
                    log.debug("工具名规范化匹配: 请求={} -> 实际注册名={}", toolName, actualToolName);
                }

                log.info("执行工具调用: userId={}, toolName={}, 尝试 {}/{}", userId, actualToolName, attempt, maxRetries);

                // 按照重试次数应用参数降级
                Map<String, Object> adjustedArgs = prepareArgumentsForRetry(arguments, attempt);

                Object result = toolRegistry.executeTool(actualToolName, adjustedArgs);

                // Map 返回值：直接序列化，同时探查是否带 sessionId 需要走确认流程
                String resultJson;
                if (result instanceof Map) {
                    resultJson = serializeResult(result);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resultMap = (Map<String, Object>) result;
                        if (Boolean.TRUE.equals(resultMap.get("success")) && resultMap.containsKey("sessionId")) {
                            log.info("工具执行结果包含sessionId，标记为需要确认: toolName={}, sessionId={}",
                                    toolName, resultMap.get("sessionId"));
                        }
                    } catch (Exception e) {
                        log.warn("解析工具结果sessionId失败: {}", e.getMessage());
                    }
                } else {
                    // DTO 等非 Map 返回：包装一层 data 字段
                    resultJson = "{\"success\": true, \"data\": " + serializeResult(result) + "}";
                }

                log.info("工具执行成功: userId={}, toolName={}, 尝试次数={}", userId, toolName, attempt);
                return resultJson;

            } catch (Exception e) {
                lastException = e;
                log.warn("工具执行失败: userId={}, toolName={}, 尝试 {}/{}, error={}",
                        userId, toolName, attempt, maxRetries, e.getMessage());

                if (attempt >= maxRetries) {
                    break;
                }

                // 指数退避等待：100ms → 200ms → 400ms
                try {
                    long waitMs = (long) (Math.pow(2, attempt - 1) * 100);
                    log.debug("工具调用失败，等待 {}ms 后重试", waitMs);
                    Thread.sleep(waitMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        log.error("工具执行全部失败: userId={}, toolName={}, error={}", userId, toolName, errorMsg);
        return buildFallbackResult(toolName, errorMsg);
    }

    // 按重试轮次调整参数：attempt=1 原样，=2 去"市"，=3 用用户默认城市
    private Map<String, Object> prepareArgumentsForRetry(Map<String, Object> originalArgs, int attempt) {
        Map<String, Object> args = new HashMap<>(originalArgs);

        if (attempt == 1) {
            return args;
        }

        if (attempt >= 2) {
            if (args.containsKey("location")) {
                String location = (String) args.get("location");
                if (location != null && location.endsWith("市")) {
                    String newLocation = location.substring(0, location.length() - 1);
                    args.put("location", newLocation);
                    log.debug("参数降级(第{}次): 去除'市'后缀: {} → {}", attempt, location, newLocation);
                }
            }
        }

        if (attempt >= 3) {
            if (args.containsKey("userId")) {
                Long userId = (Long) args.get("userId");
                try {
                    Map<String, Object> preferences = userService.getUserPreferences(userId);
                    String defaultCity = (String) preferences.get("city");
                    if (defaultCity != null && !defaultCity.isBlank()) {
                        args.put("location", defaultCity);
                        log.debug("参数降级(第{}次): 使用用户默认城市: {}", attempt, defaultCity);
                    }
                } catch (Exception e) {
                    log.warn("获取用户默认城市失败: userId={}, error={}", userId, e.getMessage());
                }
            }
        }

        return args;
    }

    // 工具全失败时构造 friendlyMessage，按工具类型给出差异化提示，便于 LLM 后续友好转述
    private String buildFallbackResult(String toolName, String errorMessage) {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("success", false);
        fallback.put("fallback", true);

        String friendlyMessage = switch (toolName) {
            case "query_weather_now", "query_weather_forecast" ->
                    "天气查询失败，可能因为城市名不正确或网络问题。建议您检查城市名，或稍后再试。";
            case "create_reminder" ->
                    "创建提醒失败，请确认提醒信息是否正确，稍后再试。";
            case "create_recurring_reminder" ->
                    "创建周期提醒失败，请确认日期范围和时间是否正确，稍后再试。";
            case "update_reminder" ->
                    "修改提醒失败，请确认提醒是否存在，稍后再试。";
            case "cancel_reminder" ->
                    "取消提醒失败，请确认提醒是否存在，稍后再试。";
            case "cancel_reminders_by_date" ->
                    "取消提醒失败，请确认日期和关键词是否正确，稍后再试。";
            case "get_reminder_detail" ->
                    "查询提醒详情失败，请确认提醒ID是否正确，稍后再试。";
            case "get_user_preferences" ->
                    "获取用户设置失败，请稍后再试。";
            case "set_user_preferences" ->
                    "保存用户设置失败，请稍后再试。";
            case "get_current_time" ->
                    "获取当前时间失败，请稍后再试。";
            case "calculate_date" ->
                    "日期计算失败，请检查输入格式，稍后再试。";
            case "get_location" ->
                    "获取位置信息失败，请稍后再试。";
            default ->
                    "操作失败，请稍后再试。";
        };

        fallback.put("message", friendlyMessage);
        fallback.put("originalError", errorMessage);

        try {
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception e) {
            log.warn("序列化降级结果失败: {}", e.getMessage());
            return "{\"success\":false,\"fallback\":true,\"message\":\"操作失败，请稍后再试。\"}";
        }
    }

    // 对象→JSON 序列化：null/基本类型/Map/复杂对象统一处理，避免 toString 带来的引号错位
    private String serializeResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof String) {
            return "\"" + result.toString().replace("\"", "\\\"") + "\"";
        }
        if (result instanceof Number || result instanceof Boolean) {
            return result.toString();
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("序列化结果失败: {}", e.getMessage());
            return "\"" + result.getClass().getName() + "\"";
        }
    }

    // 构建最终 AgentResponse：优先 CONFIRMATION（有 sessionId），其次 TEXT；带 JSON 片段的会被 sanitize 清理
    public AgentResponse buildAgentResponse(String llmOutput, Object toolResult, Long userId) {
        log.debug("构建Agent响应: llmOutput={}, toolResult={}", llmOutput, toolResult);

        ToolCall toolCall = llmService.parseToolCall(llmOutput);
        if (toolCall != null) {
            return AgentResponse.toolCall(toolCall.getToolName(), toolCall.getArguments());
        }

        if (toolResult != null && userId != null) {
            AgentResponse confirmationResponse = buildConfirmationResponse(toolResult, userId);
            if (confirmationResponse != null) {
                log.info("工具结果包含sessionId，返回CONFIRMATION类型响应");
                if (confirmationResponse.getContent() != null && !confirmationResponse.getContent().isBlank()) {
                    conversationMemory.addAssistantMessage(userId, confirmationResponse.getContent());
                }
                return confirmationResponse;
            }
        }

        if (llmOutput != null && llmOutput.contains("error")) {
            return AgentResponse.error(llmOutput);
        }

        String cleanOutput = sanitizeLlmOutput(llmOutput);

        AgentResponse response = AgentResponse.text(cleanOutput);
        if (toolResult != null) {
            response.setToolResult(toolResult);
        }
        return response;
    }

    // 判断 LLM 输出中是否还残留未被 parse 成功的 tool_call / arguments / tool_name JSON 片段
    private boolean containsToolCallFragment(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        return output.contains("\"tool_call\"") || output.contains("'tool_call'")
                || output.contains("\"tool_name\"") || output.contains("\"arguments\"");
    }

    // 生成工具调用签名：跳过 userId，并把参数按 key 排序后拼接 value，
    // 保证 {A=1,B=2} 与 {B=2,A=1} 被视为同一次调用
    private String toolCallSignature(ToolCall toolCall) {
        if (toolCall == null) {
            return "";
        }
        java.util.TreeMap<String, String> sortedArgs = new java.util.TreeMap<>();
        if (toolCall.getArguments() != null) {
            for (Map.Entry<String, Object> e : toolCall.getArguments().entrySet()) {
                if (!"userId".equalsIgnoreCase(e.getKey())) {
                    sortedArgs.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        }
        return toolCall.getToolName() + "|" + sortedArgs;
    }

    // 判断 LLM 是否在"中文演戏"：包含「工具调用:」或「工具执行结果:」等话术但无真正 JSON
    private boolean isActedFormat(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return output.contains("工具调用:") || output.contains("工具调用：")
                || output.contains("工具执行结果:") || output.contains("工具执行结果：");
    }

    // 最终回复出口清理：
    // 1) 删除演戏段整行与内联演戏串；
    // 2) 剥离过程语前缀（"好的我帮你…"等，最多循环剥 3 轮）；
    // 3) 规整空行和首尾空白；
    // 4) 整段被清空时给兜底句，避免空回复
    private String cleanFinalResponse(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        String text = output;

        // 整行演戏段删除
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { sb.append('\n'); continue; }
            if (trimmed.startsWith("工具调用:") || trimmed.startsWith("工具调用：")
                    || trimmed.startsWith("工具执行结果:") || trimmed.startsWith("工具执行结果：")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        text = sb.toString();

        // 内联演戏串清理（到行末）
        text = text.replaceAll("(?m)(工具调用|工具执行结果)[:：][^\n]*", "");
        text = text.replaceAll("(?m)参数[:：][ \t]*\\{.*\\}[ \t]*", "");

        // 过程语前缀：按模式循环剥离，最多 3 轮防止遗漏嵌套前缀
        java.util.regex.Pattern[] processPrefixes = new java.util.regex.Pattern[] {
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*好[的啦的嘛]?[，,\\s\\.。～~\\-!！?？:：/]*我(先)?(这就|现在|马上|立刻)?(帮你|来帮你|给你|为你)(查一下|查|看看|看一下|处理|修改|调整|设置|记录|记|存|创建|建|添加|加|取消|删除|删|更新|改|做|安排|弄|完成)[^，,。.！!~\n～]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*让我(先)?(帮你|来帮你|给你|为你)(查一下|查|看看|看一下|处理|修改|调整|设置|记录|记|存)[^，,。.！!\n～]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*我先(来)?(帮你|处理|确认|看看|查一下)[^，,。.！!～\n]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE),
                java.util.regex.Pattern.compile("^[\\s～~\\-。,，.!！?？、:：]*(这就|马上|立刻|立即)(就)?(帮你|来|开始|处理)[^，,。.！!～\n]*[~～。,.，!！\\s]*", java.util.regex.Pattern.UNICODE_CASE)
        };
        int stripRound = 0;
        while (stripRound < 3) {
            boolean changed = false;
            for (java.util.regex.Pattern p : processPrefixes) {
                java.util.regex.Matcher m = p.matcher(text);
                if (m.find() && m.start() == 0) {
                    String before = text;
                    text = m.replaceFirst("");
                    if (!text.equals(before)) changed = true;
                }
            }
            if (!changed) break;
            stripRound++;
        }

        // 只剩"好的 ～"这类空话 → 清空走兜底
        if (text.trim().matches("^(好[的啦嘛]?[~～\\s。,.，!！?？:：]*){1,3}$")) {
            text = "";
        }

        // 收尾空行/首尾空白规范
        text = text.replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (text.isEmpty()) {
            return "已处理完成，如需进一步帮助请随时告诉我。";
        }

        return text;
    }

    // LLM 输出过滤器：含 tool_call 片段返回友好提示，其它交给 cleanFinalResponse 做过程语清理
    private String sanitizeLlmOutput(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        if (containsToolCallFragment(output)) {
            log.warn("过滤LLM输出中的JSON片段: {}", output.substring(0, Math.min(output.length(), 200)));
            return "操作失败，请重试或换一种方式描述。";
        }
        return cleanFinalResponse(output);
    }

    // 从工具执行结果构造 CONFIRMATION 响应：
    // - 解析 sessionId/title/remindTime/location/isRecurring；
    // - 额外调用 LLM 生成一句"提醒已为你解析，请确认"引导语，保证卡片+文字一起下不突兀
    private AgentResponse buildConfirmationResponse(Object toolResult, Long userId) {
        if (toolResult == null) {
            return null;
        }

        try {
            Map<String, Object> resultMap;
            if (toolResult instanceof String) {
                resultMap = objectMapper.readValue((String) toolResult, Map.class);
            } else if (toolResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) toolResult;
                resultMap = castedMap;
            } else {
                log.debug("buildConfirmationResponse: toolResult 类型 {} 不是 String/Map，跳过",
                        toolResult != null ? toolResult.getClass().getSimpleName() : "null");
                return null;
            }

            boolean success = Boolean.TRUE.equals(resultMap.get("success"));
            boolean hasSessionId = resultMap.containsKey("sessionId") && resultMap.get("sessionId") != null;
            log.debug("buildConfirmationResponse: success={}, hasSessionId={}, keys={}",
                    success, hasSessionId, resultMap.keySet());

            if (!success || !hasSessionId) {
                log.debug("buildConfirmationResponse: 条件不满足（success={}，hasSessionId={}），返回null", success, hasSessionId);
                return null;
            }

            String sessionId = (String) resultMap.get("sessionId");
            String title = (String) resultMap.get("title");
            String remindTime = (String) resultMap.get("remindTime");
            String location = (String) resultMap.get("location");
            boolean isRecurring = Boolean.TRUE.equals(resultMap.get("isRecurring"));
            log.info("buildConfirmationResponse: 检测到确认会话 sessionId={}, title={}, isRecurring={}", sessionId, title, isRecurring);

            Map<String, Object> confirmationData = new HashMap<>();
            confirmationData.put("title", title);
            confirmationData.put("remindTime", remindTime);
            confirmationData.put("location", location);
            if (resultMap.containsKey("endTime")) {
                confirmationData.put("endTime", resultMap.get("endTime"));
            }
            if (resultMap.containsKey("priority")) {
                confirmationData.put("priority", resultMap.get("priority"));
            }
            if (isRecurring) {
                confirmationData.put("isRecurring", true);
                // 周期提醒卡片不超时
                confirmationData.put("noTimeout", true);
            }

            // LLM 生成卡片前的引导语
            String llmLead = buildConfirmationLead(userId, title, remindTime, location, isRecurring);

            return AgentResponse.confirmation(sessionId, llmLead, confirmationData);
        } catch (Exception e) {
            log.warn("解析工具结果构建CONFIRMATION响应失败: {}", e.getMessage());
            return null;
        }
    }

    // 生成确认卡片前的自然语言引导语（纯文本、不允许再出工具调用）；
    // LLM 不可用时走默认文案兜底
    private String buildConfirmationLead(Long userId, String title, String remindTime,
                                         String location, boolean isRecurring) {
        // 兜底文案：根据提醒类型使用不同风格
        String defaultText;
        if (isRecurring) {
            defaultText = "好啦，周期提醒已准备好，帮你设为" + (title != null ? "「" + title + "」" : "") +
                    (remindTime != null ? "，时间" + remindTime : "") + "，在下方卡片确认一下吧～";
        } else {
            defaultText = "好啦，" + (title != null ? "「" + title + "」" : "提醒") +
                    (remindTime != null ? "已设为" + remindTime : "") + "，在下方卡片确认一下吧～";
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("请根据以下提醒信息，生成一句自然、口语化的引导语，引导用户在确认卡片上确认或取消。\n\n");
            sb.append("要求：\n");
            sb.append("- 直接用朋友的语气告诉用户你创建了什么提醒，包含标题和时间\n");
            sb.append("- 不要输出任何JSON、工具调用、演戏格式\n");
            sb.append("- 不要使用'正在生成'、'请稍候'等过程性语言\n");
            sb.append("- 50字以内，自然友好\n");
            sb.append("- 日常提醒轻松随意，重要/周期提醒更认真清晰\n\n");

            sb.append("提醒标题：").append(title == null ? "(未命名)" : title).append("\n");
            sb.append("提醒时间：").append(remindTime == null ? "(未设置)" : remindTime).append("\n");
            if (location != null && !location.isBlank()) {
                sb.append("提醒地点：").append(location).append("\n");
            }
            if (isRecurring) {
                sb.append("类型：周期提醒（按周期重复）\n");
            }

            String hint = sb.toString();
            List<ToolDefinition> noTools = List.of();
            List<Message> history = conversationMemory.getHistory(userId);

            // 使用纯文本对话（不带工具定义），防止 LLM 误判为需要调用工具
            String reply = llmService.chatWithoutTools(hint, history);

            // 检测 LLM 是否返回了 JSON/工具调用，若是则重试一次
            if (reply != null && reply.contains("tool_call")) {
                log.warn("引导语生成返回工具调用JSON，重试一次");
                String retryHint = "严格禁止输出JSON或工具调用。请用一句话自然地告诉用户：已创建提醒「"
                        + (title != null ? title : "提醒") + "」，"
                        + (remindTime != null ? remindTime : "") + "，请在卡片上确认。";
                reply = llmService.chatWithoutTools(retryHint, history);
            }

            reply = sanitizeLlmOutput(reply);
            if (reply == null || reply.isBlank() || reply.contains("tool_call")) {
                log.warn("引导语LLM输出无效，使用兜底文案");
                return defaultText;
            }
            return reply;
        } catch (Exception e) {
            log.warn("生成确认卡片引导语失败，使用兜底文案: {}", e.getMessage());
            return defaultText;
        }
    }

    // 构建兜底引导语模板，确保 LLM 完全失败时仍有有意义的文案
    private String buildDefaultLead(String title, String remindTime, String location, boolean isRecurring) {
        String titlePart = title != null ? "「" + title + "」" : "提醒";
        String timePart = remindTime != null ? remindTime : "";
        String locPart = (location != null && !location.isBlank()) ? "，地点" + location : "";

        if (isRecurring) {
            return "📅 已为你设置周期提醒" + titlePart + timePart + locPart + "，请在下方卡片确认～";
        } else {
            return "✨ 好的，已为你创建提醒" + titlePart + "，" + timePart + locPart + "会提醒你哦～";
        }
    }

    // 判断工具返回是否标记任务完成（用于 create_recurring_reminder 等长链路场景）
    private boolean isTaskCompleted(Object toolResult) {
        if (toolResult == null) {
            return false;
        }
        try {
            Map<String, Object> resultMap;
            if (toolResult instanceof String) {
                resultMap = objectMapper.readValue((String) toolResult, Map.class);
            } else if (toolResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castedMap = (Map<String, Object>) toolResult;
                resultMap = castedMap;
            } else {
                return false;
            }
            return Boolean.TRUE.equals(resultMap.get("shouldStop"))
                    || Boolean.TRUE.equals(resultMap.get("taskCompleted"));
        } catch (Exception e) {
            return false;
        }
    }

    // 获取指定用户的完整会话历史列表（由 ConversationMemory 承载，含过期清理和裁剪逻辑）
    public List<Message> getHistory(Long userId) {
        return conversationMemory.getHistory(userId);
    }

    // 清空指定用户的会话历史（用户主动重置或退出登录时调用）
    public void clearHistory(Long userId) {
        conversationMemory.clear(userId);
    }

    // 返回当前 Agent 可用的全部工具 Schema 列表，供调试接口展示
    public List<ToolDefinition> getAvailableTools() {
        return toolRegistry.getToolDefinitions();
    }

    // 返回已注册工具的总数量，用于健康检查和启动自检日志
    public int getToolCount() {
        return toolRegistry.getToolCount();
    }

    // 监听提醒创建事件：写入 tool_result（视为 create_reminder 成功），便于后续历史中查看
    @EventListener
    public void onReminderCreated(ReminderCreatedEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒创建事件: userId={}, title={}", event.getUserId(), event.getTitle());
        try {
            String resultText = "提醒创建请求已发送: " + event.getTitle() + "，时间: " + event.getRemindTime();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, true);
        } catch (Exception e) {
            log.error("处理提醒创建事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    // 监听提醒确认事件：落结果后再让 LLM 生成最终回复
    @EventListener
    public void onReminderConfirmed(ReminderConfirmedEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒确认事件: userId={}, reminderId={}", event.getUserId(), event.getReminderId());
        try {
            String resultText = "用户已确认提醒创建: " + event.getTitle();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, true);

            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
            List<Message> history = conversationMemory.getHistory(event.getUserId());
            String llmResponse = llmService.chatWithTools(resultText, tools, history);
            conversationMemory.addAssistantMessage(event.getUserId(), llmResponse);
        } catch (Exception e) {
            log.error("处理提醒确认事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    // 监听提醒取消事件：落结果后再让 LLM 生成最终回复
    @EventListener
    public void onReminderCancelled(ReminderCancelledEvent event) {
        if (!agentEnabled) {
            return;
        }
        log.info("收到提醒取消事件: userId={}, reminderId={}", event.getUserId(), event.getReminderId());
        try {
            String resultText = "用户已取消提醒: " + event.getTitle();
            conversationMemory.addToolResult(event.getUserId(), "create_reminder", resultText, false);

            List<ToolDefinition> tools = toolRegistry.getToolDefinitions();
            List<Message> history = conversationMemory.getHistory(event.getUserId());
            String llmResponse = llmService.chatWithTools(resultText, tools, history);
            conversationMemory.addAssistantMessage(event.getUserId(), llmResponse);
        } catch (Exception e) {
            log.error("处理提醒取消事件失败: userId={}, error={}", event.getUserId(), e.getMessage(), e);
        }
    }

    // 用户确认/取消卡片（或 Scheduler 超时自动确认）都会走这里：
    // 1. 写 create_reminder tool_result；
    // 2. 以"工具结果 + 空工具列表"再调一次 LLM，让它生成最终自然语言回复；
    // 3. LLM 失败用默认文案兜底
    // contextData：周期提醒场景传 createResult（含 successCount/skippedCount/overwrittenCount/totalDays），普通场景传 null
    public AgentResponse handleConfirmationResult(Long userId, String sessionId, boolean confirmed, Map<String, Object> contextData) {
        log.info("处理确认结果: userId={}, sessionId={}, confirmed={}, contextData={}", userId, sessionId, confirmed, contextData);

        if (!agentEnabled) {
            log.debug("Agent未启用，跳过处理确认结果");
            return AgentResponse.text(confirmed ? "已确认" : "已取消");
        }

        String fallback;
        if (confirmed) {
            fallback = "好的，提醒已为你创建 ✨";
        } else {
            fallback = "好的，已为你取消啦～";
        }

        try {
            String resultText;
            if (confirmed) {
                StringBuilder sb = new StringBuilder();
                sb.append("用户已确认提醒创建。");

                // 周期提醒上下文：包含具体创建数量、跳过冲突数、覆盖数量等
                if (contextData != null) {
                    Object successCount = contextData.get("successCount");
                    Object skippedCount = contextData.get("skippedCount");
                    Object overwrittenCount = contextData.get("overwrittenCount");
                    Object totalDays = contextData.get("totalDays");

                    if (successCount != null && skippedCount != null) {
                        sb.append(" 实际成功创建了 ").append(successCount).append(" 天");
                        int skipped = ((Number) skippedCount).intValue();
                        if (skipped > 0) {
                            sb.append("，跳过了 ").append(skipped).append(" 天冲突");
                        }
                        sb.append("。");
                    } else if (successCount != null && overwrittenCount != null) {
                        sb.append(" 实际成功创建了 ").append(successCount).append(" 天");
                        int overwritten = ((Number) overwrittenCount).intValue();
                        if (overwritten > 0) {
                            sb.append("，覆盖了 ").append(overwritten).append(" 条冲突提醒");
                        }
                        sb.append("。");
                    } else if (successCount != null) {
                        sb.append(" 实际成功创建了 ").append(successCount).append(" 天。");
                    } else if (totalDays != null) {
                        sb.append(" 共创建 ").append(totalDays).append(" 天。");
                    }
                }

                sb.append(" 请根据这个结果回复一句自然友好的确认话术，50字内，适当使用emoji。");
                resultText = sb.toString();
            } else {
                resultText = "用户已取消提醒创建，请回复一句自然友好的取消话术，30字内，语气轻松。";
            }

            conversationMemory.addToolResult(userId, "create_reminder", resultText, confirmed);

            List<ToolDefinition> noTools = List.of();
            List<Message> history = conversationMemory.getHistory(userId);

            // 使用 chatWithoutTools（纯文本对话，不注入工具定义），防止 LLM 误判为需要调用工具
            String llmResponse = llmService.chatWithoutTools(resultText, history);
            String finalReply = sanitizeLlmOutput(llmResponse);

            // 检测是否仍返回了 JSON/tool_call，若是则使用兜底文案
            if (finalReply == null || finalReply.isBlank() || finalReply.contains("tool_call")) {
                log.warn("确认结果LLM输出无效，使用兜底文案: confirmed={}", confirmed);
                finalReply = fallback;
            }

            conversationMemory.addAssistantMessage(userId, finalReply);
            log.info("确认结果处理完成: userId={}, confirmed={}, reply={}", userId, confirmed, finalReply);
            return AgentResponse.text(finalReply);

        } catch (Exception e) {
            log.error("处理确认结果失败: userId={}, sessionId={}, error={}", userId, sessionId, e.getMessage(), e);
            conversationMemory.addAssistantMessage(userId, fallback);
            return AgentResponse.text(fallback);
        }
    }

    // 返回 Agent 功能是否启用（对应配置项 agent.enabled），关闭时事件监听等能力会被跳过
    public boolean isAgentEnabled() {
        return agentEnabled;
    }
}
