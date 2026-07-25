package com.dormrepair.remindme.utils;

import com.dormrepair.remindme.agent.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间/日期工具
 * <p>
 * 为 Agent 提供时间感知和日期计算能力
 * </p>
 */
@Slf4j
@Service
public class TimeTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] WEEK_DAYS_CN = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    /**
     * 获取当前日期和时间
     * <p>
     * 用户询问现在几点、今天几号、今天是星期几时调用
     * </p>
     */
    @AgentTool(name = "get_current_time", description = "获取当前日期和时间，用户询问现在几点、今天几号、今天是星期几时调用")
    public Map<String, Object> getCurrentTime() {
        log.info("Agent调用获取当前时间");

        LocalDateTime now = TimeUtils.now();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("currentTime", now.format(FORMATTER));
        result.put("currentDate", now.format(DATE_FORMATTER));
        result.put("weekDay", getWeekDayChinese(now.getDayOfWeek().getValue()));
        result.put("timestamp", TimeUtils.toTimestamp(now));

        return result;
    }

    /**
     * 计算日期
     * <p>
     * 用户说"3天后"、"下周一"、"两周后"时调用
     * </p>
     *
     * @param expression 日期表达式
     * @return 计算结果
     */
    @AgentTool(name = "calculate_date", description = "计算日期，用户说'3天后'、'下周一'、'两周后'时调用")
    public Map<String, Object> calculateDate(String expression) {
        log.info("Agent调用计算日期: expression={}", expression);

        Map<String, Object> result = new HashMap<>();
        result.put("expression", expression);

        if (expression == null || expression.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "表达式不能为空");
            return result;
        }

        expression = expression.trim();
        LocalDate today = TimeUtils.now().toLocalDate();
        LocalDate calculatedDate = null;
        String description = null;

        // X天后
        Matcher daysAfterMatcher = Pattern.compile("^(\\d+)天后$").matcher(expression);
        if (daysAfterMatcher.matches()) {
            int days = Integer.parseInt(daysAfterMatcher.group(1));
            calculatedDate = today.plusDays(days);
            description = days + "天后";
        }

        // X天前
        if (calculatedDate == null) {
            Matcher daysBeforeMatcher = Pattern.compile("^(\\d+)天前$").matcher(expression);
            if (daysBeforeMatcher.matches()) {
                int days = Integer.parseInt(daysBeforeMatcher.group(1));
                calculatedDate = today.minusDays(days);
                description = days + "天前";
            }
        }

        // X周后
        if (calculatedDate == null) {
            Matcher weeksAfterMatcher = Pattern.compile("^(\\d+)周后$").matcher(expression);
            if (weeksAfterMatcher.matches()) {
                int weeks = Integer.parseInt(weeksAfterMatcher.group(1));
                calculatedDate = today.plusWeeks(weeks);
                description = weeks + "周后";
            }
        }

        // 下周一 ~ 下周日
        if (calculatedDate == null) {
            Map<String, Integer> nextWeekDayMap = new HashMap<>();
            nextWeekDayMap.put("下周一", 1);
            nextWeekDayMap.put("下周二", 2);
            nextWeekDayMap.put("下周三", 3);
            nextWeekDayMap.put("下周四", 4);
            nextWeekDayMap.put("下周五", 5);
            nextWeekDayMap.put("下周六", 6);
            nextWeekDayMap.put("下周日", 7);

            if (nextWeekDayMap.containsKey(expression)) {
                int targetDay = nextWeekDayMap.get(expression);
                calculatedDate = today.plusWeeks(1).with(DayOfWeek.of(targetDay));
                description = expression;
            }
        }

        // 无法解析
        if (calculatedDate == null) {
            result.put("success", false);
            result.put("error", "无法解析表达式: " + expression + "，支持格式：X天后、X天前、X周后、下周一至下周日");
            return result;
        }

        LocalDateTime calculatedDateTime = calculatedDate.atTime(TimeUtils.now().toLocalTime());
        result.put("success", true);
        result.put("calculatedDate", calculatedDate.format(DATE_FORMATTER));
        result.put("calculatedDateTime", calculatedDateTime.format(FORMATTER));
        result.put("weekDay", getWeekDayChinese(calculatedDate.getDayOfWeek().getValue()));
        result.put("description", description);

        return result;
    }

    /**
     * 获取星期几的中文名称
     *
     * @param dayOfWeek ISO标准星期值（1=星期一, 7=星期日）
     * @return 中文名称
     */
    private String getWeekDayChinese(int dayOfWeek) {
        if (dayOfWeek >= 1 && dayOfWeek <= 7) {
            return WEEK_DAYS_CN[dayOfWeek - 1];
        }
        return "未知";
    }
}
