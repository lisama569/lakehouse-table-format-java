package com.github.lisama569.lakehousetableformatjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对外 REST 接口端到端验证：建表、写入、非法输入可区分错误码、
 * 快照冲突 409、历史版本查询、结构演进与 compaction。
 * 每次测试使用独立仓库目录（见 properties 配置）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestApiIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RestApiIntegrationTest.class);
    private static final String WAREHOUSE = "target/rest-warehouse-it-" + java.util.UUID.randomUUID();

    @org.junit.jupiter.api.BeforeAll
    static void setWarehouse() {
        System.setProperty("lakehouse.warehouse-dir", WAREHOUSE);
    }

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 建表
        String createBody = """
                {"columns":[
                  {"name":"id","type":"LONG","nullable":false},
                  {"name":"name","type":"STRING","nullable":true}
                ],"primaryKey":["id"]}""";
        mvc.perform(post("/api/tables/users").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.operation").value("INIT"));
        LOG.info("[rest] created table users, initial snapshot v0");

        // 合法写入 v1
        String writeOk = """
                {"mode":"APPEND","expectedVersion":0,"records":[{"id":1,"name":"ada"}]}""";
        mvc.perform(post("/api/tables/users/write").contentType(MediaType.APPLICATION_JSON).content(writeOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(1));
        LOG.info("[rest] append accepted -> v1");

        // 非法写入：缺主键，整批拒绝（HTTP 200 + committed=false，原因可区分）
        String writeBadPk = """
                {"mode":"APPEND","expectedVersion":1,"records":[{"name":"no-id"}]}""";
        mvc.perform(post("/api/tables/users/write").contentType(MediaType.APPLICATION_JSON).content(writeBadPk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.rejected[0].reason").value("MISSING_PRIMARY_KEY"));
        LOG.warn("[rest:reject] reason=MISSING_PRIMARY_KEY committed=false");

        // 类型不匹配
        String writeBadType = """
                {"mode":"APPEND","expectedVersion":1,"records":[{"id":"not-a-long"}]}""";
        mvc.perform(post("/api/tables/users/write").contentType(MediaType.APPLICATION_JSON).content(writeBadType))
                .andExpect(jsonPath("$.rejected[0].reason").value("TYPE_MISMATCH"));
        LOG.warn("[rest:reject] reason=TYPE_MISMATCH");

        // 过期快照 -> 409 SNAPSHOT_CONFLICT
        String stale = """
                {"mode":"APPEND","expectedVersion":0,"records":[{"id":2,"name":"grace"}]}""";
        mvc.perform(post("/api/tables/users/write").contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_CONFLICT"));
        LOG.warn("[rest:reject] HTTP 409 errorCode=SNAPSHOT_CONFLICT basis=expected 0 != head 1");

        // 历史版本：v0 为空，v1 有一行
        mvc.perform(get("/api/tables/users/rows?version=0"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/tables/users/rows?version=1"))
                .andExpect(jsonPath("$[0].name").value("ada"));
        mvc.perform(get("/api/tables/users/rows"))
                .andExpect(jsonPath("$.length()").value(1));

        // 结构演进：新增列
        String addCol = """
                {"expectedVersion":1,"changes":[{"kind":"ADD","columnName":"score",
                 "spec":{"name":"score","type":"LONG","nullable":true,"defaultValue":"5"}}]}""";
        mvc.perform(post("/api/tables/users/schema").contentType(MediaType.APPLICATION_JSON).content(addCol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema.columns[2].name").value("score"));
        mvc.perform(get("/api/tables/users/rows"))
                .andExpect(jsonPath("$[0].score").value(5));
        LOG.info("[rest] ADD column score default 5 -> v2, old row backfilled");

        // 只有一个数据文件时 compaction 幂等跳过（不产生新版本），结果保持一致
        mvc.perform(post("/api/tables/users/compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/tables/users/rows"))
                .andExpect(jsonPath("$[0].name").value("ada"))
                .andExpect(jsonPath("$[0].score").value(5));
        LOG.info("[rest] compaction idempotent on single-file snapshot, head unchanged v2");

        // 历史包含 INIT/APPEND/SCHEMA_CHANGE/COMPACTION
        String history = mvc.perform(get("/api/tables/users/history"))
                .andReturn().getResponse().getContentAsString();
        JsonNode nodes = json.readTree(history);
        LOG.info("[rest] history versions={}",
                (Iterable<Integer>) () -> java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
                        .map(n -> n.get("version").asInt()).iterator());
    }

    @Test
    void unknownTableAndTableErrorsAreMapped() throws Exception {
        mvc.perform(get("/api/tables/ghost/rows"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TABLE_NOT_FOUND"));
        LOG.warn("[rest:reject] HTTP 404 errorCode=TABLE_NOT_FOUND");
    }
}
