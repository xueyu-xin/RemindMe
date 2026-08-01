package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 提醒响应 DTO。提醒列表、详情、编辑后保存等接口的通用返回体，完整映射 Reminder 实体的全部字段
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReminderResponse {

    // 提醒主键 id，编辑/取消/确认等操作都通过此 id 定位
    private Long id;
    // 归属用户 id
    private Long userId;
    // 提醒标题（事项名称）
    private String title;
    // 提醒触发时间，格式 yyyy-MM-dd HH:mm:ss
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;
    // 事项结束时间，日历视图中用于显示时间段
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime endTime;
    // 时区标识，默认 Asia/Shanghai
    private String timezone;
    // 提前提醒分钟数，通知会在此时间差之前触达用户
    private Integer advanceMinutes;
    // Cron 表达式（保留字段）
    private String cronExpression;
    // 地点
    private String location;
    // 备注，用户对提醒的补充说明
    private String note;
    // 优先级：LOW/NORMAL/HIGH，影响通知样式和权重
    private String priority;
    // 提醒状态：PENDING（待推送）/SENT（已推送）/COMPLETED（已完成）/CANCELLED（已取消）/EXPIRED（已过期）
    private String status;
    // 信息确认阶段状态：PENDING（待确认）/CONFIRMED（已确认）/CANCELLED（已取消）/DEFAULTED（超时默认确认）
    private String confirmStatus;
    // 到点执行确认阶段状态：PENDING（待执行确认）/CONFIRMED（已点已执行）/CANCELLED（已点取消）/DEFAULTED（超时默认）
    private String executionStatus;
    // 已尝试确认次数，超过阈值后自动标记超时
    private Integer confirmAttempts;
    // 上次发送确认提醒的时间，用于控制确认轮询的发送频率
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime lastConfirmRemindAt;
    // 提醒来源：USER_INPUT（手动录入）/AI_PARSE（AI 生成）/SCHEDULE_IMPORT（课表导入）等
    private String source;
    // 记录创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;
    // 记录最后更新时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;
}
