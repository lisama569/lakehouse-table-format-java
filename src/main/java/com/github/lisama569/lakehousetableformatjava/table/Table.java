package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.RetentionPolicy;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
import com.github.lisama569.lakehousetableformatjava.model.VacuumResult;
import com.github.lisama569.lakehousetableformatjava.model.WriteRequest;
import com.github.lisama569.lakehousetableformatjava.model.WriteResult;

import java.util.List;
import java.util.Map;

/**
 * 一张本地湖仓表的对外接口：写入、快照读、结构演进、整理与历史浏览。
 */
public interface Table {

    /** 表名。 */
    String name();

    /** 当前头快照（始终已提交）。 */
    Snapshot currentSnapshot();

    /** 按版本读取已提交快照；不存在抛出 IllegalArgumentException。 */
    Snapshot snapshotAt(long version);

    /** 列出全部已提交快照（按版本升序）。 */
    List<Snapshot> history();

    /**
     * 提交一次写入。整批校验：存在非法记录时全部拒绝（不产生任何副作用），
     * 原因逐条可区分；并发冲突抛 {@link
     * com.github.lisama569.lakehousetableformatjava.exception.ConflictException}。
     */
    WriteResult commit(WriteRequest request);

    /** 提交一次结构演进，返回演进后的头快照。 */
    Snapshot evolve(SchemaChangeRequest request);

    /**
     * 数据文件整理：把当前快照全部文件重写合并为更少的文件，
     * 生成新快照；整理前后按任意快照查询的结果必须完全一致。
     */
    Snapshot compact();

    /**
     * 按主键批量删除记录（写入删除墓碑）。整批校验：批内主键重复、主键缺失、
     * 主键在当前快照不存在等问题逐条列出，有任何问题整批不提交。
     * 删除只影响删除之后的快照，历史版本仍能读到被删的行。
     */
    WriteResult delete(DeleteRequest request);

    /**
     * 按保留策略回收空间：过期删除更早的快照，并物理删除不再被任何保留快照
     * 引用的数据文件；保留范围内的历史查询结果必须与回收前逐条一致。
     */
    VacuumResult vacuum(RetentionPolicy policy);

    /**
     * 按给定快照查询数据，返回以列名为键的行（投影到该快照的 schema）。
     */
    List<Map<String, Object>> readRows(Snapshot snapshot);

    /** 便捷方法：按版本查询。 */
    List<Map<String, Object>> readAt(long version);

    /** 当前头快照查询。 */
    List<Map<String, Object>> readLatest();
}
