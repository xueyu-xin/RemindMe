package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 经期确认请求DTO
 */
@Data
public class PeriodConfirmRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "经期开始日期不能为空")
    private LocalDate periodStartDate;

    private Boolean isActual;
}
