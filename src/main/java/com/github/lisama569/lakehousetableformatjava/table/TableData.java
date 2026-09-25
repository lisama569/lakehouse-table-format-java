package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.model.DataFileRef;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.storage.TableStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把快照引用的全部不可变数据文件按主键合并为当前有效行。
 *
 * <p>胜负规则（全局唯一，读取、MERGE 写入与 compaction 共用）：
 * 比较行的来源坐标 {@code (commitVersion, seq)}，坐标大者胜。seq 是在快照
 * 文件列表内按“文件顺序 + 文件内行序”赋的全局单调序号，保证同一提交写入的
 * 多行也有确定先后（即最后写入胜出）。
 */
final class TableData {

    record Entry(Row row, long commitVersion, long seq) {
    }

    private final Map<String, Entry> live = new LinkedHashMap<>();
    private final Schema schema;

    private TableData(Schema schema) {
        this.schema = schema;
    }

    /** 加载快照下所有有效行（主键 -> 行）。 */
    static TableData load(TableStorage storage, Snapshot snapshot) {
        TableData data = new TableData(snapshot.schema());
        long seq = 0;
        for (DataFileRef ref : snapshot.files()) {
            List<Row> rows = storage.readDataFile(ref.path());
            for (Row row : rows) {
                String key = data.keyOf(row);
                Entry incoming = new Entry(row, ref.maxCommitVersion(), seq++);
                Entry existing = data.live.get(key);
                if (existing == null || greater(incoming, existing)) {
                    data.live.put(key, incoming);
                }
            }
        }
        return data;
    }

    static boolean greater(Entry a, Entry b) {
        if (a.commitVersion() != b.commitVersion()) {
            return a.commitVersion() > b.commitVersion();
        }
        return a.seq() > b.seq();
    }

    String keyOf(Row row) {
        List<Object> parts = new ArrayList<>();
        for (int id : schema.primaryKeyIds()) {
            parts.add(row.values().get(id));
        }
        return KeyCodec.encode(parts);
    }

    boolean contains(String key) {
        return live.containsKey(key);
    }

    Set<String> keys() {
        return live.keySet();
    }

    List<Row> liveRows() {
        return live.values().stream().map(Entry::row).toList();
    }

    /**
     * 用一批新行（同一次提交，commitVersion 相同）覆盖/新增。
     * 新行的 seq 必然大于任何已加载行（从 Long.MAX_VALUE/2 起按行递增），
     * 因此同批次后出现的记录确定地覆盖先出现的记录（批次内去重已在
     * 校验阶段拒绝，这里仅做防御性保证）。
     */
    void overwriteWithNewRows(List<Row> rows, long commitVersion) {
        long base = 1L << 50;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            live.put(keyOf(row), new Entry(row, commitVersion, base + i));
        }
    }
}
