package com.github.lisama569.lakehousetableformatjava.model;

/**
 * 空间回收（vacuum）的保留策略：两种方式二选一。
 *
 * <ul>
 *   <li>{@code retainAtLeastVersion(minVersion)}：保留版本号 &gt;= minVersion 的全部快照；</li>
 *   <li>{@code retainLast(n)}：只保留最近 n 个快照（始终至少保留当前头快照，n &gt;= 1）。</li>
 * </ul>
 * 被保留快照引用的数据文件一律不删；只被已过期快照引用的数据文件才会被物理删除。
 */
public record RetentionPolicy(Long minVersion, Integer retainLastSnapshots) {

    public static RetentionPolicy retainAtLeastVersion(long minVersion) {
        return new RetentionPolicy(minVersion, null);
    }

    public static RetentionPolicy retainLast(int n) {
        return new RetentionPolicy(null, n);
    }
}
