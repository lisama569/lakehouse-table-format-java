package com.github.lisama569.lakehousetableformatjava.table;

/**
 * 列名合法性校验（结构演进与建表共用）。
 *
 * <p>合法列名：非 null、去掉两端空白后非空、本身不含任何 ISO 控制字符
 * （例如换行、制表符、单元分隔符等），也不允许两端带空白。校验纯按名字
 * 字符串进行，与列身份（列 ID）无关。
 */
final class ColumnNames {

    private ColumnNames() {
    }

    /** 名字是否合法（不抛异常的判定形式）。 */
    static boolean isValid(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // 不允许两端空白，避免 " id " 与 "id" 两个写法造成的歧义
        if (!name.equals(name.strip())) {
            return false;
        }
        // 不允许任何控制字符（含换行、回车、制表符、KeyCodec 使用的分隔符等）
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 空白（null / 空串 / 全空白）单独判定，用于给出可区分的失败原因。 */
    static boolean isBlank(String name) {
        return name == null || name.isBlank();
    }
}
