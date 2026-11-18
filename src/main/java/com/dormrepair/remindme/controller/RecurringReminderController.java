package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.entity.RecurringReminder;
import com.dormrepair.remindme.enums.RepeatType;
import com.dormrepair.remindme.service.RecurringReminderService;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 周期提醒接口控制器。统一前缀 /api/recurring-reminders，提供「课程表、每日打卡」类重复提醒模板的查询、编辑、取消、暂停、恢复 5 项能力
@RestController
@RequestMapping("/api/recurring-reminders")
@RequiredArgsConstructor
public class RecurringReminderController {

    // 周期提醒服务：模板 CRUD、状态流转（ACTIVE→PAUSED→CANCELLED）、按用户过滤等核心逻辑
    private final RecurringReminderService recurringReminderService;

    // 列表接口：按用户返回其所有 ACTIVE/PAUSED/CANCELLED 状态的周期提醒模板，供调用方查询使用
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam Long userId) {
        try {
            List<RecurringReminder> reminders = recurringReminderService.getRecurringRemindersByUser(userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", reminders);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 详情接口：按模板 id + 用户 id 取单条详情；不存在返回 404，存在返回模板完整字段
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            return recurringReminderService.getRecurringReminderById(id, userId)
                    .map(reminder -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("success", true);
                        result.put("data", reminder);
                        return ResponseEntity.ok(result);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 更新接口：修改周期提醒模板的标题、时间、起止范围、重复类型、地点、备注、优先级；修改后已生成的后续实例会按新模板重建
    // body 字段：title/remindTime/startDate/endDate/repeatType/repeatDays/location/note/priority 均为可选
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.get("title");
            String remindTime = (String) body.get("remindTime");
            String startDateStr = (String) body.get("startDate");
            String endDateStr = (String) body.get("endDate");
            String repeatTypeStr = (String) body.get("repeatType");
            String repeatDays = (String) body.get("repeatDays");
            String location = (String) body.get("location");
            String note = (String) body.get("note");
            String priority = (String) body.get("priority");

            // 字符串转 LocalDate，空值保持 null 以便底层按"不修改该字段"处理
            LocalDate startDate = startDateStr != null ? LocalDate.parse(startDateStr, TimeUtils.DATE_FORMATTER) : null;
            LocalDate endDate = endDateStr != null ? LocalDate.parse(endDateStr, TimeUtils.DATE_FORMATTER) : null;
            RepeatType repeatType = repeatTypeStr != null ? RepeatType.valueOf(repeatTypeStr) : null;

            RecurringReminder updated = recurringReminderService.updateRecurringReminder(
                    id, userId, title, remindTime, startDate, endDate, repeatType, repeatDays, location, note, priority);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已更新");
            result.put("data", updated);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 取消周期提醒：把模板状态改为 CANCELLED，后续实例不再生成；已生成的实例不会被回溯删除
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.cancelRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已取消");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 暂停周期提醒：把模板状态改为 PAUSED，未来日期的实例暂停生成；期间可随时 resume 恢复
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.pauseRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已暂停");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 恢复周期提醒：把 PAUSED 状态模板改回 ACTIVE，从当前日期起继续生成后续实例
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            recurringReminderService.resumeRecurringReminder(id, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "周期提醒已恢复");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}
