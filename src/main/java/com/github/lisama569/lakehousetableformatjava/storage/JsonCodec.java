package com.github.lisama569.lakehousetableformatjava.storage;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.Map;

/**
 * 统一的 Jackson ObjectMapper 工厂。
 *
 * <p>数据行是 {@code Map<Integer,Object>}，JSON 自身不带整型位数信息：
 * 默认会把小整数反序列化为 Integer，导致同一 LONG 列在内存中与重载后 Java 类型
 * 不一致。这里把无小数的 JSON 数字统一读成 Long、带小数读成 Double，使持久化
 * 往返后类型稳定，比较与断言可解释。
 */
final class JsonCodec {

    private static final ObjectMapper MAPPER = build();

    private JsonCodec() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper build() {
        SimpleModule numbers = new SimpleModule();
        numbers.addDeserializer(Object.class, new JsonDeserializer<>() {
            @Override
            public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return switch (p.currentToken()) {
                    case VALUE_STRING -> p.getText();
                    case VALUE_TRUE -> Boolean.TRUE;
                    case VALUE_FALSE -> Boolean.FALSE;
                    case VALUE_NULL -> null;
                    case VALUE_NUMBER_INT -> p.getLongValue();
                    case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
                    case START_OBJECT -> p.readValueAs(Map.class);
                    case START_ARRAY -> ctxt.readValue(p, Object[].class);
                    default -> throw new IOException("unsupported json token: " + p.currentToken());
                };
            }
        });
        return new ObjectMapper()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .findAndRegisterModules()
                .registerModule(numbers)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
