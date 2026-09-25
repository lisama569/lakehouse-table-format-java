package com.github.lisama569.lakehousetableformatjava.table;

import java.util.List;

/**
 * 复合主键的确定性字符串编码：用 U+001F（单元分隔符）连接各列。
 * 仅用于进程内比较，不跨版本持久化；null 部分编码为空串。
 */
final class KeyCodec {

    private static final String SEP = "\u001f";

    private KeyCodec() {
    }

    static String encode(List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            Object p = parts.get(i);
            if (p != null) {
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
