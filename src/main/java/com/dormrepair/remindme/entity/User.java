package com.dormrepair.remindme.entity;

import com.dormrepair.remindme.enums.ConfirmStatus;
import com.dormrepair.remindme.enums.Gender;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 用户核心实体类。对应表 users，存储用户账号信息、偏好设置、经期专属参数等全部用户态数据
@Data
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 手机号（登录账号），11 位国内手机号，唯一约束
    @Column(unique = true, nullable = false, length = 11)
    private String phone;

    // 密码密文：经 BCrypt 哈希后存储，不存明文
    @Column(nullable = false)
    private String password;

    // 昵称：用户自定义显示名，可为空；为空时调用方可以手机号脱敏显示作为兜底
    private String nickname;

    // 头像：存储相对路径，如 avatars/2026/07/30/xxx.jpg；使用 @Lob 允许超长路径
    @Lob
    private String avatar;

    // 性别：MALE/FEMALE/PRIVATE；命中 FEMALE 时启用经期计算与经期提醒能力
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender = Gender.PRIVATE;

    // 默认城市：用于天气查询、经期建议、地理推荐等场景，默认「广州」
    @Column(nullable = false)
    private String city = "广州";

    // 默认提前提醒分钟数：用户未显式设置单条提醒提前量时的全局默认值
    @Column(name = "default_advance_minutes", nullable = false)
    private Integer defaultAdvanceMinutes = 10;

    // 信息确认阶段多次提醒之间的间隔分钟数（默认 10 分钟，与 ConfirmationSession 对齐）
    @Column(name = "remind_interval", nullable = false)
    private Integer remindInterval = 10;

    // 月经周期天数（默认 28 天），经期计算的核心参数之一；经期校验要求必须大于 period_duration
    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays = 28;

    // 经期提醒是否已被「推迟到明天」：true 表示本期提醒已推迟，不再重复推送；下一个周期会被自动重置
    @Column(name = "period_remind_postponed", nullable = false)
    private Boolean periodRemindPostponed = false;

    // 经期信息确认累计已完成次数，用于统计与个性化推荐
    @Column(name = "period_confirm_count", nullable = false)
    private Integer periodConfirmCount = 0;

    // 经期提醒累计被「推迟」次数，用于判断用户是否偏好推迟提醒
    @Column(name = "period_remind_postponed_count", nullable = false)
    private Integer periodRemindPostponedCount = 0;

    // 上次经期开始日期（yyyy-MM-dd），为空或未设置时 PeriodStatus 返回 UNKNOWN
    @Column(name = "last_period_start")
    private LocalDate lastPeriodStart;

    // 学期开始日期：课程表 Excel 导入时可回填，便于学期内第 N 周换算为具体日期
    @Column(name = "semester_start_date")
    private LocalDate semesterStartDate;

    // 经期持续天数（默认 5 天），经期计算核心参数；校验要求必须小于 cycleDays
    @Column(name = "period_duration", nullable = false)
    private Integer periodDuration = 5;

    // 经期提醒总开关：true-按周期自动生成经期提醒、false-完全不生成经期提醒
    @Column(name = "enable_period_reminder", nullable = false)
    private Boolean enablePeriodReminder = true;

    // 经期提醒时间（HH:mm），默认 20:30，在下次经期前一天该时间推送提醒
    @Column(name = "period_remind_time", nullable = false)
    private String periodRemindTime = "20:30";

    // 当前周期经期信息确认卡片的状态：PENDING-待确认/CONFIRMED-已确认/CANCELLED-已取消/DEFAULTED-超时默认
    @Column(name = "period_confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus periodConfirmStatus = ConfirmStatus.PENDING;

    // 经期提醒推迟到的截止日期：在该日期之前，不再推送本期提醒
    @Column(name = "period_postponed_until")
    private LocalDate periodPostponedUntil;

    // 用户一生中共推迟经期提醒的总次数（累计统计，不因周期重置清零）
    @Column(name = "period_postpone_count", nullable = false)
    private Integer periodPostponeCount = 0;

    // 账号创建时间，JPA Auditing 自动写入
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 最后修改时间，JPA Auditing 自动写入
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
