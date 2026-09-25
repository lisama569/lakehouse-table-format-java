package com.github.lisama569.lakehousetableformatjava.lakehouse;

import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LakehouseCatalog;
import com.github.lisama569.lakehousetableformatjava.lakehouse.table.LocalLakehouseCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 湖仓组件装配。 */
@Configuration
public class LakehouseConfig {

    @Bean
    public LakehouseCatalog lakehouseCatalog(@Value("${lakehouse.warehouse:./target/lakehouse-warehouse}") String warehouse) {
        return new LocalLakehouseCatalog(Path.of(warehouse));
    }
}
