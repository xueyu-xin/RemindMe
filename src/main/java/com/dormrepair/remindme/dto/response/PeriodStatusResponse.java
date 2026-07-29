package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// 经期状态响应 DTO。经期状态查询接口的返回体，展示当前所处阶段、下次经期/排卵日日期、距下次天数等关键信息
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodStatusResponse {

    // 状态编码：IN_PERIOD/PRE_PERIOD/OVULATION/SAFE 等，用于区分不同阶段的配色和图标
    private String status;
    // 状态中文展示名（如「经期第 2 天」「易孕期」），直接展示给用户
    private String statusName;
    // 图标名称或 URL
    private String icon;
    // 预测的下次经期起始日期
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private LocalDate nextPeriodDate;
    // 预测的排卵日日期
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private LocalDate ovulationDate;
    // 距离下次经期还有多少天（整数）
    private Integer daysUntilNextPeriod;
    // 当前用户设置的周期天数（默认 28），用于显示周期信息
    private Integer cycleLength;
    // 是否需要提前提醒：当距离下次经期在 N 天以内时为 true
    private Boolean shouldRemind;
}
