package com.github.lisama569.lakehousetableformatjava.lakehouse.table;

import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.CommitReport;
import com.github.lisama569.lakehousetableformatjava.lakehouse.commit.WriteMode;
import com.github.lisama569.lakehousetableformatjava.lakehouse.evolve.SchemaChange;
import com.github.lisama569.lakehousetableformatjava.lakehouse.model.TableSchema;
import com.github.lisama569.lakehousetableformatjava.lakehouse.snapshot.Snapshot;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 一张本地湖仓表的对外一致接口。 */
public interface Table {

    String name();

    /** 当前最新快照（已提交版本）。 */
    Snapshot currentSnapshot();

    /** 按表版本号获取历史快照，不存在则抛 SNAPSHOT_NOT_FOUND。 */
    Snapshot snapshotAt(long version);

    /** 某版本对应的 schema。 */
    TableSchema schemaAt(long version);

    /** 列出全部历史快照（版本升序）。 */
    List<Snapshot> listSnapshots();

    /**
     * 基于 baseVersion 做追加/合并写入。
     * 输入为 列名->值 的行集合；baseVersion 过期或任何一行非法则整批拒绝。
     */
    CommitReport write(long baseVersion, WriteMode mode, List<Map<String, Object>> rows);

    /** 基于 baseVersion 提交结构变更。 */
    CommitReport evolve(long baseVersion, Collection<SchemaChange> changes);

    /** 基于 baseVersion 整理数据文件；只产生新快照，查询结果不得变化。 */
    CommitReport compact(long baseVersion);

    /**
     * 以快照隔离读取某版本：返回主键 -> (列名->值)，
     * 同一快照在任何时候读取结果一致。
     */
    Map<String, Map<String, Object>> readAt(long version);

    /** 读取最新版本。 */
    Map<String, Map<String, Object>> readLatest();
}
