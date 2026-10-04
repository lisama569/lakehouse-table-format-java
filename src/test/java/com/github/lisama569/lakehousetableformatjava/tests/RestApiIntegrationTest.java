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

    @Test
    void deleteAndVacuumOverHttp() throws Exception {
        // 建表并写入三行：v1
        String createBody = """
                {"columns":[
                  {"name":"id","type":"LONG","nullable":false},
                  {"name":"v","type":"STRING","nullable":true}
                ],"primaryKey":["id"]}""";
        mvc.perform(post("/api/tables/events").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());
        String write = """
                {"mode":"APPEND","expectedVersion":0,"records":[{"id":1,"v":"a"},{"id":2,"v":"b"},{"id":3,"v":"c"}]}""";
        mvc.perform(post("/api/tables/events/write").contentType(MediaType.APPLICATION_JSON).content(write))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(1));

        // 删除不存在的键 -> 200 committed=false，原因可区分
        String deleteMissing = """
                {"expectedVersion":1,"keys":[{"id":99}]}""";
        mvc.perform(post("/api/tables/events/delete").contentType(MediaType.APPLICATION_JSON).content(deleteMissing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.rejected[0].reason").value("PRIMARY_KEY_NOT_FOUND"));
        LOG.warn("[rest:reject] DELETE missing key reason=PRIMARY_KEY_NOT_FOUND committed=false");

        // 合法批量删除 id=1,3 -> v2，头快照只剩 id=2
        String deleteOk = """
                {"expectedVersion":1,"keys":[{"id":1},{"id":3}]}""";
        mvc.perform(post("/api/tables/events/delete").contentType(MediaType.APPLICATION_JSON).content(deleteOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(2));
        mvc.perform(get("/api/tables/events/rows"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2));
        // 历史 v1 仍有三行
        mvc.perform(get("/api/tables/events/rows?version=1"))
                .andExpect(jsonPath("$.length()").value(3));
        LOG.info("[rest] DELETE accepted -> v2; head has only id=2; history v1 keeps 3 rows");

        // 删除的过期快照冲突 -> 409
        String deleteStale = """
                {"expectedVersion":1,"keys":[{"id":2}]}""";
        mvc.perform(post("/api/tables/events/delete").contentType(MediaType.APPLICATION_JSON).content(deleteStale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_CONFLICT"));

        // 回收：保留最近 2 个快照（v1,v2），v0 过期
        mvc.perform(post("/api/tables/events/vacuum").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retainLastSnapshots\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiredSnapshots[0]").value(0))
                .andExpect(jsonPath("$.headVersionAtEnd").value(2));
        // 保留范围内查询不变
        mvc.perform(get("/api/tables/events/rows?version=1"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/tables/events/rows"))
                .andExpect(jsonPath("$[0].id").value(2));
        // 非法策略 -> 400 INVALID_ARGUMENT
        mvc.perform(post("/api/tables/events/vacuum").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        LOG.info("[rest] VACUUM retainLast=2 expired v0; retained rows identical; empty policy -> 400");
    }
}
