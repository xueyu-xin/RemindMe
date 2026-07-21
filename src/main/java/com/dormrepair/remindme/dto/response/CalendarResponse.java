package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 日历响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarResponse {

    private Integer year;
    private Integer month;
    private Integer day;
    private List<DayItem> days;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayItem {
        @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
        private LocalDate date;
        private Boolean hasReminder;
        private Integer reminderCount;
        private String periodStatus;
        private List<ReminderItem> reminders;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReminderItem {
        private Long id;
        private String title;
        private String remindTime;
        private String priority;
        private String status;
        private String confirmStatus;
        private String location;
    }
}
