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
    void primaryKeyRenameOverHttpThenWriteDeleteAndHistoryAllWork() throws Exception {
        // 复合主键建表
        String createBody = """
                {"columns":[
                  {"name":"tenant","type":"LONG","nullable":false},
                  {"name":"sku","type":"LONG","nullable":false},
                  {"name":"v","type":"STRING","nullable":true}
                ],"primaryKey":["tenant","sku"]}""";
        mvc.perform(post("/api/tables/inventory").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tables/inventory/write").contentType(MediaType.APPLICATION_JSON).content("""
                {"mode":"APPEND","expectedVersion":0,
                 "records":[{"tenant":1,"sku":10,"v":"a"},{"tenant":2,"sku":20,"v":"b"}]}"""))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(1));

        // 主键列改名 sku -> itemSku，提交为 v2
        String rename = """
                {"expectedVersion":1,"changes":[{"kind":"RENAME","columnName":"sku","newName":"itemSku"}]}""";
        mvc.perform(post("/api/tables/inventory/schema").contentType(MediaType.APPLICATION_JSON).content(rename))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.schema.primaryKey[1]").value("itemSku"));
        LOG.info("[rest] primary key column sku->itemSku committed -> v2, tenant unchanged");

        // 历史 v1 仍按旧名 sku 读出
        mvc.perform(get("/api/tables/inventory/rows?version=1"))
                .andExpect(jsonPath("$[0].sku").value(10))
                .andExpect(jsonPath("$[0].itemSku").doesNotExist());
        // 头快照用新主键名读到原值
        mvc.perform(get("/api/tables/inventory/rows"))
                .andExpect(jsonPath("$[0].itemSku").value(10));

        // 用新主键名追加
        mvc.perform(post("/api/tables/inventory/write").contentType(MediaType.APPLICATION_JSON).content("""
                {"mode":"APPEND","expectedVersion":2,
                 "records":[{"tenant":1,"itemSku":30,"v":"c"}]}"""))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(3));
        // 用新主键名按主键删除
        mvc.perform(post("/api/tables/inventory/delete").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedVersion":3,"keys":[{"tenant":2,"itemSku":20}]}"""))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(4));
        mvc.perform(get("/api/tables/inventory/rows"))
                .andExpect(jsonPath("$.length()").value(2));
        LOG.info("[rest] append/delete by renamed composite key itemSku OK -> head v4");

        // 改成已存在列名 -> 400 INVALID_SCHEMA_EVOLUTION，版本不推进
        mvc.perform(post("/api/tables/inventory/schema").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedVersion":4,"changes":[{"kind":"RENAME","columnName":"tenant","newName":"v"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SCHEMA_EVOLUTION"));
        // 空白新名 -> 400，原因可区分
        mvc.perform(post("/api/tables/inventory/schema").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedVersion":4,"changes":[{"kind":"RENAME","columnName":"tenant","newName":"   "}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SCHEMA_EVOLUTION"));
        // 过期基版本改名 -> 409 SNAPSHOT_CONFLICT
        mvc.perform(post("/api/tables/inventory/schema").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedVersion":1,"changes":[{"kind":"RENAME","columnName":"tenant","newName":"org"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_CONFLICT"));
        LOG.warn("[rest:reject] pk rename duplicate/blank -> 400, stale base -> 409; head stays v4");
    }

    @Test
    void unknownTableAndTableErrorsAreMapped() throws Exception {        mvc.perform(get("/api/tables/ghost/rows"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TABLE_NOT_FOUND"));
        LOG.warn("[rest:reject] HTTP 404 errorCode=TABLE_NOT_FOUND");
    }

    @Test
    void deleteAndRetentionOverHttp() throws Exception {
        String createBody = """
                {"columns":[
                  {"name":"id","type":"LONG","nullable":false},
                  {"name":"v","type":"STRING","nullable":true}
                ],"primaryKey":["id"]}""";
        mvc.perform(post("/api/tables/orders").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());

        mvc.perform(post("/api/tables/orders/write").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"APPEND","expectedVersion":0,
                                 "records":[{"id":1,"v":"a"},{"id":2,"v":"b"},{"id":3,"v":"c"}]}"""))
                .andExpect(jsonPath("$.committed").value(true));

        // 删除两条：当前不可见，历史可见
        mvc.perform(post("/api/tables/orders/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"keys":[{"id":1},{"id":3}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(2))
                .andExpect(jsonPath("$.acceptedCount").value(2));
        mvc.perform(get("/api/tables/orders/rows"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2));
        mvc.perform(get("/api/tables/orders/rows?version=1"))
                .andExpect(jsonPath("$.length()").value(3));
        LOG.info("[rest] delete committed -> v2, current 1 row, history v1 still 3 rows");

        // 删除不存在的主键：整批拒绝，原因可区分
        mvc.perform(post("/api/tables/orders/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"keys":[{"id":42}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.rejected[0].reason").value("PRIMARY_KEY_NOT_FOUND"));
        LOG.warn("[rest:reject] delete reason=PRIMARY_KEY_NOT_FOUND committed=false");

        // 过期快照删除 -> 409
        mvc.perform(post("/api/tables/orders/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"keys":[{"id":2}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_CONFLICT"));

        // MERGE 重写为单文件 -> v3，旧文件只被历史快照引用
        mvc.perform(post("/api/tables/orders/write").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"MERGE","expectedVersion":2,"records":[{"id":2,"v":"b2"}]}"""))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.committedVersion").value(3));

        // 保留最近 1 个快照：v0..v2 过期，不再被引用的两个旧文件被删除
        mvc.perform(post("/api/tables/orders/retention").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keepLastSnapshots":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headVersion").value(3))
                .andExpect(jsonPath("$.retainedSnapshotVersions[0]").value(3))
                .andExpect(jsonPath("$.deletedDataFiles.length()").value(2));
        mvc.perform(get("/api/tables/orders/rows"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].v").value("b2"));
        mvc.perform(get("/api/tables/orders/rows?version=1"))
                .andExpect(status().isBadRequest());
        LOG.info("[rest] retention keepLast=1 -> expired v0..v2, head v3 still readable, v1 gone");

        // 非法保留策略 -> 400
        mvc.perform(post("/api/tables/orders/retention").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keepLastSnapshots":1,"retainFromVersion":2}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
