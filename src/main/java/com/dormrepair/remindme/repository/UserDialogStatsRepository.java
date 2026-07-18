package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.UserDialogStats;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话统计 Repository
 * <p>
 * 对应实体：{@link UserDialogStats}
 * 对应表名：`user_dialog_stats`
 * </p>
 */
@Repository
public interface UserDialogStatsRepository extends JpaRepository<UserDialogStats, Long> {

    List<UserDialogStats> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("SELECT COUNT(d) FROM UserDialogStats d WHERE d.userId = :userId AND d.createdAt >= :since")
    Long countByUserIdAndCreatedAtSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since
    );

    @Query("SELECT d.intentType, COUNT(d) FROM UserDialogStats d WHERE d.userId = :userId GROUP BY d.intentType")
    List<Object[]> countByUserIdGroupByIntentType(@Param("userId") Long userId);
}
