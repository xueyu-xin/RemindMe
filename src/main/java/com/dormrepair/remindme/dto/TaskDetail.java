package com.dormrepair.remindme.dto;

import com.dormrepair.remindme.enums.TaskStatus;
import com.dormrepair.remindme.utils.TimeUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

// 多任务确认中的单个任务详情。
// 作为 ReplyTemplateService 的输入，用于生成结构化回复。
// 同时提供 toMap()/fromMap() 用于与 ConfirmationService 中的 item Map 互转。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetail {

    // 任务序号（从 0 开始）
    private int index;
    // 任务标题
    private String title;
    // 提醒时间
    private LocalDateTime remindTime;
    // 地点，可为空
    private String location;
    // 优先级 LOW/NORMAL/HIGH
    private String priority;
    // 任务状态
    private TaskStatus status;
    // 是否有冲突
    private boolean hasConflict;
    // 冲突的原有提醒标题，可为空
    private String conflictOriginalTitle;
    // 附加消息，如冲突说明或失败原因
    private String message;

    // 序列化到 Map，用于存储到 parsed_data JSON
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("index", index);
        map.put("title", title);
        map.put("remindTime", remindTime != null ? remindTime.format(TimeUtils.FORMATTER) : null);
        map.put("location", location);
        map.put("priority", priority);
        map.put("status", status != null ? status.name() : TaskStatus.PENDING.name());
        map.put("hasConflict", hasConflict);
        map.put("conflictOriginalTitle", conflictOriginalTitle);
        map.put("message", message);
        return map;
    }

    // 从 Map 反序列化，兼容 ConfirmationService 中 item Map 的字段名
    @SuppressWarnings("unchecked")
    public static TaskDetail fromMap(Map<String, Object> map) {
        if (map == null) {
            return TaskDetail.builder().status(TaskStatus.PENDING).build();
        }

        int idx = map.get("index") != null ? Integer.parseInt(String.valueOf(map.get("index"))) : 0;
        String title = map.get("title") != null ? String.valueOf(map.get("title")) : "";
        String remindTimeStr = map.get("remindTime") != null ? String.valueOf(map.get("remindTime")) : null;
        LocalDateTime remindTime = null;
        if (remindTimeStr != null && !remindTimeStr.isBlank()) {
            try {
                remindTime = LocalDateTime.parse(remindTimeStr, TimeUtils.FORMATTER);
            } catch (Exception e) {
                // 尝试 ISO 格式
                try {
                    remindTime = LocalDateTime.parse(remindTimeStr);
                } catch (Exception ignored) {
                    // 解析失败保留 null
                }
            }
        }
        String location = map.get("location") != null ? String.valueOf(map.get("location")) : null;
        String priority = map.get("priority") != null ? String.valueOf(map.get("priority")) : "NORMAL";

        // 状态解析：兼容现有 Map 中的 "PENDING"/"CONFIRMED"/"CANCELLED"/"SKIPPED" 字符串
        String statusStr = map.get("status") != null ? String.valueOf(map.get("status")) : "PENDING";
        boolean hasConflict = map.get("hasConflict") != null
                && Boolean.parseBoolean(String.valueOf(map.get("hasConflict")));

        // 推断 TaskStatus：
        //   CANCELLED（取消选中）→ TaskStatus.CANCELLED（新增交互态）
        //   SKIPPED → TaskStatus.SKIPPED
        //   CONFIRMED + hasConflict → OVERWRITTEN
        //   CONFIRMED 无冲突 → CONFIRMED
        //   FAILED → FAILED
        TaskStatus status;
        if ("CANCELLED".equalsIgnoreCase(statusStr)) {
            status = TaskStatus.CANCELLED;
        } else if ("SKIPPED".equalsIgnoreCase(statusStr)) {
            status = TaskStatus.SKIPPED;
        } else if ("CONFIRMED".equalsIgnoreCase(statusStr)) {
            status = hasConflict ? TaskStatus.OVERWRITTEN : TaskStatus.CONFIRMED;
        } else if ("FAILED".equalsIgnoreCase(statusStr)) {
            status = TaskStatus.FAILED;
        } else if ("OVERWRITTEN".equalsIgnoreCase(statusStr)) {
            status = TaskStatus.OVERWRITTEN;
        } else {
            status = TaskStatus.PENDING;
        }

        String conflictTitle = map.get("conflictOriginalTitle") != null
                ? String.valueOf(map.get("conflictOriginalTitle"))
                : (map.get("conflictTitle") != null ? String.valueOf(map.get("conflictTitle")) : null);
        String message = map.get("message") != null
                ? String.valueOf(map.get("message"))
                : (map.get("conflictMessage") != null ? String.valueOf(map.get("conflictMessage")) : null);

        return TaskDetail.builder()
                .index(idx)
                .title(title)
                .remindTime(remindTime)
                .location(location)
                .priority(priority)
                .status(status)
                .hasConflict(hasConflict)
                .conflictOriginalTitle(conflictTitle)
                .message(message)
                .build();
    }
}
