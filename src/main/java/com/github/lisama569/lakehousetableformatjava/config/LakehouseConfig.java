package com.github.lisama569.lakehousetableformatjava.config;

import com.github.lisama569.lakehousetableformatjava.storage.Catalog;
import com.github.lisama569.lakehousetableformatjava.storage.LocalCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 装配本地 catalog bean。 */
@Configuration
@EnableConfigurationProperties(LakehouseProperties.class)
public class LakehouseConfig {

    @Bean
    public Catalog catalog(LakehouseProperties properties) {
        return new LocalCatalog(Path.of(properties.getWarehouseDir()));
    }
}
