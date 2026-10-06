# 本地湖仓表格式（Local Lakehouse Table Format）

用**本地文件系统**模拟湖仓表的元数据与数据文件，在一套对外接口（Java API + REST）
下提供可解释、可复现的：

- 按主键的**追加（APPEND）**、**合并（MERGE/upsert）**与**删除（DELETE）**写入；
- **快照隔离**、历史版本查询与乐观并发控制（OCC）；
- **表结构演进**（新增 / 改名 / 删除列）与**数据文件整理（compaction）**；
- 按保留策略**回收历史快照与孤儿数据文件（retention）**。

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
    3-delete.json        # 删除提交的墓碑文件（行带 deleted=true，只含主键列）
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

### 2.4 按主键删除（DELETE）

`delete(DeleteRequest)` 一次可删除多条记录，语义与写入完全对齐：

- **墓碑提交**：删除不修改任何旧文件，而是写一个只含主键列、`deleted=true`
  的墓碑文件，并生成 `operation=DELETE` 的新快照（文件列表 = 旧列表 + 墓碑文件）。
- **可见性**：墓碑与数据行走 §2.3 同一套 `(commitVersion, seq)` 坐标规则。
  墓碑的提交版本高于被删行，因此**当前快照不再可见这些主键**；
  历史快照仍引用旧文件、不含墓碑，**删除之前的版本照常可读**，不会被一起抹掉。
- **删除后重写同一主键**：删除只作用于其基版本之前的行。之后以更高提交版本
  APPEND/MERGE 同一主键时，数据行确定地胜过墓碑（APPEND 也不再判定
  「主键已存在」，因为已删主键不在有效键集合中）。规则唯一、结果稳定可复现。
- **整批校验，all-or-nothing**：任一记录非法则整批拒绝、不产生任何文件或
  新版本，原因逐条可区分（在 §3 表格中）：
  批内重复主键 `DUPLICATE_PRIMARY_KEY_IN_BATCH`、
  主键不存在 `PRIMARY_KEY_NOT_FOUND`、
  缺主键 `MISSING_PRIMARY_KEY`、主键类型不符 `TYPE_MISMATCH`、
  未知列 `UNKNOWN_COLUMN`。
- 删除同样走表级锁 + OCC（`DeleteRequest.expectedVersion`），
  过期基版本得到 `ConflictException`（HTTP 409）。

### 2.5 删除与演进、整理的配合

- **与 compaction**：整理用与查询完全相同的合并规则，墓碑在重写时生效，
  被删行不会写进新文件——**整理后已删行不会复活**；旧文件不动，历史快照不受影响。
- **与结构演进**：删除只按主键列定位行，主键以**稳定的列 ID** 锚定身份；
  删行 / 加列 / 改名（**含主键列改名**）/ 删列任意穿插后，
  **每个历史快照仍按它当时的 schema 把值原样读出**
  （列 ID 寻址保证不会把一列的值读到另一列上）。注意：主键列不允许 DROP
  （即使在同一批变更里先改名再删除也按列 ID 判为主键而拒绝），但**允许 RENAME**，
  语义见 §4。

### 2.6 时间旅行（历史版本）

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
| `PRIMARY_KEY_NOT_FOUND` | DELETE 要删的主键在当前快照中不存在 |

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
| **ADD** | 分配新列 ID。旧行读取该列时：有 `defaultValue` 用缺省值，否则可空列为 `null`；**NOT NULL 且无缺省值的新列被拒绝**（旧行无法填充）；缺省值必须能按列类型解析。新列名还必须合法（非空白、不含控制字符、两端无空白）。 |
| **RENAME** | **只改列名、列 ID 不变**，普通列与**主键列一视同仁**（单列主键、复合主键中的任一列都可改）。历史快照仍用旧 schema（旧列名）复现；新快照用新列名读到同一份按 ID 存储的数据，**不会错列**。 |
| **DROP** | 从新 schema 移除；历史快照保留旧 schema，旧列在历史版本中仍可读。**禁止删除主键列**（按列 ID 判定，同批先改名也无法绕过）。 |

### 4.1 主键列改名（RENAME primary key column）

主键的身份是**列 ID 的有序集合**，主键名只是每个快照 schema 里的一层投影。
因此主键列改名与普通列改名是同一条机制：列 ID 不变、数据文件不触碰，
只在新快照的 schema 里把主键声明中的旧名换成新名。

- **改名后写入 / 合并 / 删除**：改名提交成功后，必须用**新主键列名**
  APPEND / MERGE / DELETE；旧名字在新 schema 下会被当作 `UNKNOWN_COLUMN`。
  - APPEND 不会把同列 ID 的既有主键误判为「已存在」——有效键集合仍按列 ID 求值。
  - DELETE 按新名字给出的键与既有墓碑/数据行走同一套 `(commitVersion, seq)` 规则。
- **复合主键**：可以只改其中一列，未改名的列保持原样；键的各部分仍按
  各自稳定的列 ID 对齐，**不会出现一列读到另一列的值**。
- **历史可见性**：改名之前的快照内嵌旧 schema，时间旅行到该版本仍用**旧列名**
  读到原来的值；改名之后的快照用新列名。两个名字永不共存于同一快照。
- **与删行 / 加列 / 删列 / 整理穿插**：主键改名只是一次普通的 SCHEMA_CHANGE
  提交，可与这些操作任意穿插。整理（compaction）重写时按头快照 schema 的
  主键列 ID 合并，墓碑照常生效，**已删除行不会在改名或整理后复活**；
  删除后又写回同一主键（按新名）仍服从「更高提交版本胜出」的稳定规则。
- **与保留回收**：改名不改动任何数据文件，快照/文件的引用关系完全不变，
  retention 的「保留快照引用的文件不删」规则与改名前一致。
- **失败边界（均返回 400 `INVALID_SCHEMA_EVOLUTION`，失败后版本不推进、
  数据不变、不留半截状态，消息可区分）**：
  新名与已有列重名（`target name already exists`）、
  被改列不存在（`column not found`）、
  新名为 null/空白（`new name must not be null or blank`）、
  新名非法（两端空白或含控制字符，`control characters`/`whitespace`）。
- **并发**：改名同样走表级锁 + OCC（`SchemaChangeRequest.expectedVersion`）。
  基于同一过期基版本的两个改名提交恰好一个成功，另一个得到 409
  `SNAPSHOT_CONFLICT`，**已提交的改名绝不会被覆盖**；输家刷新头版本、
  在赢家给出的新列名上重试即可。

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

## 6. 保留与回收（Retention）

`expireSnapshots(RetentionRequest)` 按保留策略清理历史，回收磁盘空间。
两种策略**恰好指定一个**（否则 400 `INVALID_ARGUMENT`）：

| 策略 | 含义 |
|---|---|
| `keepLastSnapshots=N`（N≥1） | 只保留最近 N 个快照（含当前头快照） |
| `retainFromVersion=V`（V≥0） | 保留版本号 ≥ V 的全部快照 |

规则与不变量：

1. **头快照无条件保留**：`retainFromVersion` 大于头版本时按头版本处理，
   当前快照永远不会指向一个不存在的文件。
2. 过期快照文件被删除；**不再被任何保留快照引用的数据文件**随之删除，
   被保留快照引用的文件一个都不动。
3. **保留范围内的历史查询结果与回收前逐条一致**（有测试逐版本比对）；
   过期版本再查询会得到 `400 INVALID_ARGUMENT`（快照不存在）。
4. **与并发提交安全共存**：回收与提交共用同一把表级锁，HEAD 与引用集合在
   锁内一次性确定，不会删掉刚好被新快照引用的文件；先删过期快照文件、
   再删孤儿数据文件，任一步中断都不破坏保留快照（含 HEAD）的完整性。
5. 回收结果 `RetentionResult` 带回头版本、头快照标识、过期/保留版本清单、
   被删/被留文件清单，日志同步打印判定依据。

---

## 7. 对外接口

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
t.delete(new DeleteRequest(1L, List.of(Map.of("id", 1)))); // 按主键删除
t.evolve(new SchemaChangeRequest(1L,
        List.of(new ColumnChange(Kind.ADD, "note", null,
                new ColumnSpec("note", DataType.STRING, true, null)))));
t.compact();
t.expireSnapshots(new RetentionRequest(5, null));   // 只保留最近 5 个快照
// 或 new RetentionRequest(null, 3L)                // 保留版本 >= 3 的快照
t.history();         // 全部已提交快照
```

### REST

| 方法与路径 | 说明 |
|---|---|
| `POST /api/tables/{name}` | 建表，body：`{columns:[{name,type,nullable,defaultValue}], primaryKey:[...]}`，返回初始快照 |
| `POST /api/tables/{name}/write` | 写入，body：`{mode:APPEND|MERGE, expectedVersion, records:[{列名:值}]}` |
| `POST /api/tables/{name}/delete` | 按主键删除，body：`{expectedVersion, keys:[{主键列:值}]}`，返回同写入结果 |
| `POST /api/tables/{name}/schema` | 结构演进，body：`{expectedVersion, changes:[{kind:ADD|RENAME|DROP, columnName, newName, spec}]}` |
| `POST /api/tables/{name}/compact` | 整理数据文件 |
| `POST /api/tables/{name}/retention` | 保留回收，body：`{keepLastSnapshots}` 或 `{retainFromVersion}`（二选一） |
| `GET  /api/tables/{name}/rows?version=N` | 查询（省略 version 读头快照） |
| `GET  /api/tables/{name}/history` | 快照历史 |

---

## 8. 验证方法

```bash
mvn test          # 运行全部 39 个测试
```

测试与覆盖点：

| 测试类 | 覆盖 |
|---|---|
| `DataTypeTest` | 类型规范化合法 / 非法路径 |
| `WriteValidationTest` | APPEND/MERGE 确定性、6 类拒绝原因、整批 all-or-nothing |
| `SnapshotIsolationTest` | 每版可复现（含重开 catalog）、过期快照拒绝、演进 OCC |
| `ConcurrentCommitTest` | 8 线程同基版本竞争：恰一成功、其余 SNAPSHOT_CONFLICT；刷新重试全部落地 |
| `SchemaEvolutionTest` | ADD 缺省语义、RENAME 不错列、DROP 历史可读、非法演进、compaction 前后一致 |
| `PrimaryKeyRenameTest` | 单列/复合主键改名后追加·合并·删除、逐快照按本版 schema 复现、与删行/加列/删列/整理穿插不复活、4 类失败边界无副作用、同基版本改名 OCC 恰一胜且重试落地、重开 catalog 持久化 |
| `DeleteTest` | 删除可见性与历史保留、三类非法删除可区分且整批原子、删后重写规则、整理后不复活、与演进穿插不错列、旧格式文件兼容 |
| `RetentionTest` | keepLast/retainFromVersion 两种策略、保留范围逐条一致、引用文件零误删、头快照钉住、并发提交与回收竞争、策略参数校验 |
| `RestApiIntegrationTest` | HTTP 全生命周期（含删除、回收与**主键列改名**及可区分错误码） |

测试日志会打印**提交版本、快照标识（snapshotId）与判定依据**，例如：

```
[commit:accept] table=orders mode=APPEND baseVersion=0 committedVersion=1 snapshotId=... records=2 basis=headCAS(base=0)
[delete:accept] table=orders baseVersion=1 committedVersion=2 snapshotId=... deletedKeys=2 basis=headCAS(base=1)
[delete:reject] table=orders baseVersion=1 rejectedCount=3 reasons=[DUPLICATE_PRIMARY_KEY_IN_BATCH, PRIMARY_KEY_NOT_FOUND, MISSING_PRIMARY_KEY]
[concurrent:reject] id=3 reason=SNAPSHOT_CONFLICT expectedVersion=0 currentVersion=1 basis=OCC
[evolve] RENAME label->title columnId=2: history v1 reads label, head v2 reads title ...
[evolve:accept] table=orders ... changes=[RENAME:id->orderId] primaryKey=[orderId] basis=headCAS(base=1)
[pk-rename:use] appendV=3 mergeV=4 deleteV=5 ... basis=all keyed by new name orderId (columnId=1)
[pk-rename:interleave] version=4 snapshotId=... op=DELETE pk=[itemSku] columns=[1:itemSku, 2:name] rows=1
[pk-rename:concurrent:reject] loser=... reason=SNAPSHOT_CONFLICT expectedVersion=1 currentVersion=2 basis=OCC
[compact] files 3->1, liveRows=4 ... results identical; history v1 still reproduces old value
[retention:done] table=orders headVersion=5 policy=keepLastSnapshots=2 expiredSnapshots=[0,1,2,3] retainedSnapshots=[4,5] deletedFiles=3 retainedFiles=2 basis=commitLock+headReferenced
```

查看某次测试的日志输出可直接运行：

```bash
mvn test -Dtest=ConcurrentCommitTest
mvn test -Dtest=PrimaryKeyRenameTest   # 主键列改名（含复合主键、穿插、失败边界、OCC、持久化）
```

---

## 9. 兼容范围

- **旧格式数据文件**：引入删除前行 JSON 没有 `deleted` 字段；反序列化时缺失
  一律按 `false` 处理，旧文件无需迁移即可继续读取、参与合并与删除
  （`DeleteTest.legacyDataFilesWithoutDeletedFlagRemainReadable` 验证）。
- **快照文件**：快照结构未变，历史仓库的 `snap-*.json` 原样可用。
- **API**：接口保持稳定；本次仅**放开**了原先对主键列 RENAME 的拒绝
  （主键改名为纯 schema 投影变更，不动数据文件），写入、演进、整理、查询的
  其余语义与历史仓库格式不变（原有 32 个测试全部无回归）。

---

## 10. 设计取舍小结

- **表级锁 + OCC + 原子 HEAD**：单 JVM 内由锁串行化，跨进程由原子 CAS 兜底，
  冲突可解释且绝不丢已提交数据。
- **列 ID 寻址**：把「数据身份」与「列名」解耦，是改名/删列不错列、历史可复现的根因。
  主键身份同样只是「列 ID 的有序集合」，所以主键列改名与普通列改名走同一条机制，
  无需改写任何数据文件或墓碑。
- **不可变文件 + 快照清单**：时间旅行与可重复读几乎零额外成本；compaction 只影响
  新快照，旧版本安然无恙。
- **墓碑删除**：删除也是一次普通提交（新文件 + 新快照），复用统一的合并坐标规则，
  因此当前可见性、历史保留、整理不复活、删后重写都由同一条规则推出，没有特例。
- **回收不碰引用**：保留集（含头快照）引用的文件是删除的禁区；锁内确定引用集合，
  是与并发提交安全共存的关键。
