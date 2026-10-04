package com.github.lisama569.lakehousetableformatjava.model;

/**
 * 快照保留策略：两种指定方式恰好填一个。
 *
 * @param keepLastSnapshots 只保留最近 N 个快照（含当前头快照），N >= 1
 * @param retainFromVersion 保留版本号 >= 该值的所有快照（头快照始终保留，
 *                          若该值大于头版本则按头版本处理）
 */
public record RetentionRequest(Integer keepLastSnapshots, Long retainFromVersion) {

    public RetentionRequest {
        boolean hasKeepLast = keepLastSnapshots != null;
        boolean hasFromVersion = retainFromVersion != null;
        if (hasKeepLast == hasFromVersion) {
            throw new IllegalArgumentException(
                    "exactly one of keepLastSnapshots / retainFromVersion must be set");
        }
        if (hasKeepLast && keepLastSnapshots < 1) {
            throw new IllegalArgumentException("keepLastSnapshots must be >= 1");
        }
        if (hasFromVersion && retainFromVersion < 0) {
            throw new IllegalArgumentException("retainFromVersion must be >= 0");
        }
    }
}
