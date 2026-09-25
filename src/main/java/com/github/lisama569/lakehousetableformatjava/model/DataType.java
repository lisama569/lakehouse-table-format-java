package com.github.lisama569.lakehousetableformatjava.model;

/**
 * 支持的列数据类型。
 *
 * <p>反序列化（本地 JSON 文件 / HTTP JSON）后，标量值的 Java 类型遵循 Jackson
 * 映射：字符串为 String、整数为 Long、浮点为 Double、布尔为 Boolean。
 * {@link #coerce(Object)} 做严格但确定的规范化，数值只在“无精度损失”时转换，
 * 例如 1 可写入 LONG 列，1.5 写入 LONG 列会被拒绝（TYPE_MISMATCH）。
 *
 * <p>{@link #parseDefault(String)} 仅用于列声明中的缺省值（缺省值在 schema
 * 中以字符串保存），允许把 "7" 解析为 LONG、"true" 解析为 BOOLEAN。
 */
public enum DataType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN;

    /**
     * 校验给定原始值是否符合本类型，返回规范化后的值；无法安全转换时抛
     * {@link IllegalArgumentException}，由写入校验层转成 TYPE_MISMATCH 拒绝原因。
     */
    public Object coerce(Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (this) {
            case STRING -> raw instanceof String s ? s : raw.toString();
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("expected boolean but got " + typeName(raw));
            }
            case LONG -> {
                if (raw instanceof Long l) {
                    yield l;
                }
                if (raw instanceof Integer i) {
                    yield i.longValue();
                }
                if (raw instanceof Number n && isIntegral(n)) {
                    yield n.longValue();
                }
                throw new IllegalArgumentException("expected long but got " + typeName(raw));
            }
            case DOUBLE -> {
                if (raw instanceof Double d) {
                    yield d;
                }
                if (raw instanceof Number n) {
                    if (isFiniteNumber(n)) {
                        yield n.doubleValue();
                    }
                }
                throw new IllegalArgumentException("expected double but got " + typeName(raw));
            }
        };
    }

    /**
     * 解析列声明缺省值（始终是字符串）。无法解析时抛 IllegalArgumentException，
     * 由结构演进层转成明确的演进错误。
     */
    public Object parseDefault(String literal) {
        if (literal == null) {
            return null;
        }
        return switch (this) {
            case STRING -> literal;
            case LONG -> {
                try {
                    yield Long.parseLong(literal.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid long default: " + literal);
                }
            }
            case DOUBLE -> {
                try {
                    double d = Double.parseDouble(literal.trim());
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        throw new IllegalArgumentException("non-finite double default: " + literal);
                    }
                    yield d;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid double default: " + literal);
                }
            }
            case BOOLEAN -> {
                String b = literal.trim();
                if (b.equalsIgnoreCase("true")) {
                    yield Boolean.TRUE;
                }
                if (b.equalsIgnoreCase("false")) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("invalid boolean default: " + literal);
            }
        };
    }

    private static boolean isIntegral(Number n) {
        double d = n.doubleValue();
        return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)
                && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE;
    }

    private static boolean isFiniteNumber(Number n) {
        double d = n.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static String typeName(Object raw) {
        return raw.getClass().getSimpleName() + "(" + raw + ")";
    }
}
