package com.github.lisama569.lakehousetableformatjava.tests;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.DataType;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LocalLakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmokeWiringTest {

    @Test
    void createWriteReloadRead(@TempDir Path dir) {
        LakehouseCatalog catalog = new LocalLakehouseCatalog(dir);
        Table t = catalog.createTable("t1",
                List.of(new LakehouseCatalog.ColumnSpec("id", DataType.LONG, false, null),
                        new LakehouseCatalog.ColumnSpec("name", DataType.STRING, true, null)),
                List.of("id"));
        t.write(0, WriteMode.APPEND, List.of(
                Map.of("id", 1, "name", "a"),
                Map.of("id", 2, "name", "b")));

        Table reloaded = new LocalLakehouseCatalog(dir).loadTable("t1");
        var rows = reloaded.readLatest();
        assertEquals(2, rows.size());
        assertEquals("a", rows.values().iterator().next().get("name"));
    }
}
