package com.dormrepair.remindme.repository;

import com.dormrepair.remindme.entity.AgentStatusFlow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// Agent 状态流数据访问接口。操作 agent_status_flows 表，
// 按用户拉取最近 N 条状态流（新→旧），供刷新后回放渲染，查询窗口与聊天历史对齐
@Repository
public interface AgentStatusFlowRepository extends JpaRepository<AgentStatusFlow, Long> {

    // 按用户 id 取状态流，落库时间倒序 + Pageable 取 top N，聊天历史合并渲染用
    List<AgentStatusFlow> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
