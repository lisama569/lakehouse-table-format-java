# 本地湖仓表格式（Local Lakehouse Table Format）

用**本地文件系统**模拟湖仓表的元数据与数据文件，在一套对外接口（Java API + REST）
下提供可解释、可复现的：

- 按主键的**追加（APPEND）**与**合并（MERGE/upsert）**写入；
- **快照隔离**、历史版本查询与乐观并发控制（OCC）；
- **表结构演进**（新增 / 改名 / 删除列）与**数据文件整理（compaction）**。

所有结论都以「提交版本 + 快照标识 + 判定依据」的形式出现在日志与返回体中。

---

## 1. 目录与存储模型

仓库根目录由 `lakehouse.warehouse-dir` 配置（默认 `lakehouse-warehouse`），
每张表是一个子目录：

```
lakehouse-warehouse/<table>/
  metadata/
    HEAD                 # 当前头指针版本号（纯文本，原子替换）
    snap-0.json          # 不可变快照：内嵌当时的完整 schema + 数据文件清单
    snap-1.json
    ...
  data/
    1-0.json             # 不可变数据文件：行按「列 ID -> 值」序列化（JSON）
    2-0.json
    5-compact.json       # compaction 重写出的新文件
```

关键不变量：

1. **数据文件与快照文件只增不改**。任何提交都写新文件，从不就地覆盖旧文件。
2. 每个快照内嵌**当时的完整 schema**（含列 ID、主键、schema 版本）和
   **数据文件清单**，因此任一历史快照都能脱离当前状态独立复现。
3. `HEAD` 通过「临时文件 + 原子 move（CAS）」更新，是提交生效的唯一标志。

---

## 2. 快照模型与并发提交

### 2.1 提交协议

每次写入 / 演进 / compaction 都在**表级锁**内串行执行，并遵循：

1. 读取当前头版本 `base = HEAD`；
2. **OCC 校验**：若请求带 `expectedVersion` 且 `!= base`，立即拒绝并抛
   `ConflictException`（HTTP 409，`errorCode=SNAPSHOT_CONFLICT`），不做任何写入；
3. 在 `base` 快照上构造新状态，写**新数据文件**（如需）与**新快照** `base+1`；
4. `CAS(base -> base+1)` 推进 `HEAD`。

> 因此「基于过期快照的写入」会被**明确拒绝而不是覆盖已提交结果**。
> 并发下同一 `base` 的多个提交恰好一个成功，其余全部得到冲突，调用方刷新到最新
> 版本后重试即可（见 `ConcurrentCommitTest`）。

### 2.2 APPEND 与 MERGE 的确定性

- **APPEND**：新记录写一个新文件，旧文件原样保留；若主键在表中已存在则拒绝
  （`PRIMARY_KEY_ALREADY_EXISTS`）。
- **MERGE（upsert）**：读取当前有效行并以本批覆盖，重写为一个新文件。
- **同一批次内重复主键**：整批拒绝，原因 `DUPLICATE_PRIMARY_KEY_IN_BATCH`，
  并给出该记录在批次中的下标（不静默保留任意一条）。

### 2.3 多文件合并的胜负规则

一个快照可能引用多个文件，且同一主键可能在历史文件中有多个版本。合并规则全局唯一，
**读取、MERGE 重写、compaction 共用**：

> 比较行的来源坐标 `(commitVersion, seq)`，坐标大者胜。
> `seq` 是在快照文件列表内按「文件顺序 + 文件内行序」赋的全局单调序号。

这保证了「后提交 / 后写入」的行确定性胜出，且 compaction 前后查询结果逐字节一致。

### 2.4 时间旅行（历史版本）

- `readAt(version)` / `GET /rows?version=N` 读取任一已提交快照；
- 因为旧文件不删除、快照自带 schema 与文件清单，**任一已提交快照其后都可一致复现**
  （包括「空表」v0）。

---

## 3. 写入校验与可区分的拒绝原因

校验在整批上进行，遵循 **all-or-nothing**：只要有一条非法记录，整批拒绝、
不产生任何文件或新版本，`committed=false` 并在 `rejected[]` 中逐条列出原因。

| RejectReason | 触发条件 |
|---|---|
| `MISSING_PRIMARY_KEY` | 缺少主键列、主键值为 null 或空串 |
| `TYPE_MISMATCH` | 字段值与列类型不符（如 `1.5` 写 LONG、`"x"` 写数字列） |
| `UNKNOWN_COLUMN` | 记录里出现 schema 中不存在的列 |
| `MISSING_REQUIRED_COLUMN` | 非主键列缺失，且列 NOT NULL 又无缺省值 |
| `PRIMARY_KEY_ALREADY_EXISTS` | APPEND 命中已存在主键 |
| `DUPLICATE_PRIMARY_KEY_IN_BATCH` | 同一批次内主键重复 |

类型规范化严格且确定：整数可写 LONG/DOUBLE，但小数写 LONG、字符串写数字/布尔
都会被拒绝；`null` 对可空列合法。缺省值以字符串在 schema 中保存，按列类型解析。

REST 层把领域异常映射为可区分状态码：`404 TABLE_NOT_FOUND`、
`409 TABLE_ALREADY_EXISTS / SNAPSHOT_CONFLICT`、
`400 INVALID_SCHEMA_EVOLUTION / INVALID_ARGUMENT`；记录级拒绝返回 200 +
`committed=false`（请求本身合法但数据被拒）。

---

## 4. 结构演进规则

列以**稳定的列 ID** 标识（建表时从 1 起按声明顺序分配，**ID 永不复用**），
数据文件以列 ID 为键。演进只产生新快照、**不触碰数据文件**。

| 操作 | 规则 |
|---|---|
| **ADD** | 分配新列 ID。旧行读取该列时：有 `defaultValue` 用缺省值，否则可空列为 `null`；**NOT NULL 且无缺省值的新列被拒绝**（旧行无法填充）；缺省值必须能按列类型解析。 |
| **RENAME** | **只改列名、列 ID 不变**。历史快照仍用旧 schema（旧列名）复现；新快照用新列名读到同一份按 ID 存储的数据，**不会错列**。 |
| **DROP** | 从新 schema 移除；历史快照保留旧 schema，旧列在历史版本中仍可读。**禁止删除主键列**。 |

新增列的缺省语义是**确定且稳定**的：补值只取决于列声明（`defaultValue` / 可空性），
与查询时刻无关。演进同样受 OCC 保护（`SchemaChangeRequest.expectedVersion`）。

---

## 5. 数据文件整理（Compaction）

`compact()` 把当前快照引用的多个文件按 §2.3 的同一合并规则压成一个新文件，
并生成新快照：

- **整理前后按任意快照查询的结果完全一致**（有测试做集合相等断言）；
- 旧文件**不删除**（仍被历史快照引用），因此时间旅行不受影响；
- 只有 0/1 个文件时为幂等空操作，头版本不变。

---

## 6. 对外接口

### Java API

```java
Catalog catalog = new LocalCatalog(Path.of("lakehouse-warehouse"));
Table t = catalog.createTable("orders",
        List.of(new ColumnSpec("id", DataType.LONG, false, null),
                new ColumnSpec("v",  DataType.STRING, true, null)),
        List.of("id"));

WriteResult r = t.commit(new WriteRequest(WriteMode.APPEND, 0L,
        List.of(Map.of("id", 1, "v", "a"))));
r.committed(); r.committedVersion(); r.snapshotId(); r.rejected();

t.readLatest();      // 当前头快照
t.readAt(1);         // 历史版本
t.evolve(new SchemaChangeRequest(1L,
        List.of(new ColumnChange(Kind.ADD, "note", null,
                new ColumnSpec("note", DataType.STRING, true, null)))));
t.compact();
t.history();         // 全部已提交快照
```

### REST

| 方法与路径 | 说明 |
|---|---|
| `POST /api/tables/{name}` | 建表，body：`{columns:[{name,type,nullable,defaultValue}], primaryKey:[...]}`，返回初始快照 |
| `POST /api/tables/{name}/write` | 写入，body：`{mode:APPEND|MERGE, expectedVersion, records:[{列名:值}]}` |
| `POST /api/tables/{name}/schema` | 结构演进，body：`{expectedVersion, changes:[{kind:ADD|RENAME|DROP, columnName, newName, spec}]}` |
| `POST /api/tables/{name}/compact` | 整理数据文件 |
| `GET  /api/tables/{name}/rows?version=N` | 查询（省略 version 读头快照） |
| `GET  /api/tables/{name}/history` | 快照历史 |

---

## 7. 验证方法

```bash
mvn test          # 运行全部 20 个测试
```

测试与覆盖点：

| 测试类 | 覆盖 |
|---|---|
| `DataTypeTest` | 类型规范化合法 / 非法路径 |
| `WriteValidationTest` | APPEND/MERGE 确定性、6 类拒绝原因、整批 all-or-nothing |
| `SnapshotIsolationTest` | 每版可复现（含重开 catalog）、过期快照拒绝、演进 OCC |
| `ConcurrentCommitTest` | 8 线程同基版本竞争：恰一成功、其余 SNAPSHOT_CONFLICT；刷新重试全部落地 |
| `SchemaEvolutionTest` | ADD 缺省语义、RENAME 不错列、DROP 历史可读、非法演进、compaction 前后一致 |
| `RestApiIntegrationTest` | HTTP 全生命周期与可区分错误码 |

测试日志会打印**提交版本、快照标识（snapshotId）与判定依据**，例如：

```
[commit:accept] table=orders mode=APPEND baseVersion=0 committedVersion=1 snapshotId=... records=2 basis=headCAS(base=0)
[concurrent:reject] id=3 reason=SNAPSHOT_CONFLICT expectedVersion=0 currentVersion=1 basis=OCC
[evolve] RENAME label->title columnId=2: history v1 reads label, head v2 reads title ...
[compact] files 3->1, liveRows=4 ... results identical; history v1 still reproduces old value
```

查看某次测试的日志输出可直接运行：

```bash
mvn test -Dtest=ConcurrentCommitTest
```

---

## 8. 设计取舍小结

- **表级锁 + OCC + 原子 HEAD**：单 JVM 内由锁串行化，跨进程由原子 CAS 兜底，
  冲突可解释且绝不丢已提交数据。
- **列 ID 寻址**：把「数据身份」与「列名」解耦，是改名/删列不错列、历史可复现的根因。
- **不可变文件 + 快照清单**：时间旅行与可重复读几乎零额外成本；compaction 只影响
  新快照，旧版本安然无恙。
