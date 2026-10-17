package com.company.labor.common.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ID生成器
 *
 * <p>支持两种ID生成方式：
 * <ul>
 *   <li>雪花算法（Snowflake）- 生成Long类型的分布式唯一ID</li>
 *   <li>业务编号 - 生成带业务前缀的字符串ID（如EMP20241018001）</li>
 * </ul>
 *
 * <p>雪花算法ID组成（64位）：
 * <pre>
 * 1位符号位 + 41位时间戳 + 10位机器ID + 12位序列号
 * </pre>
 *
 * <p>使用示例：
 * <pre>
 * // 雪花算法生成Long ID
 * Long id = IdGenerator.nextId();
 *
 * // 生成员工编号：EMP20241018001
 * String employeeNo = IdGenerator.generateEmployeeNo();
 *
 * // 生成工资单号：SAL20241018001
 * String salaryNo = IdGenerator.generateSalaryNo();
 *
 * // 生成自定义业务编号
 * String customNo = IdGenerator.generateBizNo("ORD", 5); // ORD2024101800001
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
public final class IdGenerator {

    private IdGenerator() {
        // 私有构造函数，防止实例化
    }

    // ==================== 雪花算法 ====================

    /**
     * 起始时间戳（2024-01-01 00:00:00）
     */
    private static final long START_TIMESTAMP = 1704038400000L;

    /**
     * 机器ID所占的位数
     */
    private static final long WORKER_ID_BITS = 10L;

    /**
     * 序列号所占的位数
     */
    private static final long SEQUENCE_BITS = 12L;

    /**
     * 机器ID最大值（1024）
     */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    /**
     * 序列号最大值（4096）
     */
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    /**
     * 机器ID左移位数
     */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /**
     * 时间戳左移位数
     */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /**
     * 机器ID（0-1023）
     * 可以从配置文件中读取，或者根据IP地址计算
     */
    private static final long WORKER_ID = getWorkerId();

    /**
     * 序列号（0-4095）
     */
    private static long sequence = 0L;

    /**
     * 上次生成ID的时间戳
     */
    private static long lastTimestamp = -1L;

    /**
     * 生成下一个ID（雪花算法）
     *
     * <p>线程安全，支持高并发
     *
     * @return 分布式唯一ID
     */
    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                // 时钟回拨在5ms以内，等待
                try {
                    Thread.sleep(offset * 2);
                    timestamp = System.currentTimeMillis();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException("时钟回拨，拒绝生成ID");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待时钟恢复被中断", e);
                }
            } else {
                // 时钟回拨超过5ms，拒绝生成ID
                throw new RuntimeException("时钟回拨" + offset + "ms，拒绝生成ID");
            }
        }

        // 同一毫秒内
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 序列号用完，等待下一毫秒
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒，序列号重置为0
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装ID
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 等待下一毫秒
     *
     * @param lastTimestamp 上次生成ID的时间戳
     * @return 当前时间戳
     */
    private static long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 获取机器ID
     *
     * <p>可以从配置文件中读取，或者根据IP地址计算
     *
     * @return 机器ID（0-1023）
     */
    private static long getWorkerId() {
        // TODO: 从配置文件中读取，或者根据IP地址计算
        // 这里简单返回0，生产环境需要根据实际情况配置
        return 0L;
    }

    // ==================== 业务编号生成 ====================

    /**
     * 日期格式化器（yyyyMMdd）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 序列号计数器（每天重置）
     */
    private static final AtomicInteger DAILY_SEQUENCE = new AtomicInteger(0);

    /**
     * 上次生成编号的日期
     */
    private static volatile String lastDate = "";

    /**
     * 生成业务编号
     *
     * <p>格式：前缀 + 日期(yyyyMMdd) + 序列号
     *
     * @param prefix 业务前缀（如EMP、SAL、ORD）
     * @param sequenceLength 序列号长度（如3表示001-999）
     * @return 业务编号
     */
    public static synchronized String generateBizNo(String prefix, int sequenceLength) {
        String currentDate = LocalDateTime.now().format(DATE_FORMATTER);

        // 如果日期变了，重置序列号
        if (!currentDate.equals(lastDate)) {
            lastDate = currentDate;
            DAILY_SEQUENCE.set(0);
        }

        // 序列号递增
        int sequence = DAILY_SEQUENCE.incrementAndGet();

        // 格式化序列号（补0）
        String sequenceStr = String.format("%0" + sequenceLength + "d", sequence);

        return prefix + currentDate + sequenceStr;
    }

    /**
     * 生成员工编号
     *
     * <p>格式：EMP + yyyyMMdd + 3位序列号
     * <p>示例：EMP20241018001
     *
     * @return 员工编号
     */
    public static String generateEmployeeNo() {
        return generateBizNo("EMP", 3);
    }

    /**
     * 生成工资单号
     *
     * <p>格式：SAL + yyyyMMdd + 3位序列号
     * <p>示例：SAL20241018001
     *
     * @return 工资单号
     */
    public static String generateSalaryNo() {
        return generateBizNo("SAL", 3);
    }

    /**
     * 生成借支单号
     *
     * <p>格式：ADV + yyyyMMdd + 3位序列号
     * <p>示例：ADV20241018001
     *
     * @return 借支单号
     */
    public static String generateAdvanceNo() {
        return generateBizNo("ADV", 3);
    }

    /**
     * 生成返费单号
     *
     * <p>格式：RBT + yyyyMMdd + 3位序列号
     * <p>示例：RBT20241018001
     *
     * @return 返费单号
     */
    public static String generateRebateNo() {
        return generateBizNo("RBT", 3);
    }

    /**
     * 生成项目编号
     *
     * <p>格式：PRJ + yyyyMMdd + 3位序列号
     * <p>示例：PRJ20241018001
     *
     * @return 项目编号
     */
    public static String generateProjectNo() {
        return generateBizNo("PRJ", 3);
    }

    /**
     * 生成渠道编号
     *
     * <p>格式：CHN + yyyyMMdd + 3位序列号
     * <p>示例：CHN20241018001
     *
     * @return 渠道编号
     */
    public static String generateChannelNo() {
        return generateBizNo("CHN", 3);
    }
}
