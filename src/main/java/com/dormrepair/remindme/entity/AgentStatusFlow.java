package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// Agent 状态流实体类。对应表 agent_status_flows，每轮对话在终态（DONE/ERROR）时落库一条，
// 用于刷新后按时间序回放状态流气泡（默认折叠），与聊天记录保持一致的生命周期
@Data
@Entity
@Table(name = "agent_status_flows", indexes = {
        @Index(name = "idx_asf_user_created", columnList = "user_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class AgentStatusFlow {

    // 主键：自增 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 状态流唯一标识：同一次对话的所有状态事件共享，与前端实时气泡的 flowId 一致
    @Column(name = "flow_id", nullable = false, length = 64, unique = true)
    private String flowId;

    // 用户 id，对应 users.id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 终态状态：DONE 处理完成 / ERROR 处理失败
    @Column(name = "final_status", nullable = false, length = 32)
    private String finalStatus;

    // 总步骤数：生成折叠摘要「N 步 · 用时 X」用
    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    // 整轮耗时（毫秒）：DONE 时小于 300ms 的秒回不会落库
    @Column(name = "duration_ms")
    private Long durationMs;

    // 步骤明细 JSON 数组：每步含 stepIndex/status/displayName/icon/terminal/extra/durationMs
    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    // 落库时间，JPA Auditing 自动写入，前端按时间序穿插渲染
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
