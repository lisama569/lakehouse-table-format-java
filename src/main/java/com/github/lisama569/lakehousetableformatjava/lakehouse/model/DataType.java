package com.github.lisama569.lakehousetableformatjava.lakehouse.model;

/** 表字段支持的基础数据类型。 */
public enum DataType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN;

    /**
     * 将外部输入规范化为该类型的标准 Java 表示（String/Long/Double/Boolean）；
     * 不兼容（含会丢失信息的数值转换）时抛出 IllegalArgumentException，由上层归类为 TYPE_MISMATCH。
     */
    public Object normalize(Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (this) {
            case STRING -> {
                if (raw instanceof String s) {
                    yield s;
                }
                throw new IllegalArgumentException("期望 STRING，实际为 " + raw.getClass().getSimpleName());
            }
            case LONG -> {
                if (raw instanceof Long l) {
                    yield l;
                }
                if (raw instanceof Integer i) {
                    yield i.longValue();
                }
                if (raw instanceof Number n) {
                    // 仅接受无精度损失的整数值：1.0 可以，1.5 拒绝
                    double d = n.doubleValue();
                    if (!Double.isFinite(d) || d != Math.rint(d)
                            || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "期望 LONG（整数），实际值无法无损转换：" + raw);
                    }
                    yield (long) d;
                }
                throw new IllegalArgumentException("期望 LONG，实际为 " + raw.getClass().getSimpleName());
            }
            case DOUBLE -> {
                if (raw instanceof Number n) {
                    yield n.doubleValue();
                }
                throw new IllegalArgumentException("期望 DOUBLE，实际为 " + raw.getClass().getSimpleName());
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("期望 BOOLEAN，实际为 " + raw.getClass().getSimpleName());
            }
        };
    }
}
