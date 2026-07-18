package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 经期状态响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodStatusResponse {

    private String status;
    private String statusName;
    private String icon;
    private LocalDate nextPeriodDate;
    private LocalDate ovulationDate;
    private Integer daysUntilNextPeriod;
    private Integer cycleLength;
    private Boolean shouldRemind;
}
