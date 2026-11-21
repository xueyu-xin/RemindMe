package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

// 提醒导出 DTO。导出提醒数据接口（GET /api/reminders/export）的返回体，只含导出展示所需字段，
// 时间字段按项目规范统一 Asia/Shanghai 时区输出
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReminderExportDTO {

    // 提醒主键 id
    private Long id;
    // 提醒标题
    private String title;
    // 提醒触发时间，格式 yyyy-MM-dd HH:mm:ss
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;
    // 事项结束时间（可选，null 表示单时间点提醒）
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime endTime;
    // 地点（可选）
    private String location;
    // 备注（可选）
    private String note;
    // 优先级：LOW/NORMAL/HIGH
    private String priority;
    // 提醒生命周期状态：PENDING/SENT/COMPLETED/CANCELLED/EXPIRED/REMINDED
    private String status;
    // 创建时间（导出按此字段筛选日期范围），格式 yyyy-MM-dd HH:mm:ss
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;
}
