package com.dormrepair.remindme.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

// 更新提醒请求 DTO。对应 PUT /api/reminders/{id} 接口，所有字段均为可选，只有传入值的字段才会被更新
@Data
public class ReminderUpdateRequest {

    // 提醒标题，可选，长度不超过 255 字符
    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    // 提醒触发时间，可选，按 yyyy-MM-dd HH:mm:ss 格式传
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;

    // 事项结束时间，可选，同格式
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime endTime;

    // 时区标识，可选，用于跨时区场景下的重新解析
    private String timezone;

    // 提前提醒分钟数，可选
    private Integer advanceMinutes;

    // Cron 表达式，可选
    private String cronExpression;

    // 地点，可选
    private String location;

    // 备注，可选，用户对提醒的补充说明
    private String note;

    // 优先级，可选值：LOW/NORMAL/HIGH
    private String priority;
}
