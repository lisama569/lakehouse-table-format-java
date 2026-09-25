package com.github.lisama569.lakehousetableformatjava.model;

/**
 * 不可变数据文件引用：文件中序列化的是按列 ID 存储的行。
 * 整理（compaction）会把多个文件合并成新文件，记录数不变。
 */
public record DataFileRef(String path, long recordCount, long maxCommitVersion) {
}
