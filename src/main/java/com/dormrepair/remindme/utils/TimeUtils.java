package com.dormrepair.remindme.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 统一时间工具（final + 私有构造，禁止实例化）。集中定义项目用的三个常量：时区、完整时间格式、纯日期格式；
// 所有格式化/解析/时间戳转换在此集中处理，避免各处自定义格式字符串导致的不一致
public final class TimeUtils {

    // 时区：全系统统一 Asia/Shanghai，不得在其他文件硬编码 "UTC+8" 等字符串
    public static final String TIMEZONE = "Asia/Shanghai";
    // 完整日期时间格式：yyyy-MM-dd HH:mm:ss，响应 JSON 中 remindTime、createdAt 等字段均按此格式序列化
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 纯日期格式：yyyy-MM-dd，日程日期、经期日期等仅需年月日的场景
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    // 纯时间格式：HH:mm，日程视图时间标签、确认卡片时间摘要等场景
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    // 带秒时间格式：HH:mm:ss，循环提醒时间字段、日志时间戳等场景
    public static final DateTimeFormatter TIME_SECONDS_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    // 日期+分钟格式：yyyy-MM-dd HH:mm，不带秒的完整时间，确认卡片时间摘要等场景
    public static final DateTimeFormatter DATETIME_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 私有构造，禁止实例化
    private TimeUtils() {
    }

    // 返回当前北京时间。JVM 默认时区已由 RemindMeApplication#init 设为 Asia/Shanghai，LocalDateTime.now() 即北京时间
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    // LocalDateTime 转「yyyy-MM-dd HH:mm:ss」字符串；null 入参直接返回 null 避免 NPE
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(FORMATTER);
    }

    // LocalDateTime 转「yyyy-MM-dd」纯日期字符串；null 入参返回 null
    public static String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DATE_FORMATTER);
    }

    // 「yyyy-MM-dd HH:mm:ss」字符串解析回 LocalDateTime；字符串空白或解析异常一律返回 null，不抛异常
    public static LocalDateTime parse(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr, FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }

    // LocalDateTime → 毫秒级时间戳（epoch millis）。必须附带 ZoneId=Asia/Shanghai 才能得到正确的毫秒值；
    // 入参 null 返回 0，避免 NPE
    public static long toTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        return dateTime.atZone(java.time.ZoneId.of(TIMEZONE)).toInstant().toEpochMilli();
    }

    // 获取当前北京时间完整字符串；等价于 format(now())，日志/调试可直接调用
    public static String getCurrentBeijingTime() {
        return now().format(FORMATTER);
    }

    // LocalDateTime 转「HH:mm」纯时间字符串；null 入参返回 null
    public static String formatTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(TIME_FORMATTER);
    }
}
