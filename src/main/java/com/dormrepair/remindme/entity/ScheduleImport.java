package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 日程批量导入任务实体类。对应表 schedule_imports，记录一次 Excel 导入的元信息，与明细表 schedule_import_items 为一对多关系
@Data
@Entity
@Table(name = "schedule_imports")
@NoArgsConstructor
public class ScheduleImport {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 发起导入的用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 上传的原始文件名（含扩展名）
    @Column(name = "file_name", nullable = false)
    private String fileName;

    // 文件类型：excel / csv / ics 等，用于选择不同的解析器
    @Column(name = "file_type", nullable = false)
    private String fileType;

    // 文件解析出的总条目数
    @Column(name = "total_count")
    private Integer totalCount;

    // 成功导入并创建提醒的条目数
    @Column(name = "success_count")
    private Integer successCount;

    // 导入失败的条目数，失败明细存 schedule_import_items.error_message
    @Column(name = "failed_count")
    private Integer failedCount;

    // 学期总周数：课程表导入特有，用于计算各周的日期
    @Column(name = "total_weeks")
    private Integer totalWeeks;

    // 学期开始日期：课程表导入特有，第 1 周的周一
    @Column(name = "semester_start_date")
    private LocalDate semesterStartDate;

    // 本次导入任务的整体状态：PENDING-处理中、SUCCESS-全部成功、PARTIAL-部分成功、FAILED-全部失败、EXPIRED-已过期
    @Column(nullable = false)
    private String status = "PENDING";

    // 任务创建时间，由 @PrePersist 在第一次插入前自动写入当前时间
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 插入前钩子：createdAt 为空时自动填充当前时间；JPA Auditing 未启用时作为兜底
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
