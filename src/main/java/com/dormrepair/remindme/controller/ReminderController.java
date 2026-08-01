package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.request.ReminderCreateRequest;
import com.dormrepair.remindme.dto.request.ReminderUpdateRequest;
import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.dto.response.ConflictResponse;
import com.dormrepair.remindme.dto.response.ReminderResponse;
import com.dormrepair.remindme.enums.IntentType;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.DialogStatsService;
import com.dormrepair.remindme.service.LLMService;
import com.dormrepair.remindme.service.ReminderService;
import com.dormrepair.remindme.utils.TimeUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 一次性提醒接口控制器。统一前缀 /api/reminders，提供提醒的创建、列表、待办筛选、编辑、取消、冲突检测、纯解析、手动确认等能力
@Slf4j
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    // 提醒主服务：实体 CRUD、状态流转、冲突判定等核心业务逻辑
    private final ReminderService reminderService;
    // 大模型服务：/parse 接口中把自然语言输入解析成结构化字段
    private final LLMService llmService;
    // 对话统计服务：记录 parse 解析过程的对话意图，供后续指标统计
    private final DialogStatsService dialogStatsService;
    // 确认会话服务：/parse 接口解析为提醒类型时，走「用户二次确认→创建」流程
    private final ConfirmationService confirmationService;

    // 创建提醒接口（结构化表单提交方式）。确认卡片确认或编辑保存时走此入口，直接落 Reminder 表
    @PostMapping
    public ResponseEntity<Map<String, Object>> createReminder(@Valid @RequestBody ReminderCreateRequest request) {
        Map<String, Object> response = reminderService.createReminder(request);
        return ResponseEntity.ok(response);
    }

    // 我的提醒列表接口：按 userId 返回所有状态（PENDING/DONE/CANCELLED）的提醒，默认按提醒时间倒序
    @GetMapping
    public ResponseEntity<List<ReminderResponse>> getReminders(@RequestParam Long userId) {
        List<ReminderResponse> reminders = reminderService.getRemindersByUser(userId);
        return ResponseEntity.ok(reminders);
    }

    // 待办提醒列表接口：只返回状态为 PENDING 的提醒，用于今日待办场景
    @GetMapping("/pending")
    public ResponseEntity<List<ReminderResponse>> getPendingReminders(@RequestParam Long userId) {
        List<ReminderResponse> reminders = reminderService.getPendingReminders(userId);
        return ResponseEntity.ok(reminders);
    }

    // 编辑提醒接口：按提醒 id 全量更新字段；会校验 userId 是否匹配，防止越权修改
    @PutMapping("/{id}")
    public ResponseEntity<ReminderResponse> updateReminder(
            @PathVariable Long id,
            @Valid @RequestBody ReminderUpdateRequest request,
            @RequestParam Long userId) {
        ReminderResponse response = reminderService.updateReminder(id, request, userId);
        return ResponseEntity.ok(response);
    }

    // 取消提醒接口：把状态改为 CANCELLED，不是物理删除，保留记录便于后续统计展示
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> cancelReminder(
            @PathVariable Long id,
            @RequestParam Long userId) {
        reminderService.cancelReminder(id, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "提醒已取消");

        return ResponseEntity.ok(response);
    }

    // 冲突检测接口：创建/编辑提醒前先调用此接口，判断与现有提醒时间是否重叠，避免重复行程
    @PostMapping("/check-conflict")
    public ResponseEntity<ConflictResponse> checkConflict(
            @RequestParam Long userId,
            @RequestParam LocalDateTime remindTime) {
        ConflictResponse response = reminderService.checkConflict(userId, remindTime);
        return ResponseEntity.ok(response);
    }

    // 纯自然语言解析接口：把用户输入交给 LLM，返回结构化的 parsedData；
    // 仅当 intentType=REMINDER 且指定了 userId 且未显式跳过确认时，才会创建确认卡片等待用户点确认
    // request 常用字段：input(必填,用户输入)、userId(可选)、skipConfirmation(可选,true 时不走确认)
    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseReminder(@RequestBody Map<String, Object> request) {
        String userInput = (String) request.get("input");
        Long userId = request.containsKey("userId") ? Long.parseLong(String.valueOf(request.get("userId"))) : null;
        boolean skipConfirmation = request.containsKey("skipConfirmation") && (Boolean) request.get("skipConfirmation");
        
        // 调 LLM 把自然语言解析成 {标题, 时间, 地点, 优先级, 意图类型}
        LLMService.ParsedResult result = llmService.parseReminder(userInput);

        // 有 userId 时把本次对话与识别到的意图写入统计表，便于后续历史记录展示
        if (userId != null) {
            IntentType intentType = IntentType.fromString(result.intentType());
            dialogStatsService.recordDialog(userId, userInput, intentType);
        }

        // 组装统一的 parsedData，传给确认会话或直接返回；缺省提前 10 分钟、置信度 0.95
        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("title", result.title());
        parsedData.put("remindTime", result.remindTime() != null ? result.remindTime().format(TimeUtils.FORMATTER) : null);
        parsedData.put("intentType", result.intentType());
        parsedData.put("location", result.location());
        parsedData.put("priority", result.priority());
        parsedData.put("note", result.note());
        parsedData.put("advanceMinutes", 10);
        parsedData.put("confidence", 0.95);

        // 命中提醒意图时走二次确认流程：由确认会话服务生成卡片，通过 WebSocket 推送
        if ("REMINDER".equals(result.intentType()) && userId != null && !skipConfirmation) {
            ConfirmationCardResponse card = confirmationService.createSession(userId, userInput, parsedData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("needConfirmation", true);
            response.put("type", "confirmation_required");
            response.put("message", "需要确认提醒信息");
            response.put("data", card.getData());
            
            return ResponseEntity.ok(response);
        }

        // 非提醒意图或用户要求跳过确认：只返回解析后的结构化字段，不入库
        Map<String, Object> response = new HashMap<>();
        response.put("needConfirmation", false);
        response.put("title", result.title());
        response.put("remindTime", result.remindTime());
        response.put("intentType", result.intentType());
        response.put("location", result.location());
        response.put("priority", result.priority());

        return ResponseEntity.ok(response);
    }

    // 手动确认提醒接口：把提醒状态从待确认推进到 PENDING，与确认卡片的「确认」按钮等效
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReminderResponse> confirmReminder(
            @PathVariable Long id,
            @RequestParam Long userId) {
        ReminderResponse response = reminderService.confirmReminder(id, userId);
        return ResponseEntity.ok(response);
    }
}
