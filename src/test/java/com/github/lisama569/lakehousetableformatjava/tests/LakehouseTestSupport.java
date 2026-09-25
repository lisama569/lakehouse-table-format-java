package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LocalLakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;

import java.nio.file.Path;
import java.util.List;

/** 测试辅助：在临时目录中构建固定结构的示例表（id LONG PK, name STRING, score DOUBLE）。 */
final class LakehouseTestSupport {

    private LakehouseTestSupport() {}

    static LocalLakehouseCatalog catalog(Path dir) {
        return new LocalLakehouseCatalog(dir);
    }

    static Table exampleTable(LakehouseCatalog catalog, String name) {
        return catalog.createTable(name,
                List.of(
                        new LakehouseCatalog.ColumnSpec("id", DataType.LONG, false, null),
                        new LakehouseCatalog.ColumnSpec("name", DataType.STRING, false, null),
                        new LakehouseCatalog.ColumnSpec("score", DataType.DOUBLE, true, 0.0)),
                List.of("id"));
    }
}
