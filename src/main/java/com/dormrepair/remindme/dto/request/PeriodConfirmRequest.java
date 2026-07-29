package com.dormrepair.remindme.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

// 经期确认请求 DTO。
@Data
public class PeriodConfirmRequest {

    // 用户 id，必填，标识要更新哪位用户的经期记录
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    // 经期起始日期，必填
    @NotNull(message = "经期开始日期不能为空")
    private LocalDate periodStartDate;

    // 是否为真实确认，true 表示用户明确点击了"今天就是经期第一天"，false 表示手动调整/补录历史数据
    private Boolean isActual;
}
