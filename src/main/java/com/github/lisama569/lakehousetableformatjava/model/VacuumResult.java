package com.github.lisama569.lakehousetableformatjava.model;

import java.util.List;

/**
 * 一次空间回收的结果：哪些快照被过期删除、哪些数据文件被物理删除、
 * 哪些数据文件虽然只剩唯一保留快照引用但仍被保留。
 *
 * @param headVersionAtStart 回收开始时读取到的头版本
 * @param headVersionAtEnd   回收结束时的头版本（与 start 不同说明期间有并发提交）
 * @param retainedSnapshots  保留下来的快照版本（升序）
 * @param expiredSnapshots   被删除的快照版本（升序）
 * @param deletedDataFiles   被物理删除的数据文件路径
 * @param retainedDataFiles  仍被保留快照引用的数据文件路径
 */
public record VacuumResult(long headVersionAtStart,
                           long headVersionAtEnd,
                           List<Long> retainedSnapshots,
                           List<Long> expiredSnapshots,
                           List<String> deletedDataFiles,
                           List<String> retainedDataFiles) {
}
