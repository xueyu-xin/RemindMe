package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提醒响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReminderResponse {

    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime remindTime;
    private String timezone;
    private Integer advanceMinutes;
    private String cronExpression;
    private String location;
    private String priority;
    private String status;
    private String confirmStatus;
    private Integer confirmAttempts;
    private LocalDateTime lastConfirmRemindAt;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
