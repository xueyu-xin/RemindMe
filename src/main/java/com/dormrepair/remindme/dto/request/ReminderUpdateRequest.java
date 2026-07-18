package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 更新提醒请求DTO
 */
@Data
public class ReminderUpdateRequest {

    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    private LocalDateTime remindTime;

    private String timezone;

    private Integer advanceMinutes;

    private String cronExpression;

    private String location;

    private String priority;
}
