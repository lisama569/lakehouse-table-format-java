package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一次按主键的批量删除请求（只接受主键列，按主键定位记录）。
 *
 * @param expectedVersion 客户端所基于的快照版本（OCC），null 表示不校验
 * @param keys            要删除的主键（单列主键时形如 {@code [{"id":1},{"id":2}]}）
 */
public record DeleteRequest(Long expectedVersion, List<Map<String, Object>> keys) {
}
