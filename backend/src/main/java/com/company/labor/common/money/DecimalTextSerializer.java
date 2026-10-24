package com.company.labor.common.money;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;
import java.math.BigDecimal;

/** 将高精度值输出为不含指数的十进制字符串，避免前端按浮点数接收后丢失精度。 */
public final class DecimalTextSerializer extends StdScalarSerializer<BigDecimal> {
    /** Jackson 序列化器的版本标识。 */
    private static final long serialVersionUID = 1L;

    /** 登记本序列化器负责的十进制类型。 */
    public DecimalTextSerializer() {
        super(BigDecimal.class);
    }

    /**
     * 输出十进制文本；空值由 Jackson 的空值处理机制负责。
     * @param value 已通过所属值对象容量校验的数值
     * @param generator 当前 JSON 输出器
     * @param provider 当前序列化上下文
     * @throws IOException JSON 输出失败
     */
    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeString(value.toPlainString());
    }
}
