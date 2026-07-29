package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节次时间 DTO
 * <p>
 * 表示单个节次的时间信息
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSlotDTO {

    /** 节次索引 */
    private Integer index;

    /** 开始时间（格式 HH:mm） */
    private String startTime;

    /** 结束时间（格式 HH:mm） */
    private String endTime;
}
