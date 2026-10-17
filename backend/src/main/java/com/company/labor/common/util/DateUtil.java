package com.company.labor.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 日期时间工具类
 *
 * <p>基于Java 8的LocalDateTime，不再使用过时的Date和SimpleDateFormat。
 *
 * <p>使用示例：
 * <pre>
 * // 格式化
 * String formatted = DateUtil.format(LocalDateTime.now()); // 2026-10-18 15:30:00
 *
 * // 解析
 * LocalDateTime dateTime = DateUtil.parse("2026-10-18 15:30:00");
 *
 * // 计算天数差
 * long days = DateUtil.daysBetween(startDate, endDate);
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class DateUtil {

    private DateUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * 标准日期时间格式（yyyy-MM-dd HH:mm:ss）
     */
    public static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";

    /**
     * 标准日期格式（yyyy-MM-dd）
     */
    public static final String PATTERN_DATE = "yyyy-MM-dd";

    /**
     * 标准时间格式（HH:mm:ss）
     */
    public static final String PATTERN_TIME = "HH:mm:ss";

    /**
     * 月份格式（yyyy-MM）
     */
    public static final String PATTERN_MONTH = "yyyy-MM";

    /**
     * 紧凑日期时间格式（yyyyMMddHHmmss）
     */
    public static final String PATTERN_DATETIME_COMPACT = "yyyyMMddHHmmss";

    /**
     * 标准日期时间格式化器
     */
    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_DATETIME);

    /**
     * 标准日期格式化器
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_DATE);

    /**
     * 标准时间格式化器
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_TIME);

    /**
     * 月份格式化器
     */
    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_MONTH);

    /**
     * 紧凑日期时间格式化器
     */
    private static final DateTimeFormatter DATETIME_COMPACT_FORMATTER =
            DateTimeFormatter.ofPattern(PATTERN_DATETIME_COMPACT);

    /**
     * 默认时区（东八区）
     */
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Shanghai");

    // ==================== 格式化 ====================

    /**
     * 格式化日期时间（yyyy-MM-dd HH:mm:ss）
     *
     * @param dateTime 日期时间
     * @return 格式化后的字符串
     */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DATETIME_FORMATTER.format(dateTime);
    }

    /**
     * 格式化日期（yyyy-MM-dd）
     *
     * @param date 日期
     * @return 格式化后的字符串
     */
    public static String format(LocalDate date) {
        if (date == null) {
            return null;
        }
        return DATE_FORMATTER.format(date);
    }

    /**
     * 格式化日期时间（自定义格式）
     *
     * @param dateTime 日期时间
     * @param pattern 格式
     * @return 格式化后的字符串
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).format(dateTime);
    }

    /**
     * 格式化为月份（yyyy-MM）
     *
     * @param dateTime 日期时间
     * @return 格式化后的字符串
     */
    public static String formatMonth(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return MONTH_FORMATTER.format(dateTime);
    }

    /**
     * 格式化为紧凑格式（yyyyMMddHHmmss）
     *
     * @param dateTime 日期时间
     * @return 格式化后的字符串
     */
    public static String formatCompact(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DATETIME_COMPACT_FORMATTER.format(dateTime);
    }

    // ==================== 解析 ====================

    /**
     * 解析日期时间字符串（yyyy-MM-dd HH:mm:ss）
     *
     * @param dateTimeStr 日期时间字符串
     * @return LocalDateTime
     */
    public static LocalDateTime parse(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(dateTimeStr, DATETIME_FORMATTER);
    }

    /**
     * 解析日期字符串（yyyy-MM-dd）
     *
     * @param dateStr 日期字符串
     * @return LocalDate
     */
    public static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        return LocalDate.parse(dateStr, DATE_FORMATTER);
    }

    /**
     * 解析日期时间字符串（自定义格式）
     *
     * @param dateTimeStr 日期时间字符串
     * @param pattern 格式
     * @return LocalDateTime
     */
    public static LocalDateTime parse(String dateTimeStr, String pattern) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern(pattern));
    }

    // ==================== 转换 ====================

    /**
     * LocalDateTime转Date
     *
     * @param dateTime LocalDateTime
     * @return Date
     */
    public static Date toDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return Date.from(dateTime.atZone(DEFAULT_ZONE_ID).toInstant());
    }

    /**
     * Date转LocalDateTime
     *
     * @param date Date
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
    }

    /**
     * 时间戳转LocalDateTime
     *
     * @param timestamp 时间戳（毫秒）
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), DEFAULT_ZONE_ID);
    }

    /**
     * LocalDateTime转时间戳
     *
     * @param dateTime LocalDateTime
     * @return 时间戳（毫秒）
     */
    public static Long toTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(DEFAULT_ZONE_ID).toInstant().toEpochMilli();
    }

    // ==================== 计算 ====================

    /**
     * 计算两个日期之间的天数差
     *
     * @param start 开始日期
     * @param end 结束日期
     * @return 天数差（end - start）
     */
    public static long daysBetween(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * 计算两个日期时间之间的天数差
     *
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 天数差（end - start）
     */
    public static long daysBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * 计算两个日期时间之间的小时差
     *
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 小时差（end - start）
     */
    public static long hoursBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.HOURS.between(start, end);
    }

    /**
     * 计算两个日期时间之间的分钟差
     *
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 分钟差（end - start）
     */
    public static long minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.MINUTES.between(start, end);
    }

    // ==================== 获取当前时间 ====================

    /**
     * 获取当前日期时间
     *
     * @return LocalDateTime
     */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 获取当前日期
     *
     * @return LocalDate
     */
    public static LocalDate today() {
        return LocalDate.now();
    }

    /**
     * 获取当前时间戳（毫秒）
     *
     * @return 时间戳
     */
    public static long currentTimestamp() {
        return System.currentTimeMillis();
    }

    // ==================== 判断 ====================

    /**
     * 判断日期是否在范围内
     *
     * @param date 日期
     * @param start 开始日期
     * @param end 结束日期
     * @return true在范围内，false不在范围内
     */
    public static boolean isBetween(LocalDate date, LocalDate start, LocalDate end) {
        if (date == null || start == null || end == null) {
            return false;
        }
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * 判断日期时间是否在范围内
     *
     * @param dateTime 日期时间
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return true在范围内，false不在范围内
     */
    public static boolean isBetween(LocalDateTime dateTime, LocalDateTime start, LocalDateTime end) {
        if (dateTime == null || start == null || end == null) {
            return false;
        }
        return !dateTime.isBefore(start) && !dateTime.isAfter(end);
    }
}
