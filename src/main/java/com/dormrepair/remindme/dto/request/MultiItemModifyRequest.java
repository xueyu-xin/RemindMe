package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 多项事务场景下单条条目修改请求 DTO。用于 PUT /api/confirmations/multi/{sessionId}/item/{index}
@Data
public class MultiItemModifyRequest {
    // 事务标题（必填）
    @NotBlank(message = "标题不能为空")
    private String title;
    // 提醒时间（必填，yyyy-MM-dd HH:mm:ss）
    @NotBlank(message = "提醒时间不能为空")
    private String remindTime;
    // 结束时间（可选）
    private String endTime;
    // 地点（可选）
    private String location;
    // 备注（可选）
    private String note;
    // 优先级（可选，默认 NORMAL）
    private String priority;
    // 提前提醒分钟数（可选，默认 10）
    private Integer advanceMinutes;
}
