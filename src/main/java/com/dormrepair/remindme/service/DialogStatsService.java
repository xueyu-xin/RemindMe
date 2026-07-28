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

/**
 * 用户对话统计服务
 * <p>
 * 记录用户对话、分析行为模式、生成个性化提示词
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogStatsService {

    private final UserDialogStatsRepository dialogStatsRepository;

    @Transactional
    public void recordDialog(Long userId, String input, IntentType intent) {
        saveMessage(userId, "user", input, null);

        // 补充意图解析信息（通过 inputText 字段所在记录更新）：取最新一条 user 记录回填
        List<UserDialogStats> latest = dialogStatsRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1));
        if (!latest.isEmpty()) {
            UserDialogStats stats = latest.get(0);
            stats.setIntentType(intent);
            stats.setProcessed(false);
            dialogStatsRepository.save(stats);
        }
    }

    /**
     * 保存一条对话消息（用户或机器人）
     *
     * @param userId  用户ID
     * @param role    角色：user / bot
     * @param content 消息内容
     * @param sessionId 会话ID（可为null）
     */
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
        // 向后兼容：inputText 字段必须有值。用户消息填入 input，机器人消息也填 content
        stats.setInputText(content);
        stats.setProcessed(false);

        dialogStatsRepository.save(stats);
        log.debug("保存消息: userId={}, role={}, len={}", userId, role, content.length());
    }

    /**
     * 查询用户历史消息（按创建时间倒序，用于前端加载）
     *
     * @param userId 用户ID
     * @param limit  返回条数上限（内部会限制最大200）
     * @return 消息列表，每项包含 role、content、createdAt
     */
    public List<Map<String, Object>> queryMessageHistory(Long userId, int limit) {
        if (userId == null) return Collections.emptyList();
        int actualLimit = Math.max(1, Math.min(limit, 200));

        List<UserDialogStats> list = dialogStatsRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, actualLimit));

        return list.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("role", s.getRole() != null ? s.getRole() : "user");
            m.put("content", s.getContent() != null ? s.getContent() : s.getInputText());
            m.put("createdAt", s.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    public List<String> generateDynamicPrompts(Long userId) {
        List<String> prompts = new ArrayList<>();

        Map<String, Long> keywordCounts = analyzeKeywords(userId);
        Map<IntentType, Long> intentCounts = analyzeIntents(userId);
        Map<Integer, Long> timePatterns = analyzeTimePatterns(userId);

        if (!keywordCounts.isEmpty()) {
            String topKeyword = keywordCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");
            
            prompts.add("用户经常提到'" + topKeyword + "'，相关提醒优先显示");
        }

        if (!intentCounts.isEmpty()) {
            IntentType topIntent = intentCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(IntentType.UNKNOWN);
            
            prompts.add("用户主要意图是" + topIntent.getDisplayName());
        }

        if (!timePatterns.isEmpty()) {
            int peakHour = timePatterns.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(9);
            
            prompts.add("用户活跃时段: " + peakHour + ":00-" + (peakHour + 1) + ":00");
        }

        return prompts;
    }

    public Map<String, Object> analyzeUserBehavior(Long userId) {
        Map<String, Object> analysis = new HashMap<>();

        analysis.put("keywordCounts", analyzeKeywords(userId));
        analysis.put("intentCounts", analyzeIntents(userId));
        analysis.put("timePatterns", analyzeTimePatterns(userId));
        analysis.put("recentActivity", getRecentActivity(userId));

        return analysis;
    }

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
