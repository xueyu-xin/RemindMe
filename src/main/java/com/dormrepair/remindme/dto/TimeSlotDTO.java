package com.dormrepair.remindme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 节次时间 DTO。课表场景下表示一节课或一个时间段的起止时间，可用于时间节次配置页、AI 识别结果回显等
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSlotDTO {

    // 节次序号，从 1 开始，对应"第 1 节""第 2 节"
    private Integer index;

    // 节次开始时间，格式 HH:mm（如 08:00）
    private String startTime;

    // 节次结束时间，格式 HH:mm（如 08:45）
    private String endTime;
}
