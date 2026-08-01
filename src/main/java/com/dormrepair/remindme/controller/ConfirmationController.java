package com.dormrepair.remindme.controller;

import com.dormrepair.remindme.dto.response.ConfirmationCardResponse;
import com.dormrepair.remindme.entity.Reminder;
import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.service.ConfirmationService;
import com.dormrepair.remindme.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 确认会话接口控制器。统一前缀 /api/confirmations，维护 LLM 解析后待用户二次确认的卡片生命周期：单条/批量确认/取消、冲突覆盖、周期提醒三类策略、智能编辑、待执行阶段的确认/稍后/取消等
@Slf4j
@RestController
@RequestMapping("/api/confirmations")
@RequiredArgsConstructor
public class ConfirmationController {

    // 确认会话服务：会话状态机、冲突判定、周期提醒策略、超时回收等逻辑都在该服务
    private final ConfirmationService confirmationService;
    // 大模型服务：智能修改接口中用于解析用户「改成XXX」式的自然语言编辑意图
    private final LLMService llmService;

    // 获取当前用户有效的待确认会话列表（刷新时前端校验 sessionStorage 用）
    // 只返回 status=PENDING 且未超时的会话 sessionId 列表
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> getPendingSessions(@RequestParam Long userId) {
        List<String> sessionIds = confirmationService.getPendingSessions(userId).stream()
                .map(s -> s.getSessionId())
                .toList();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessionIds", sessionIds);
        return ResponseEntity.ok(result);
    }

    // 单条确认会话的「确认创建」接口。可选 body.conflictIds 指定需要同时覆盖的冲突提醒 ID 列表；未传时若存在冲突则返回冲突卡片
    // sessionId - 确认会话唯一标识（创建时生成）
    // userId    - 当前用户
    @PostMapping("/{sessionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            List<Long> conflictIdsToOverwrite = List.of();
            if (body != null && body.containsKey("conflictIds")) {
                @SuppressWarnings("unchecked")
                List<Number> ids = (List<Number>) body.get("conflictIds");
                conflictIdsToOverwrite = ids != null
                        ? ids.stream().map(Number::longValue).toList()
                        : List.of();
            }
            Map<String, Object> result = confirmationService.confirm(sessionId, userId, conflictIdsToOverwrite);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 单条确认会话「取消」：丢弃当前会话，不会写入 Reminder 表
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.cancel(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 冲突场景下「全部覆盖」：删除所有与本次时间冲突的既有提醒，再创建当前提醒
    @PostMapping("/{sessionId}/overwrite")
    public ResponseEntity<Map<String, Object>> overwrite(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleOverwrite(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 周期提醒「仅本次生效」：本次按用户修改后的值写入，后续周期实例仍沿用模板原始时间
    @PostMapping("/{sessionId}/recurring/direct")
    public ResponseEntity<Map<String, Object>> recurringDirect(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.confirmRecurringDirect(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 周期提醒「跳过本次」：本次不生成提醒，后续周期实例保持模板
    @PostMapping("/{sessionId}/recurring/skip")
    public ResponseEntity<Map<String, Object>> recurringSkip(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.confirmRecurringSkip(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 周期提醒「全部覆盖模板」：本次修改反写回 RecurringReminder 模板，之后所有周期实例均按新时间生成
    @PostMapping("/{sessionId}/recurring/overwrite-all")
    public ResponseEntity<Map<String, Object>> recurringOverwriteAll(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.confirmRecurringOverwriteAll(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 按冲突 ID 单条覆盖：选择覆盖冲突列表中的某一条而非全部
    // conflictId - 要覆盖掉的既有提醒 ID
    @PostMapping("/{sessionId}/overwrite/{conflictId}")
    public ResponseEntity<Map<String, Object>> overwriteSingle(
            @PathVariable String sessionId,
            @PathVariable Long conflictId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleOverwrite(sessionId, userId, List.of(conflictId));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 按冲突 ID 批量覆盖：用户在多冲突复选框中勾选若干条后统一提交覆盖
    // body.conflictIds - Number[] 类型的待覆盖提醒 ID 数组
    @PostMapping("/{sessionId}/batch-overwrite")
    public ResponseEntity<Map<String, Object>> batchOverwrite(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> conflictIdNumbers = (List<Number>) body.get("conflictIds");
            List<Long> conflictIds = conflictIdNumbers != null
                    ? conflictIdNumbers.stream().map(Number::longValue).toList()
                    : List.of();
            Map<String, Object> result = confirmationService.handleOverwrite(sessionId, userId, conflictIds);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 编辑并重解析会话（旧版全量字段提交）。传入新的用户输入和已解析数据，服务端重新组装确认卡片并推送刷新
    @PostMapping("/{sessionId}/edit")
    public ResponseEntity<Map<String, Object>> edit(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String newInput = (String) body.get("input");
            Map<String, Object> newParsedData = (Map<String, Object>) body.get("parsedData");

            if (newInput == null || newParsedData == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少必要参数");
                return ResponseEntity.badRequest().body(error);
            }

            confirmationService.reparse(sessionId, userId, newInput, newParsedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "已重新解析");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 到点执行阶段的「确认执行」：把 Reminder 状态置为 EXECUTING → DONE
    @PostMapping("/{sessionId}/execute/confirm")
    public ResponseEntity<Map<String, Object>> executeConfirm(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteConfirm(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 到点执行阶段的「稍后提醒」：按默认策略（一般顺延 10 分钟或用户配置）重新生成一次提醒实例
    @PostMapping("/{sessionId}/execute/later")
    public ResponseEntity<Map<String, Object>> executeLater(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteLater(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 到点执行阶段的「取消本次」：丢弃当次执行，状态置为 CANCELLED
    @PostMapping("/{sessionId}/execute/cancel")
    public ResponseEntity<Map<String, Object>> executeCancel(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        try {
            Map<String, Object> result = confirmationService.handleExecuteCancel(sessionId, userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 获取指定会话当前数据快照：用于卡片刷新、回显等场景拉取最新确认卡片状态
    @GetMapping("/{sessionId}/data")
    public ResponseEntity<Map<String, Object>> getData(
            @PathVariable String sessionId,
            @RequestParam Long userId) {
        Map<String, Object> data = confirmationService.getSessionData(sessionId);
        return ResponseEntity.ok(data);
    }

    // 表单式重新解析接口：用户在卡片中直接修改字段（标题/时间/地点/优先级/周期等）后提交，服务端按字段值重建 parsedData 并刷新卡片
    @PostMapping("/{sessionId}/reparse")
    public ResponseEntity<Map<String, Object>> reparse(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String title = (String) body.get("title");
            String remindTime = (String) body.get("remindTime");
            String location = (String) body.get("location");
            String priority = (String) body.get("priority");
            Integer advanceMinutes = (Integer) body.get("advanceMinutes");

            if (title == null || remindTime == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少必要参数");
                return ResponseEntity.badRequest().body(error);
            }

            // 重新组装 parsedData 结构，补齐缺省字段以便后续业务统一处理
            Map<String, Object> parsedData = new HashMap<>();
            parsedData.put("title", title);
            parsedData.put("remindTime", remindTime);
            parsedData.put("location", location);
            parsedData.put("note", body.get("note"));
            parsedData.put("priority", priority != null ? priority : Priority.NORMAL.name());
            parsedData.put("advanceMinutes", advanceMinutes != null ? advanceMinutes : 10);
            parsedData.put("intentType", "REMINDER");
            parsedData.put("confidence", 0.95);

            // 周期提醒相关字段（可选，仅 isRecurring=true 时写入）
            if (body.get("isRecurring") != null) {
                parsedData.put("isRecurring", body.get("isRecurring"));
                parsedData.put("startDate", body.get("startDate"));
                parsedData.put("endDate", body.get("endDate"));
                parsedData.put("repeatType", body.get("repeatType"));
                if (body.get("recurringRemindTime") != null) {
                    parsedData.put("recurringRemindTime", body.get("recurringRemindTime"));
                }
            }

            ConfirmationCardResponse card = confirmationService.reparse(sessionId, userId, title, parsedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "已重新解析");
            // actions/timeout/expiresAt 用于构成卡片按钮与倒计时
            result.put("actions", card.getActions());
            result.put("timeout", card.getTimeout());
            result.put("expiresAt", card.getExpiresAt());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 智能修改接口（自然语言式编辑）：用户输入「改成下午三点」这类一句话指令，由 LLM 识别修改字段+新值后自动刷新卡片
    // sessionId - 当前确认会话
    // userId    - 用户
    // body 字段：userInput(用户一句话修改指令,必填)、currentData(当前卡片已解析数据,必填)
    @PostMapping("/{sessionId}/smart-edit")
    public ResponseEntity<Map<String, Object>> smartEdit(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestBody Map<String, Object> body) {
        try {
            String userInput = (String) body.get("userInput");
            @SuppressWarnings("unchecked")
            Map<String, Object> currentData = (Map<String, Object>) body.get("currentData");

            if (userInput == null || userInput.trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少用户输入");
                return ResponseEntity.badRequest().body(error);
            }
            if (currentData == null || currentData.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少当前卡片数据");
                return ResponseEntity.badRequest().body(error);
            }

            // 调 LLM 识别编辑意图：返回要改的字段（time/location/title/priority）+ 新值 + 解析原因
            LLMService.EditIntent editIntent = llmService.parseEditIntent(userInput, currentData);
            log.info("修改意图解析结果: field={}, newValue={}, reason={}",
                    editIntent.getField(), editIntent.getNewValue(), editIntent.getReason());

            // 无法识别修改意图时，返回用户可理解的指引，提示可修改的几类字段
            if ("unknown".equals(editIntent.getField())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无法识别修改意图，请明确说明要修改的内容（如：改成下午三点、地点改为会议室、改成开会）");
                return ResponseEntity.ok(result);
            }

            // 根据 LLM 识别的 field 名，映射到 parsedData 中对应 key，并写入新值
            String fieldName = editIntent.getField();
            String newValue = editIntent.getNewValue();
            String fieldDisplayName = getFieldDisplayName(fieldName);

            // 复制一份 currentData 再修改，避免影响上游引用
            Map<String, Object> updatedData = new HashMap<>(currentData);
            switch (fieldName) {
                case "time":
                    updatedData.put("remindTime", newValue);
                    break;
                case "location":
                    updatedData.put("location", newValue);
                    break;
                case "title":
                    updatedData.put("title", newValue);
                    break;
                case "priority":
                    updatedData.put("priority", newValue);
                    break;
                default:
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("message", "不支持修改的字段类型：" + fieldName);
                    return ResponseEntity.ok(error);
            }

            // 补齐业务需要但可能缺失的默认字段：优先级/提前分钟/意图类型/置信度
            updatedData.putIfAbsent("priority", Priority.NORMAL.name());
            updatedData.putIfAbsent("advanceMinutes", 10);
            updatedData.putIfAbsent("intentType", "REMINDER");
            updatedData.put("confidence", 0.95);

            // 调 reparse 重建卡片，内部会主动通过 WebSocket 推送新卡片
            confirmationService.reparse(sessionId, userId, userInput, updatedData);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "✏️ 已修改" + fieldDisplayName + "为：" + newValue);
            result.put("field", fieldName);
            result.put("newValue", newValue);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("智能修改失败: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "修改失败，请重试");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    // 字段英文名 → 中文显示名映射，用于智能修改成功提示中的用户可读文案
    private String getFieldDisplayName(String field) {
        return switch (field) {
            case "time" -> "时间";
            case "location" -> "地点";
            case "title" -> "标题";
            case "priority" -> "优先级";
            default -> field;
        };
    }

    // 通用单会话动作分发接口：支持 confirm / cancel 两类 action 统一入口，简化调用方路径判断
    // body 字段：sessionId、action(confirm/cancel)、userId
    @PostMapping("/action")
    public ResponseEntity<Map<String, Object>> action(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        String action = (String) body.get("action");
        Long userId = body.containsKey("userId") ? Long.parseLong(String.valueOf(body.get("userId"))) : null;

        if (sessionId == null || action == null || userId == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "缺少必要参数");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = new HashMap<>();
            if ("confirm".equals(action)) {
                confirmationService.confirmSingle(userId, sessionId);
                result.put("success", true);
                result.put("message", "已确认");
            } else if ("cancel".equals(action)) {
                boolean success = confirmationService.cancelSingle(userId, sessionId);
                result.put("success", success);
                result.put("message", success ? "已取消" : "操作失败");
            } else {
                result.put("success", false);
                result.put("message", "无效的操作类型");
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 批量动作分发接口：对用户全部待确认会话执行 confirmAll / cancelAll，用于顶部「一键确认/取消」按钮
    // body 字段：userId、action(confirmAll/cancelAll)
    @PostMapping("/action-all")
    public ResponseEntity<Map<String, Object>> actionAll(@RequestBody Map<String, Object> body) {
        Long userId = body.containsKey("userId") ? Long.parseLong(String.valueOf(body.get("userId"))) : null;
        String action = (String) body.get("action");

        if (userId == null || action == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "缺少必要参数");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = new HashMap<>();
            if ("confirmAll".equals(action)) {
                List<Reminder> reminders = confirmationService.confirmAll(userId);
                result.put("success", true);
                result.put("message", "已确认全部 " + reminders.size() + " 个行程");
            } else if ("cancelAll".equals(action)) {
                int count = confirmationService.cancelAll(userId);
                result.put("success", true);
                result.put("message", "已取消全部 " + count + " 个行程");
            } else {
                result.put("success", false);
                result.put("message", "无效的操作类型");
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // 冲突场景动作处理接口：当解析到与既有提醒时间冲突时，通过此接口提交用户选择（覆盖/改时间/取消等）
    @PostMapping("/conflict")
    public ResponseEntity<Map<String, Object>> handleConflict(@RequestBody Map<String, Object> body) {
        try {
            Long userId = body.containsKey("userId") ? Long.parseLong(String.valueOf(body.get("userId"))) : null;
            String action = (String) body.get("action");
            String title = (String) body.get("title");
            String remindTime = (String) body.get("remindTime");
            String location = (String) body.get("location");
            String priority = (String) body.get("priority");
            
            Long conflictReminderId = null;
            if (body.containsKey("conflictReminderId") && body.get("conflictReminderId") != null) {
                conflictReminderId = Long.parseLong(String.valueOf(body.get("conflictReminderId")));
            }
            
            String newTime = (String) body.get("newTime");

            if (userId == null || action == null || title == null || remindTime == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "缺少必要参数（userId, action, title, remindTime）");
                return ResponseEntity.badRequest().body(error);
            }

            Map<String, Object> result = confirmationService.handleConflict(
                    userId, action, title, remindTime, location, priority, conflictReminderId, newTime);
            
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("处理冲突失败: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "处理冲突失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}