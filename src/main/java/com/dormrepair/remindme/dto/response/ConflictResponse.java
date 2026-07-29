package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 冲突检测响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictResponse {

    private boolean hasConflict;
    private List<ConflictItem> conflicts;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime suggestedTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictItem {
        private Long id;
        private String title;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime remindTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime endTime;
    }
}
