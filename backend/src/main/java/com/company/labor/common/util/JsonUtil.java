package com.company.labor.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON工具类
 *
 * <p>基于Jackson的JSON序列化和反序列化工具，支持LocalDateTime等Java 8时间类型。
 *
 * <p>使用示例：
 * <pre>
 * // 对象转JSON
 * String json = JsonUtil.toJson(employee);
 *
 * // JSON转对象
 * Employee employee = JsonUtil.fromJson(json, Employee.class);
 *
 * // JSON转集合
 * List<Employee> employees = JsonUtil.fromJson(json, new TypeReference<List<Employee>>() {});
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
public final class JsonUtil {

    private JsonUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * ObjectMapper实例（单例）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // 注册Java 8时间模块（支持LocalDateTime等）
        OBJECT_MAPPER.registerModule(new JavaTimeModule());

        // 禁用将日期序列化为时间戳
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 对象转JSON字符串
     *
     * @param obj 对象
     * @return JSON字符串
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("对象转JSON失败：{}", obj, e);
            return null;
        }
    }

    /**
     * 对象转格式化的JSON字符串（带缩进）
     *
     * @param obj 对象
     * @return 格式化的JSON字符串
     */
    public static String toPrettyJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("对象转格式化JSON失败：{}", obj, e);
            return null;
        }
    }

    /**
     * JSON字符串转对象
     *
     * @param json JSON字符串
     * @param clazz 对象类型
     * @param <T> 对象类型
     * @return 对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON转对象失败：json={}, class={}", json, clazz.getName(), e);
            return null;
        }
    }

    /**
     * JSON字符串转对象（支持泛型）
     *
     * <p>使用示例：
     * <pre>
     * List<Employee> employees = JsonUtil.fromJson(json, new TypeReference<List<Employee>>() {});
     * Map<String, Object> map = JsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
     * </pre>
     *
     * @param json JSON字符串
     * @param typeReference 类型引用
     * @param <T> 对象类型
     * @return 对象
     */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("JSON转对象失败：json={}, typeReference={}", json, typeReference.getType(), e);
            return null;
        }
    }

    /**
     * 获取ObjectMapper实例
     *
     * <p>用于特殊场景的自定义序列化和反序列化
     *
     * @return ObjectMapper
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
