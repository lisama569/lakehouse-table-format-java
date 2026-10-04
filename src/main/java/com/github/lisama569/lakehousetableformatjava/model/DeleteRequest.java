package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一次按主键删除的请求，可包含多条记录；整批校验，all-or-nothing。
 *
 * @param expectedVersion 客户端所基于的快照版本（OCC），null 表示不校验
 * @param keys            每条记录只需给出主键列（以列名为键）
 */
public record DeleteRequest(Long expectedVersion, List<Map<String, Object>> keys) {
}
