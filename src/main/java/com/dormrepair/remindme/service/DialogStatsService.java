package com.dormrepair.remindme.service;

import com.dormrepair.remindme.entity.UserDialogStats;
import com.dormrepair.remindme.enums.IntentType;
import com.dormrepair.remindme.repository.UserDialogStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// 用户对话统计：落库每条对话消息，定期分析关键词、意图、活跃时段，给 Agent 生成个性化提示
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogStatsService {

    private final UserDialogStatsRepository dialogStatsRepository;

    // 记录一条用户对话并回填意图
    @Transactional
    public void recordDialog(Long userId, String input, IntentType intent) {
        saveMessage(userId, "user", input, null);

        // 取刚写入的 user 记录回填意图，标记为未处理等待每日分析
        List<UserDialogStats> latest = dialogStatsRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1));
        if (!latest.isEmpty()) {
            UserDialogStats stats = latest.get(0);
            stats.setIntentType(intent);
            stats.setProcessed(false);
            dialogStatsRepository.save(stats);
        }
    }

    // 保存一条对话消息（user 或 bot）
    // userId    用户ID
    // role      角色：user / bot
    // content   消息内容
    // sessionId 会话ID，可为 null
    @Transactional
    public void saveMessage(Long userId, String role, String content, String sessionId) {
        if (userId == null || role == null || content == null) {
            return;
        }
        if (!"user".equals(role) && !"bot".equals(role)) {
            log.warn("saveMessage 角色非法: role={}", role);
            return;
        }

        UserDialogStats stats = new UserDialogStats();
        stats.setUserId(userId);
        stats.setRole(role);
        stats.setContent(content);
        stats.setSessionId(sessionId);
        // inputText 字段兼容旧逻辑，必须填值；这里统一写入 content
        stats.setInputText(content);
        stats.setProcessed(false);

        dialogStatsRepository.save(stats);
        log.debug("保存消息: userId={}, role={}, len={}", userId, role, content.length());
    }

    // 查询用户历史消息，按创建时间倒序，内部把条数限制在 1~200
    // userId 用户ID
    // limit  返回条数上限
    public List<Map<String, Object>> queryMessageHistory(Long userId, int limit) {
        if (userId == null) return Collections.emptyList();
        int actualLimit = Math.max(1, Math.min(limit, 200));

        List<UserDialogStats> list = dialogStatsRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, actualLimit));

        // 映射为 role/content/createdAt 三字段，content 缺失时回退到 inputText
        return list.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("role", s.getRole() != null ? s.getRole() : "user");
            m.put("content", s.getContent() != null ? s.getContent() : s.getInputText());
            m.put("createdAt", s.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    // 基于近期对话生成动态提示词，拼出高频关键词、主要意图、活跃时段三条
    public List<String> generateDynamicPrompts(Long userId) {
        List<String> prompts = new ArrayList<>();

        Map<String, Long> keywordCounts = analyzeKeywords(userId);
        Map<IntentType, Long> intentCounts = analyzeIntents(userId);
        Map<Integer, Long> timePatterns = analyzeTimePatterns(userId);

        // 高频关键词提示
        if (!keywordCounts.isEmpty()) {
            String topKeyword = keywordCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");

            prompts.add("用户经常提到'" + topKeyword + "'，相关提醒优先显示");
        }

        // 主要意图提示
        if (!intentCounts.isEmpty()) {
            IntentType topIntent = intentCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(IntentType.UNKNOWN);

            prompts.add("用户主要意图是" + topIntent.getDisplayName());
        }

        // 活跃时段提示
        if (!timePatterns.isEmpty()) {
            int peakHour = timePatterns.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(9);

            prompts.add("用户活跃时段: " + peakHour + ":00-" + (peakHour + 1) + ":00");
        }

        return prompts;
    }

    // 汇总用户行为画像：关键词、意图、时段、最近活动
    public Map<String, Object> analyzeUserBehavior(Long userId) {
        Map<String, Object> analysis = new HashMap<>();

        analysis.put("keywordCounts", analyzeKeywords(userId));
        analysis.put("intentCounts", analyzeIntents(userId));
        analysis.put("timePatterns", analyzeTimePatterns(userId));
        analysis.put("recentActivity", getRecentActivity(userId));

        return analysis;
    }

    // 每天凌晨 2 点跑一次：把所有未处理记录标记为已处理
    @Transactional
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyAnalysis() {
        log.info("开始每日对话分析...");

        List<UserDialogStats> unprocessed = dialogStatsRepository.findAll().stream()
                .filter(s -> !s.getProcessed())
                .collect(Collectors.toList());

        for (UserDialogStats stats : unprocessed) {
            stats.setProcessed(true);
            dialogStatsRepository.save(stats);
        }

        log.info("每日分析完成，处理记录数: {}", unprocessed.size());
    }

    // 关键词统计：取最近 100 条，命中预设关键词集合则计数，按频次取前 5
    private Map<String, Long> analyzeKeywords(Long userId) {
        List<UserDialogStats> dialogs = dialogStatsRepository.findByUserIdOrderByCreatedAtDesc(userId, null)
                .stream()
                .limit(100)
                .collect(Collectors.toList());

        Map<String, Long> keywordCounts = new HashMap<>();

        String[] commonKeywords = {"作业", "考试", "上课", "开会", "吃饭", "睡觉", "运动", "学习"};

        for (UserDialogStats dialog : dialogs) {
            for (String keyword : commonKeywords) {
                if (dialog.getInputText().contains(keyword)) {
                    keywordCounts.merge(keyword, 1L, Long::sum);
                }
            }
        }

        return keywordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    // 意图统计：取最近 100 条，按意图分组计数
    private Map<IntentType, Long> analyzeIntents(Long userId) {
        List<UserDialogStats> dialogs = dialogStatsRepository.findByUserIdOrderByCreatedAtDesc(userId, null)
                .stream()
                .limit(100)
                .collect(Collectors.toList());

        return dialogs.stream()
                .filter(d -> d.getIntentType() != null)
                .collect(Collectors.groupingBy(
                        UserDialogStats::getIntentType,
                        Collectors.counting()
                ));
    }

    // 时段统计：取最近 100 条，按小时分组计数，反映用户活跃时段分布
    private Map<Integer, Long> analyzeTimePatterns(Long userId) {
        List<UserDialogStats> dialogs = dialogStatsRepository.findByUserIdOrderByCreatedAtDesc(userId, null)
                .stream()
                .limit(100)
                .collect(Collectors.toList());

        return dialogs.stream()
                .collect(Collectors.groupingBy(
                        d -> d.getCreatedAt().getHour(),
                        Collectors.counting()
                ));
    }

    // 取最近 10 条活动，映射为 input/intent/time 三字段
    private List<Map<String, Object>> getRecentActivity(Long userId) {
        return dialogStatsRepository.findByUserIdOrderByCreatedAtDesc(userId, null)
                .stream()
                .limit(10)
                .map(d -> {
                    Map<String, Object> activity = new HashMap<>();
                    activity.put("input", d.getInputText());
                    activity.put("intent", d.getIntentType() != null ? d.getIntentType().name() : "UNKNOWN");
                    activity.put("time", d.getCreatedAt());
                    return activity;
                })
                .collect(Collectors.toList());
    }
}
