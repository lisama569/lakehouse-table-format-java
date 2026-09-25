package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一次写入请求。
 *
 * @param expectedVersion 客户端所基于的快照版本（OCC），null 表示只追加且不校验
 * @param records         以列名为键的记录
 */
public record WriteRequest(WriteMode mode, Long expectedVersion, List<Map<String, Object>> records) {
}
