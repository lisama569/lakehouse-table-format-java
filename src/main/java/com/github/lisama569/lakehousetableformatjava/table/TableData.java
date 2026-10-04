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
 * <p>胜负规则（全局唯一，读取、MERGE 写入、DELETE 与 compaction 共用）：
 * 比较行的来源坐标 {@code (commitVersion, seq)}，坐标大者胜。seq 是在快照
 * 文件列表内按“文件顺序 + 文件内行序”赋的全局单调序号，保证同一提交写入的
 * 多行也有确定先后（即最后写入胜出）。
 *
 * <p>删除以“墓碑行”（{@link Row#deleted()} = true）表示，与普通行走完全相同的
 * 合并规则：坐标更大的墓碑覆盖旧值（读不到该行），坐标更大的新行又能覆盖墓碑
 * （删除后重新插入）。墓碑不出现在 {@link #keys()} / {@link #liveRows()} 中，
 * 但保留在合并结构里，确保旧版本文件里的值不会在合并/整理时“复活”。
 */
final class TableData {

    record Entry(Row row, long commitVersion, long seq) {
        boolean isTombstone() {
            return row.deleted();
        }
    }

    private final Map<String, Entry> live = new LinkedHashMap<>();
    private final Schema schema;

    private TableData(Schema schema) {
        this.schema = schema;
    }

    /** 加载快照下所有有效条目（主键 -> 最新条目，墓碑也在内）。 */
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

    /** 该主键当前是否有“存活的行”（墓碑不算存活）。 */
    boolean contains(String key) {
        Entry e = live.get(key);
        return e != null && !e.isTombstone();
    }

    /** 当前存活行的主键集合（被删除的键不在其中，因此删除后可重新 APPEND）。 */
    Set<String> keys() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Entry> e : live.entrySet()) {
            if (!e.getValue().isTombstone()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 当前存活行（墓碑被过滤），用于读取投影与 MERGE/compaction 重写。 */
    List<Row> liveRows() {
        return live.values().stream().filter(e -> !e.isTombstone()).map(Entry::row).toList();
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
