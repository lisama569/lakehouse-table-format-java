package com.github.lisama569.lakehousetableformatjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "lakehouse.warehouse=${java.io.tmpdir}/lh-web-test")
class WebApiTest {

    @LocalServerPort
    int port;

    private final TestRestTemplate rest = new TestRestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    private String base;
    private String table;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        base = "http://localhost:" + port + "/api/tables";
        table = "users_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String url(String suffix) {
        return base + suffix.replace("{t}", table);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object... kv) {
        var m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void endToEndThroughHttp() throws Exception {
        // 建表
        var createReq = map(
                "tableName", table,
                "primaryKey", List.of("id"),
                "columns", List.of(
                        map("name", "id", "type", "LONG", "nullable", false),
                        map("name", "name", "type", "STRING", "nullable", false),
                        map("name", "score", "type", "DOUBLE", "nullable", true, "defaultValue", 0.0)));
        ResponseEntity<String> created = rest.exchange(url("/{t}"), HttpMethod.PUT,
                new HttpEntity<>(createReq), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        JsonNode createdBody = json.readTree(created.getBody());
        assertEquals(0, createdBody.path("current").path("version").asInt());
        String snap0 = createdBody.path("current").path("snapshotId").asText();
        assertNotNull(snap0);

        // 写入 v1
        var writeReq = map("baseVersion", 0, "mode", "APPEND",
                "rows", List.of(map("id", 1, "name", "alice", "score", 10),
                                map("id", 2, "name", "bob", "score", 20)));
        ResponseEntity<String> written = rest.postForEntity(url("/{t}/write"), writeReq, String.class);
        assertEquals(HttpStatus.OK, written.getStatusCode());
        JsonNode commit = json.readTree(written.getBody()).path("commit");
        assertEquals(1, commit.path("version").asInt());
        assertFalse(commit.path("snapshotId").asText().isBlank());
        System.out.printf("[HTTP] 提交版本 v%s 快照 %s 依据 %s%n",
                commit.path("version").asText(), commit.path("snapshotId").asText(),
                commit.path("basis").asText());

        // 过期快照写入 -> 409 STALE_SNAPSHOT
        ResponseEntity<String> stale = rest.postForEntity(url("/{t}/write"),
                map("baseVersion", 0, "mode", "APPEND", "rows", List.of(map("id", 9, "name", "x"))),
                String.class);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        JsonNode staleErr = json.readTree(stale.getBody());
        assertEquals("STALE_SNAPSHOT", staleErr.path("reason").asText());

        // 缺主键 -> 409 MISSING_PRIMARY_KEY，且逐行原因可区分
        ResponseEntity<String> bad = rest.postForEntity(url("/{t}/write"),
                map("baseVersion", 1, "mode", "APPEND", "rows", List.of(map("name", "no-id"))),
                String.class);
        assertEquals(HttpStatus.CONFLICT, bad.getStatusCode());
        JsonNode badErr = json.readTree(bad.getBody());
        assertEquals("MISSING_PRIMARY_KEY", badErr.path("reason").asText());
        assertEquals(0, badErr.path("rowRejections").get(0).path("rowIndex").asInt());
        assertEquals("MISSING_PRIMARY_KEY",
                badErr.path("rowRejections").get(0).path("reason").asText());

        // 类型不匹配 -> 409 TYPE_MISMATCH
        ResponseEntity<String> typeBad = rest.postForEntity(url("/{t}/write"),
                map("baseVersion", 1, "mode", "APPEND",
                        "rows", List.of(map("id", "oops", "name", "x"))), String.class);
        assertEquals(HttpStatus.CONFLICT, typeBad.getStatusCode());
        assertEquals("TYPE_MISMATCH", json.readTree(typeBad.getBody()).path("reason").asText());

        // 结构演进：新增列
        var evolveReq = map("baseVersion", 1, "changes", List.of(
                map("op", "ADD", "name", "region", "type", "STRING",
                        "nullable", true, "defaultValue", "NA")));
        ResponseEntity<String> evolved = rest.postForEntity(url("/{t}/evolve"), evolveReq, String.class);
        assertEquals(HttpStatus.OK, evolved.getStatusCode());
        JsonNode evCommit = json.readTree(evolved.getBody()).path("commit");
        assertEquals(2, evCommit.path("version").asInt());

        // 历史快照 v1 不含 region；当前快照旧行取缺省值
        JsonNode oldRows = json.readTree(rest.getForEntity(url("/{t}/snapshots/1/rows"), String.class).getBody());
        assertFalse(oldRows.get("rows").get(0).path("values").has("region"));
        JsonNode curRows = json.readTree(rest.getForEntity(url("/{t}/rows"), String.class).getBody());
        assertEquals("NA", curRows.get("rows").get(0).path("values").path("region").asText());

        // 整理
        ResponseEntity<String> compacted = rest.postForEntity(url("/{t}/compact"),
                map("baseVersion", 2), String.class);
        assertEquals(HttpStatus.OK, compacted.getStatusCode());
        JsonNode compCommit = json.readTree(compacted.getBody()).path("commit");
        assertEquals(3, compCommit.path("version").asInt());
        assertEquals(1, json.readTree(compacted.getBody()).path("table").path("current")
                .path("dataFileCount").asInt());

        // 表描述含完整快照链
        JsonNode desc = json.readTree(rest.getForEntity(url("/{t}"), String.class).getBody());
        assertEquals(4, desc.path("snapshots").size()); // v0..v3
    }
}
