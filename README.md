# 本地湖仓表格式（Local Lakehouse Table Format）

用**本地文件系统**模拟湖仓表的元数据与数据文件，在一套一致的对外接口下提供：
按主键的追加/合并写入、快照隔离与历史版本查询、表结构演进、数据文件整理（compaction）。

- 语言/运行时：Java 21、Spring Boot 3.4（仅用其 HTTP/装配能力，存储完全是本地文件）
- 存储介质：JSON 元数据 + 不可变 JSON 数据文件（无外部数据库）
- 默认仓库目录：`./target/lakehouse-warehouse`（可用配置 `lakehouse.warehouse` 覆盖）

---

## 1. 目录布局

```
<warehouse-root>/<tableName>/
├── metadata/
│   ├── table-state.json      # 根指针：currentVersion + 全部快照清单 + 列 id 水位
│   ├── schema-0.json         # 每个 schema 版本一个不可变文件
│   ├── schema-1.json
│   └── ...
└── data/
    ├── seq-0000000000000000001-<snapshotId>.json   # 不可变数据文件
    └── ...
```

- **数据文件不可变（immutable）**：一旦写入永不修改。每次提交产生新文件，快照只引用文件，不重写文件。
- **根指针原子替换**：`table-state.json` 通过「写临时文件 + 原子 move」发布，
  因此一次提交要么完整可见、要么完全不可见，不存在半写状态。

---

## 2. 快照模型（Snapshot Model）

每次成功提交产生一个**不可变快照**，包含：

| 字段 | 含义 |
| --- | --- |
| `version` | 单调递增的表版本号，建表为 v0 |
| `snapshotId` | UUID，唯一标识该快照 |
| `parentVersion` | 父快照版本，构成线性历史链 |
| `schemaId` | 该快照冻结使用的 schema 版本 |
| `sequenceNumber` | 单调递增序号；数据文件以此排序，决定同一主键的新旧 |
| `operation` | `INITIALIZE / APPEND / MERGE / SCHEMA_CHANGE / COMPACT` |
| `dataFiles` | 该快照引用的**完整**数据文件集合 |

### 2.1 读取如何复现任一历史快照

读取版本 `v` 时：

1. 取快照 `v` 冻结的 `dataFiles` 与 `schemaId`；
2. 按数据文件的 `sequenceNumber` **升序回放**，同一主键以高序号（后写）覆盖低序号；
3. 用该快照冻结的 schema 把「列 id → 值」投影成「列名 → 值」。

因为数据文件永不修改、旧快照引用的文件集合永不改变，**任一已提交快照在其后的任意时刻
都会得到完全一致的结果**。旧数据文件不会被物理删除（compaction 也只是新增合并文件并让
新快照只引用它），从而历史版本始终可复现。

### 2.2 记录的物理存储：按列 id 而非列名

数据文件里每条记录是 `列id -> 值`。这是结构演进不错位的关键（见第 4 节）。

---

## 3. 写入语义与输入校验

### 3.1 追加（APPEND）与合并（MERGE）

- **APPEND**：仅接受基线快照中不存在的主键；主键已存在 → 拒绝（`PRIMARY_KEY_ALREADY_EXISTS`）。
- **MERGE（upsert）**：主键存在则更新、不存在则插入。更新通过「高 sequenceNumber 文件覆盖
  低序号文件」实现，不原地改旧文件。

### 3.2 确定性（determinism）

- **同一批次内重复主键**：不会随机取舍，统一为 **后者覆盖前者（last-one-wins）**，
  被折叠的重复条数写入提交报告 `duplicatesInBatch`。
- 输出按内部主键排序，保证同一快照多次读取顺序也一致。
- 主键内部键为规范化后按分隔符转义拼接，不同列值组合不会碰撞。

### 3.3 非法记录不静默丢弃（整批拒绝，无部分写入）

任意一条记录非法，**整批拒绝**，并返回可区分的原因 `reason` 及逐行明细
`rowRejections[{rowIndex, primaryKey, reason, detail}]`：

| reason | 触发条件 |
| --- | --- |
| `MISSING_PRIMARY_KEY` | 缺少主键列或主键值为 null |
| `UNKNOWN_COLUMN` | 出现当前 schema 中不存在的字段 |
| `MISSING_REQUIRED_COLUMN` | 缺少非空且无缺省值的列，或给非空列传 null |
| `TYPE_MISMATCH` | 值无法规范化为列类型；**会丢精度的转换也拒绝**（如 LONG 列收到 `1.5`；`1.0` 可无损接受） |
| `PRIMARY_KEY_ALREADY_EXISTS` | APPEND 命中已有主键 |

校验按固定顺序进行（未知列 → 主键 → 非主键列），因此每条错误的原因稳定、可复现、可区分。

---

## 4. 结构演进规则（Schema Evolution）

列通过**稳定且永不复用的整数 id** 标识，列名只是 id 在某个 schema 版本下的视图。

- **新增列 ADD**：
  - 分配一个全新且永不复用的列 id（水位 `nextColumnId` 单调递增）；
  - 必须为历史行确定取值，因此**新增非空列必须显式提供非空且类型匹配的缺省值**，
    否则以 `INVALID_SCHEMA_CHANGE` 拒绝；
  - 旧数据文件没有该列，读取投影时稳定填入该列**缺省值**——缺省语义在列定义里固定，
    不会随后续写入漂移。
- **改名 RENAME**：只改列名，列 id 不变。历史数据文件按 id 关联，因此旧快照仍显示旧列名、
  新快照显示新列名，**不会错列**。
- **删列 DROP**：
  - 主键列禁止删除；
  - 被删列的 id **永不复用**（先 DROP 再 ADD 同名列会得到新 id），
    旧文件中该 id 的值在新 schema 下被忽略而非错配到新列；
  - 历史快照因冻结了旧 schema，仍能正常显示被删列。
- schema 变更本身也是一次提交，产生 `SCHEMA_CHANGE` 快照，并同样受 OCC 冲突保护。

---

## 5. 并发提交与冲突处理（OCC）

采用**乐观并发控制 + 表级串行提交**：

1. 写请求必须携带它基于的 `baseVersion`；
2. 提交在「该表的 JVM 锁」内整体执行：读最新根指针 → 判定 → 写新数据文件 → 原子换根指针；
3. 若 `baseVersion` 不是当前最新版本，提交被**明确拒绝**为 `STALE_SNAPSHOT`，
   绝不覆盖已经提交的结果；错误信息会指出当前最新版本、其 snapshotId 与操作类型；
4. `baseVersion` 根本不存在 → `SNAPSHOT_NOT_FOUND`。

> 因此在同一基线上并发的 N 个写事务，**有且仅有一个成功**，其余全部得到可解释的
> `STALE_SNAPSHOT`；调用方刷新基线后重试即可全部成功（见
> `ConcurrentCommitTest#disjointKeyWritersAllSucceedWhenEachRetriesOnLatest`）。

提交落盘若失败，内存态不会保留半截结果（先在 state 副本上落盘，成功后才整体发布），
并返回 `COMMIT_FAILED`；数据文件可能成为不被任何快照引用的孤儿文件，不影响正确性。

---

## 6. 数据文件整理（Compaction）

`compact` 把当前快照引用的多个数据文件按主键去重后合并成**一个**新文件，
并产生 `COMPACT` 快照（新快照只引用合并文件，旧文件保留给历史快照）。

- 整理是纯物理重组，**不改逻辑内容**：整理前后对同一逻辑表状态的查询结果必须完全一致
  （测试用 `assertEquals(before, after)` 强校验键集合与每行列值）；
- 合并记录按主键排序输出，结果可复现。

---

## 7. 对外接口（REST）

| 方法与路径 | 说明 |
| --- | --- |
| `PUT /api/tables/{name}` | 建表（columns + primaryKey），返回 v0 |
| `GET /api/tables/{name}` | 表信息与完整快照链 |
| `POST /api/tables/{name}/write` | `{baseVersion, mode: APPEND|MERGE, rows:[{列名:值}]}` |
| `POST /api/tables/{name}/evolve` | `{baseVersion, changes:[{op:ADD|RENAME|DROP,...}]}` |
| `POST /api/tables/{name}/compact` | `{baseVersion}`（可省略，默认最新） |
| `GET  /api/tables/{name}/rows` | 读最新版本 |
| `GET  /api/tables/{name}/snapshots/{v}/rows` | 读历史版本 `v` |
| `DELETE /api/tables/{name}` | 删除表目录 |

冲突/非法输入返回 HTTP `409`，形如：

```json
{
  "error": "commit_rejected",
  "reason": "STALE_SNAPSHOT",
  "message": "基于过期快照 v0 的APPEND写入被拒绝：当前最新为 v1（snapshot=..., op=APPEND）。",
  "rowRejections": []
}
```

结构变更 JSON 示例：

```json
{ "baseVersion": 1,
  "changes": [
    {"op":"ADD","name":"region","type":"STRING","nullable":true,"defaultValue":"NA"},
    {"op":"RENAME","from":"name","to":"full_name"},
    {"op":"DROP","name":"score"}
  ] }
```

核心领域能力也可不经 HTTP 直接使用：`LocalLakehouseCatalog` / `Table` 接口。

---

## 8. 验证方法

```bash
mvn test
```

测试位于 `src/test/.../tests`，共 20+ 用例：

| 测试类 | 覆盖点 |
| --- | --- |
| `WriteAndMergeTest` | 批内重复 last-one-wins、MERGE 更新、APPEND 主键冲突、四类可区分非法输入、数值精度 |
| `SnapshotIsolationTest` | 各历史快照定格复现、过期快照拒绝、未知版本、重启后快照仍可读 |
| `ConcurrentCommitTest` | 8 线程同基线仅 1 个成功其余 `STALE_SNAPSHOT`；冲突刷新基线重试全部成功 |
| `SchemaEvolutionAndCompactTest` | ADD 缺省值、非空无缺省被拒、RENAME 不错列、DROP 保历史、列 id 不复用、COMPACT 前后结果一致、完整演进链 |
| `WebApiTest` | 经真实 HTTP 串联建表/写入/冲突(409)/演进/历史读/整理 |
| `SmokeWiringTest` | 建表→写→落盘→重新加载→读取的端到端冒烟 |

**日志可解释性**：提交与拒绝会在引擎日志与单测标准输出中打印
「提交版本、snapshotId、sequenceNumber、判定依据」，例如：

```
[CONCURRENT-ACCEPT] writer=3 提交版本=v1 快照=53d4e9a2-... 依据=基线=v0 无主键冲突（1 个键均为新键），批内重复 0 条取末值
[CONCURRENT-REJECT] writer=1 原因=STALE_SNAPSHOT 依据=基于过期快照 v0 ... 当前最新为 v1（snapshot=53d4e9a2-..., op=APPEND）
[TEST] COMPACT v4 ... 文件数 3->1，结果完全一致，依据=基线=v3，合并 3 个数据文件...
```

也可手动起服务用 curl 验证：

```bash
mvn spring-boot:run
curl -X PUT localhost:8080/api/tables/demo -H 'Content-Type: application/json' -d '{
  "tableName":"demo","primaryKey":["id"],
  "columns":[{"name":"id","type":"LONG","nullable":false},
             {"name":"name","type":"STRING","nullable":false}]}'
curl -X POST localhost:8080/api/tables/demo/write -H 'Content-Type: application/json' -d '{
  "baseVersion":0,"mode":"APPEND","rows":[{"id":1,"name":"alice"}]}'
curl localhost:8080/api/tables/demo/snapshots/1/rows
```
