package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;

/** 结构演进请求，同样基于 expectedVersion 做乐观并发控制。 */
public record SchemaChangeRequest(Long expectedVersion, List<ColumnChange> changes) {
}
