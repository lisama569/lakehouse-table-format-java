package com.github.lisama569.lakehousetableformatjava.storage;

import com.github.lisama569.lakehousetableformatjava.model.DataFileRef;
import com.github.lisama569.lakehousetableformatjava.model.Row;
import com.github.lisama569.lakehousetableformatjava.model.Snapshot;

import java.util.List;

/**
 * 表目录的本地存储抽象：快照/数据文件的读写与原子头指针更新。
 * 主键信息内嵌在每个快照的 schema 中，因此不需要独立的元数据文件。
 */
public interface TableStorage {

    /** 追加写入一个不可变快照文件（文件名由版本决定）。 */
    void writeSnapshot(Snapshot snapshot);

    Snapshot readSnapshot(long version);

    List<Snapshot> readAllSnapshots();

    /** 原子更新 HEAD 指针（临时文件 + ATOMIC_MOVE）。 */
    void casHead(long expectedHeadVersion, long newHeadVersion);

    long readHeadVersion();

    /** 写入不可变数据文件，返回带统计信息的引用。 */
    DataFileRef writeDataFile(String fileName, List<Row> rows, long maxCommitVersion);

    /** 读取数据文件中的全部行（列 ID -> 值）。 */
    List<Row> readDataFile(String path);

    /** 列出数据目录下全部数据文件的相对路径（data/ 开头）。 */
    List<String> listDataFiles();

    /** 删除一个不再被任何保留快照引用的数据文件。 */
    void deleteDataFile(String path);

    /** 删除一个已过期的快照文件。 */
    void deleteSnapshot(long version);
}
