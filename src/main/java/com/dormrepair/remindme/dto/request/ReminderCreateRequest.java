package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建提醒请求DTO
 */
@Data
public class ReminderCreateRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "事项名称不能为空")
    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    @NotNull(message = "提醒时间不能为空")
    private LocalDateTime remindTime;

    private String timezone = "Asia/Shanghai";

    private Integer advanceMinutes;

    private String cronExpression;

    private String location;

    private String priority = "NORMAL";

    private String source = "USER_INPUT";
}
