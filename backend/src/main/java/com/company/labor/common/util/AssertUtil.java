package com.company.labor.common.util;

import com.company.labor.common.constant.ErrorCode;
import com.company.labor.common.exception.BusinessException;

import java.util.Collection;
import java.util.Map;

/**
 * 断言工具类
 *
 * <p>简化参数校验代码，自动抛出BusinessException。
 *
 * <p>使用示例：
 * <pre>
 * // 非空断言
 * AssertUtil.notNull(employee, "员工不存在");
 * AssertUtil.notEmpty(name, "员工姓名不能为空");
 *
 * // 条件断言
 * AssertUtil.isTrue(salary.compareTo(BigDecimal.ZERO) > 0, "工资必须大于0");
 * AssertUtil.isFalse(isDuplicate, "员工已存在，不能重复添加");
 *
 * // 集合断言
 * AssertUtil.notEmpty(employeeList, "员工列表不能为空");
 *
 * // 对比断言
 * AssertUtil.equals(actualValue, expectedValue, "数值不匹配");
 * </pre>
 *
 * <p>优势：
 * <ul>
 *   <li>代码简洁：一行代码替代if判断 + 抛异常</li>
 *   <li>统一风格：所有参数校验使用相同的API</li>
 *   <li>易于维护：修改错误码只需要改一个地方</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class AssertUtil {

    private AssertUtil() {
        // 私有构造函数，防止实例化
    }

    // ==================== 非空断言 ====================

    /**
     * 断言对象不为null
     *
     * @param obj 对象
     * @param message 错误消息
     * @throws BusinessException 如果对象为null
     */
    public static void notNull(Object obj, String message) {
        if (obj == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言对象不为null（使用错误码）
     *
     * @param obj 对象
     * @param errorCode 错误码
     * @throws BusinessException 如果对象为null
     */
    public static void notNull(Object obj, Integer errorCode) {
        if (obj == null) {
            throw new BusinessException(errorCode);
        }
    }

    /**
     * 断言字符串不为空（null或空字符串或空白字符）
     *
     * @param str 字符串
     * @param message 错误消息
     * @throws BusinessException 如果字符串为空
     */
    public static void notEmpty(String str, String message) {
        if (str == null || str.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言集合不为空（null或空集合）
     *
     * @param collection 集合
     * @param message 错误消息
     * @throws BusinessException 如果集合为空
     */
    public static void notEmpty(Collection<?> collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言Map不为空（null或空Map）
     *
     * @param map Map
     * @param message 错误消息
     * @throws BusinessException 如果Map为空
     */
    public static void notEmpty(Map<?, ?> map, String message) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言数组不为空（null或空数组）
     *
     * @param array 数组
     * @param message 错误消息
     * @throws BusinessException 如果数组为空
     */
    public static void notEmpty(Object[] array, String message) {
        if (array == null || array.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    // ==================== 条件断言 ====================

    /**
     * 断言条件为true
     *
     * @param condition 条件
     * @param message 错误消息
     * @throws BusinessException 如果条件为false
     */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言条件为true（使用错误码）
     *
     * @param condition 条件
     * @param errorCode 错误码
     * @throws BusinessException 如果条件为false
     */
    public static void isTrue(boolean condition, Integer errorCode) {
        if (!condition) {
            throw new BusinessException(errorCode);
        }
    }

    /**
     * 断言条件为false
     *
     * @param condition 条件
     * @param message 错误消息
     * @throws BusinessException 如果条件为true
     */
    public static void isFalse(boolean condition, String message) {
        if (condition) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言条件为false（使用错误码）
     *
     * @param condition 条件
     * @param errorCode 错误码
     * @throws BusinessException 如果条件为true
     */
    public static void isFalse(boolean condition, Integer errorCode) {
        if (condition) {
            throw new BusinessException(errorCode);
        }
    }

    // ==================== 对比断言 ====================

    /**
     * 断言两个对象相等
     *
     * @param actual 实际值
     * @param expected 期望值
     * @param message 错误消息
     * @throws BusinessException 如果不相等
     */
    public static void equals(Object actual, Object expected, String message) {
        if (actual == null && expected == null) {
            return;
        }
        if (actual == null || !actual.equals(expected)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言两个对象不相等
     *
     * @param actual 实际值
     * @param expected 不期望的值
     * @param message 错误消息
     * @throws BusinessException 如果相等
     */
    public static void notEquals(Object actual, Object expected, String message) {
        if (actual != null && actual.equals(expected)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    // ==================== 范围断言 ====================

    /**
     * 断言数值在指定范围内（包含边界）
     *
     * @param value 数值
     * @param min 最小值
     * @param max 最大值
     * @param message 错误消息
     * @throws BusinessException 如果不在范围内
     */
    public static void inRange(int value, int min, int max, String message) {
        if (value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言数值在指定范围内（包含边界）
     *
     * @param value 数值
     * @param min 最小值
     * @param max 最大值
     * @param message 错误消息
     * @throws BusinessException 如果不在范围内
     */
    public static void inRange(long value, long min, long max, String message) {
        if (value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    /**
     * 断言字符串长度在指定范围内（包含边界）
     *
     * @param str 字符串
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @param message 错误消息
     * @throws BusinessException 如果长度不在范围内
     */
    public static void lengthInRange(String str, int minLength, int maxLength, String message) {
        if (str == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        int length = str.length();
        if (length < minLength || length > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
    }

    // ==================== 业务断言（常用场景） ====================

    /**
     * 断言员工存在
     *
     * @param employee 员工对象
     * @throws BusinessException 如果员工不存在
     */
    public static void employeeExists(Object employee) {
        notNull(employee, ErrorCode.EMPLOYEE_NOT_FOUND);
    }

    /**
     * 断言项目存在
     *
     * @param project 项目对象
     * @throws BusinessException 如果项目不存在
     */
    public static void projectExists(Object project) {
        notNull(project, ErrorCode.PROJECT_NOT_FOUND);
    }

    /**
     * 断言渠道存在
     *
     * @param channel 渠道对象
     * @throws BusinessException 如果渠道不存在
     */
    public static void channelExists(Object channel) {
        notNull(channel, ErrorCode.CHANNEL_NOT_FOUND);
    }

    /**
     * 断言权限（用户有权限）
     *
     * @param hasPermission 是否有权限
     * @param message 错误消息
     * @throws BusinessException 如果没有权限
     */
    public static void hasPermission(boolean hasPermission, String message) {
        if (!hasPermission) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    /**
     * 断言已登录
     *
     * @param isLogin 是否已登录
     * @throws BusinessException 如果未登录
     */
    public static void isLogin(boolean isLogin) {
        if (!isLogin) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    /**
     * 抛出业务异常（快捷方法）
     *
     * @param message 错误消息
     * @throws BusinessException 业务异常
     */
    public static void fail(String message) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 抛出业务异常（快捷方法）
     *
     * @param errorCode 错误码
     * @throws BusinessException 业务异常
     */
    public static void fail(Integer errorCode) {
        throw new BusinessException(errorCode);
    }

    /**
     * 抛出业务异常（快捷方法）
     *
     * @param errorCode 错误码
     * @param message 错误消息
     * @throws BusinessException 业务异常
     */
    public static void fail(Integer errorCode, String message) {
        throw new BusinessException(errorCode, message);
    }
}
