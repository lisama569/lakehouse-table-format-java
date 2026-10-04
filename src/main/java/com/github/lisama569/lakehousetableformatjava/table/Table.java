package com.github.lisama569.lakehousetableformatjava.table;

import com.github.lisama569.lakehousetableformatjava.model.DeleteRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionRequest;
import com.github.lisama569.lakehousetableformatjava.model.RetentionResult;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Schema;
import com.github.lisama569.lakehousetableformatjava.model.SchemaChangeRequest;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;
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

    /**
     * 按主键删除一批记录。整批校验：主键缺失、批内重复、主键不存在等原因
     * 逐条可区分，任一非法即整批拒绝（不产生任何副作用）。删除以墓碑文件
     * 提交：当前快照不再可见这些主键，历史快照照常可读。
     */
    WriteResult delete(DeleteRequest request);

    /** 提交一次结构演进，返回演进后的头快照。 */
    Snapshot evolve(SchemaChangeRequest request);

    /**
     * 数据文件整理：把当前快照全部文件重写合并为更少的文件，
     * 生成新快照；整理前后按任意快照查询的结果必须完全一致。
     */
    Snapshot compact();

    /**
     * 按保留策略回收空间：清除保留范围之外的快照文件，并删除不再被任何
     * 保留快照引用的数据文件。当前头快照始终被保留；保留范围内的历史
     * 查询结果与回收前逐条一致。与提交共用同一把表级锁，不会删掉
     * 刚好被新快照引用的文件。
     */
    RetentionResult expireSnapshots(RetentionRequest request);

    /**
     * 按给定快照查询数据，返回以列名为键的行（投影到该快照的 schema）。
     */
    List<Map<String, Object>> readRows(Snapshot snapshot);

    /** 便捷方法：按版本查询。 */
    List<Map<String, Object>> readAt(long version);

    /** 当前头快照查询。 */
    List<Map<String, Object>> readLatest();
}
