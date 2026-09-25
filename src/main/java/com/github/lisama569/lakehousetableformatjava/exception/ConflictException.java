package com.github.lisama569.lakehousetableformatjava.exception;

/**
 * 乐观并发冲突：客户端基于过期快照提交。
 * 携带期望版本与当前版本，便于解释判定依据。
 */
public class ConflictException extends LakehouseException {
    private final long expectedVersion;
    private final long currentVersion;

    public ConflictException(String table, long expectedVersion, long currentVersion) {
        super("conflict on table " + table + ": expected version " + expectedVersion
                + " but current version is " + currentVersion);
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
