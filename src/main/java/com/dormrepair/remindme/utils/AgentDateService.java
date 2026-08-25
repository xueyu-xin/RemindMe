package com.dormrepair.remindme.utils;

import com.dormrepair.remindme.agent.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// P3-7 命名歧义修复：原名 TimeTools 与静态工具类 TimeUtils 形成"双胞胎"极易混淆；
// 改名为 AgentDateService 明确两点：① 这是 Spring @Service（非静态 final 工具类）；② 面向 Agent 提供日期计算工具。
// 对外暴露两个 Agent 工具方法：
// get_current_time（查询现在时刻/日期/星期）、calculate_date（按自然语言表达式算相对日期）；
// 内部工具 getWeekDayChinese 把 ISO 星期值 (1..7) 映射为中文星期名
@Slf4j
@Service
public class AgentDateService {

    // ISO 星期值 → 中文名的固定映射数组，下标 0=星期一…6=星期日，dayOfWeek(1..7) 取下标需 -1
    private static final String[] WEEK_DAYS_CN = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    // Agent 工具「get_current_time」。填入返回 Map 的 5 个字段：success、currentTime、currentDate、weekDay、timestamp；
    // 日志 info 记录一次调用，后续统计 Agent 对时间工具的使用频次可直接取
    @AgentTool(name = "get_current_time", description = "获取当前日期和时间，用户询问现在几点、今天几号、今天是星期几时调用")
    public Map<String, Object> getCurrentTime() {
        log.info("Agent调用获取当前时间");

        LocalDateTime now = TimeUtils.now();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("currentTime", TimeUtils.format(now));
        result.put("currentDate", now.format(TimeUtils.DATE_FORMATTER));
        result.put("weekDay", getWeekDayChinese(now.getDayOfWeek().getValue()));
        result.put("timestamp", TimeUtils.toTimestamp(now));

        return result;
    }

    // Agent 工具「calculate_date」。输入表达式支持 5 类：^(\d+)分钟后$、^(\d+)天后$、^(\d+)天前$、^(\d+)周后$、下周一~下周日；
    // 依次按顺序用正则匹配，命中后记录 calculatedDate / calculatedDateTime / description；
    // 无命中则 success=false 并返回所有支持格式提示；命中 分钟后 时会保留完整 DateTime（时分秒），其他类表达式用今天的时刻拼回
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
        LocalDateTime calculatedDateTime = null;
        String description = null;

        // X分钟后：需要完整的 LocalDateTime，不是整天
        Matcher minutesAfterMatcher = Pattern.compile("^(\\d+)分钟后$").matcher(expression);
        if (minutesAfterMatcher.matches()) {
            int minutes = Integer.parseInt(minutesAfterMatcher.group(1));
            calculatedDateTime = TimeUtils.now().plusMinutes(minutes);
            calculatedDate = calculatedDateTime.toLocalDate();
            description = minutes + "分钟后";
        }

        // X天后：按 if-null 顺序匹配；前面没命中才走这条
        if (calculatedDate == null) {
            Matcher daysAfterMatcher = Pattern.compile("^(\\d+)天后$").matcher(expression);
            if (daysAfterMatcher.matches()) {
                int days = Integer.parseInt(daysAfterMatcher.group(1));
                calculatedDate = today.plusDays(days);
                description = days + "天后";
            }
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

        // 下周一~下周日：建一个 HashMap 字面量把中文→ISO 星期值；
        // 先加 1 周再 with(DayOfWeek.of(target))，取的是下周的那天（而不是本周）
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

        // 所有分支均未命中：返回错误信息与全部支持格式
        if (calculatedDate == null) {
            result.put("success", false);
            result.put("error", "无法解析表达式: " + expression + "，支持格式：X分钟后、X天后、X天前、X周后、下周一至下周日");
            return result;
        }

        // 非「X分钟后」场景：日期计算完后把当前时分秒拼回去，保证输出 always 有 calculatedDateTime 字段
        if (calculatedDateTime == null) {
            calculatedDateTime = calculatedDate.atTime(TimeUtils.now().toLocalTime());
        }

        result.put("success", true);
        result.put("calculatedDate", calculatedDate.format(TimeUtils.DATE_FORMATTER));
        result.put("calculatedDateTime", TimeUtils.format(calculatedDateTime));
        result.put("weekDay", getWeekDayChinese(calculatedDate.getDayOfWeek().getValue()));
        result.put("description", description);

        return result;
    }

    // ISO 星期值 → 中文星期名。1..7 合法值取 WEEK_DAYS_CN[dayOfWeek-1]；越界返回「未知」避免数组越界
    private String getWeekDayChinese(int dayOfWeek) {
        if (dayOfWeek >= 1 && dayOfWeek <= 7) {
            return WEEK_DAYS_CN[dayOfWeek - 1];
        }
        return "未知";
    }
}
