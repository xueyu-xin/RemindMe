package com.dormrepair.remindme.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一时间工具类
 * <p>
 * 全系统使用 Asia/Shanghai（北京时间，UTC+8）
 * 统一日期时间格式：yyyy-MM-dd HH:mm:ss
 * </p>
 */
public final class TimeUtils {

    public static final String TIMEZONE = "Asia/Shanghai";
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeUtils() {
    }

    /**
     * 获取当前北京时间
     */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 格式化为 yyyy-MM-dd HH:mm:ss
     */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(FORMATTER);
    }

    /**
     * 格式化为 yyyy-MM-dd
     */
    public static String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DATE_FORMATTER);
    }

    /**
     * 解析 yyyy-MM-dd HH:mm:ss 为 LocalDateTime
     */
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

    /**
     * 转为时间戳（毫秒）
     */
    public static long toTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        return dateTime.atZone(java.time.ZoneId.of(TIMEZONE)).toInstant().toEpochMilli();
    }

    /**
     * 返回当前北京时间字符串
     */
    public static String getCurrentBeijingTime() {
        return now().format(FORMATTER);
    }
}
