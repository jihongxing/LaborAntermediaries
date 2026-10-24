package com.company.labor.foundation.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

/** 复用应用 JSON 配置，并提供按 Map 键排序的请求摘要输入；不承诺任意对象的规范化等价。 */
@Component
public class JsonCodec {
    /** 应用统一的 JSON 映射器。 */
    private final ObjectMapper mapper;
    /** 启用 Map 键排序的独立映射器，不修改应用共享实例。 */
    private final ObjectMapper canonical;

    /**
     * 创建常规与请求摘要两种 JSON 编解码入口。
     * @param mapper 应用统一映射器
     */
    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.canonical = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * 使用统一配置序列化对象。
     * @param value 待序列化数据，可为空
     * @return JSON 字符串
     * @throws IllegalStateException 数据无法序列化
     */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    /**
     * 生成 Map 键顺序稳定的 JSON，不改变数组顺序，也不统一数值或任意对象属性语义。
     * @param value 用于请求摘要的数据
     * @return 按 Map 键排序的 JSON 字符串
     * @throws IllegalStateException 数据无法序列化
     */
    public String canonical(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    /**
     * 将 JSON 还原为指定普通类型。
     * @param <T> 结果类型
     * @param json 待解析 JSON
     * @param type 目标类型
     * @return 反序列化结果
     * @throws IllegalArgumentException JSON 不合法或无法映射到目标类型
     */
    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    /**
     * 将 JSON 还原为保留泛型信息的类型，例如带元素类型的集合。
     * @param <T> 结果类型
     * @param json 待解析 JSON
     * @param type 保留泛型参数的目标类型引用
     * @return 反序列化结果
     * @throws IllegalArgumentException JSON 不合法或无法映射到目标类型
     */
    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }
}
