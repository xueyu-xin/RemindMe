package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.UserDialogStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

// 用户对话统计数据访问接口。操作 user_dialog_stats 表，3 类查询：
// 拉取某用户最新 N 条对话（聊天历史）、统计指定时间后对话条数（活跃度）、按意图类型分组计数（意图分布统计）
@Repository
public interface UserDialogStatsRepository extends JpaRepository<UserDialogStats, Long> {

    // 按用户 id 取对话消息，创建时间倒序 + Pageable 取 top N，聊天历史/动态提示词学习拉取历史样本用
    List<UserDialogStats> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 自定义 JPQL：统计某用户从 since 时刻到现在的对话总条数，算日/周活跃度用
    @Query("SELECT COUNT(d) FROM UserDialogStats d WHERE d.userId = :userId AND d.createdAt >= :since")
    Long countByUserIdAndCreatedAtSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    // 自定义 JPQL：按 userId 分组 count，并把 IntentType + count 返回 Object[] 二元组，后续统计图表取数
    @Query("SELECT d.intentType, COUNT(d) FROM UserDialogStats d WHERE d.userId = :userId GROUP BY d.intentType")
    List<Object[]> countByUserIdGroupByIntentType(@Param("userId") Long userId);
}
