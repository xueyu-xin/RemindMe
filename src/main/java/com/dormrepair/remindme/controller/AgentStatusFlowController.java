package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.AgentStatusFlow;
import com.dormrepair.remindme.repository.AgentStatusFlowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Agent 状态流查询接口：按用户返回最近 N 条状态流（新→旧），供刷新后与聊天历史按时间序合并回放
// 数据由 AgentStatusFlowPersistListener 在每轮对话终态时落库，查询窗口与聊天历史保持对齐
@Slf4j
@RestController
@RequestMapping("/api/agent/status-flows")
@RequiredArgsConstructor
public class AgentStatusFlowController {

    private final AgentStatusFlowRepository agentStatusFlowRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 状态流列表查询：userId 必填，limit 默认 50、最大 100，返回结构与聊天历史一致（data 为新→旧倒序）
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (userId == null) {
                resp.put("success", false);
                resp.put("message", "userId 不能为空");
                return ResponseEntity.badRequest().body(resp);
            }
            // 限制最大条数，与聊天历史窗口对齐
            int actualLimit = limit != null ? Math.min(limit, 100) : 50;
            if (actualLimit <= 0) actualLimit = 50;

            Pageable pageable = PageRequest.of(0, actualLimit);
            List<AgentStatusFlow> rows = agentStatusFlowRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

            List<Map<String, Object>> data = new ArrayList<>();
            for (AgentStatusFlow row : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("flowId", row.getFlowId());
                m.put("finalStatus", row.getFinalStatus());
                m.put("totalSteps", row.getTotalSteps());
                m.put("durationMs", row.getDurationMs());
                m.put("steps", parseSteps(row.getStepsJson()));
                m.put("createdAt", row.getCreatedAt());
                data.add(m);
            }

            resp.put("success", true);
            resp.put("data", data);
            resp.put("count", data.size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("查询状态流失败: userId={}", userId, e);
            resp.put("success", false);
            resp.put("message", "查询失败");
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    // 步骤 JSON 反序列化，解析失败返回空列表不抛异常
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<?> raw = objectMapper.readValue(stepsJson, List.class);
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map) {
                    steps.add((Map<String, Object>) o);
                }
            }
            return steps;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
