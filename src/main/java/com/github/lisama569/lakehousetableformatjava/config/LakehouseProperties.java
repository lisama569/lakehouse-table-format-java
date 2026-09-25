package com.github.lisama569.lakehousetableformatjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地湖仓配置：warehouse 目录（默认 ./lakehouse-warehouse）。 */
@ConfigurationProperties(prefix = "lakehouse")
public class LakehouseProperties {

    /** 本地仓库根目录。 */
    private String warehouseDir = "lakehouse-warehouse";

    public String getWarehouseDir() {
        return warehouseDir;
    }

    public void setWarehouseDir(String warehouseDir) {
        this.warehouseDir = warehouseDir;
    }
}
