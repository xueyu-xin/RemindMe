package com.dormrepair.remindme.dto.request;

import com.dormrepair.remindme.enums.Priority;
import com.dormrepair.remindme.enums.ReminderSource;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

// 创建一次性提醒请求 DTO。对应 POST /api/reminders 接口，承载创建一条新提醒所需的全部字段
@Data
public class ReminderCreateRequest {

    // 提醒所属用户 id，创建提醒时必填，用于归属和鉴权
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    // 提醒标题（事项名称），必填
    @NotBlank(message = "事项名称不能为空")
    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    // 提醒触发时间，必填，格式 yyyy-MM-dd HH:mm:ss，时区默认 Asia/Shanghai
    @NotNull(message = "提醒时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;

    // 事项结束时间，可选，用于判断与后续提醒的时间冲突
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime endTime;

    // 时区标识，默认 Asia/Shanghai，跨时区场景下可按用户本地时区解析 remindTime
    private String timezone = "Asia/Shanghai";

    // 提前提醒分钟数，可选，为空时用用户默认的 defaultAdvanceMinutes
    private Integer advanceMinutes;

    // Cron 表达式，可选，保留字段，仅在支持重复策略时使用
    private String cronExpression;

    // 地点，可选
    private String location;

    // 备注，可选，用户对提醒的补充说明
    private String note;

    // 优先级，默认 NORMAL，可选值：LOW/NORMAL/HIGH，决定提醒通知的样式和权重
    private String priority = Priority.NORMAL.name();

    // 创建来源，默认 USER_INPUT（手动），可选 AI_PARSE（AI 解析生成）、SCHEDULE_IMPORT（课表导入）等
    private String source = ReminderSource.USER_INPUT.name();
}
