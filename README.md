# 本地湖仓表格式（Local Lakehouse Table Format）

用**本地文件系统**模拟湖仓表的元数据与数据文件，在一套对外接口（Java API + REST）
下提供可解释、可复现的：

- 按主键的**追加（APPEND）**与**合并（MERGE/upsert）**写入；
- 按主键的**批量删除（DELETE，墓碑机制）**：当前快照不可见、历史版本照旧可读；
- **快照隔离**、历史版本查询与乐观并发控制（OCC）；
- **表结构演进**（新增 / 改名 / 删除列，含主键列改名）与**数据文件整理（compaction）**；
- 按保留策略的**空间回收（VACUUM）**：过期旧快照并物理删除无引用的数据文件。

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
    6-delete.json        # 删除提交写出的墓碑文件（只含主键列值，deleted=true）
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
  （包括「空表」v0）——至少在被 VACUUM 过期之前如此（见 §6）。

### 2.5 按主键删除（DELETE，墓碑机制）

`DeleteRequest{expectedVersion, keys[]}` 一次按主键删除多条记录。删除**不抹掉任何
旧数据**，而是在一个新的不可变文件 `N-delete.json` 中为每个被删主键写一行**墓碑**
（`Row{deleted=true}`，只携带主键列值，按列 ID 存储）：

- **可见性**：墓碑与普通行走 §2.3 **完全相同的来源坐标 `(commitVersion, seq)` 合并
  规则**。坐标更大的墓碑遮蔽旧行——删除提交之后的任何快照都读不到这些键；删除提交
  之前的历史快照不引用墓碑文件，旧行原样可读、不受影响。
- **删除后重新写入同一主键（规则确定且可复现）**：删除之后新提交的行坐标必然更大，
  确定地重新可见（无论 APPEND 还是 MERGE）。再次删除则新墓碑再次遮蔽。时间线上
  每个版本的结论都由坐标比较唯一决定：`v1=a → v2=<已删> → v3=A2 → v4=<已删> → v5=A3`。
- **整批 all-or-nothing 校验**，原因逐条可区分（不做半截子删除）：

| RejectReason | DELETE 触发条件 |
|---|---|
| `MISSING_PRIMARY_KEY` | 键为 null、缺少主键列或主键值为 null/空串 |
| `TYPE_MISMATCH` | 主键值与主键列类型不符（如 `"x"` 给 LONG 主键） |
| `UNKNOWN_COLUMN` | 键里出现 schema 中不存在的列，或出现**非主键列**（删除只按主键定位，不静默忽略多余字段） |
| `DUPLICATE_PRIMARY_KEY_IN_BATCH` | 同一批里同一主键出现两次（含编码后相等的复合主键） |
| `PRIMARY_KEY_NOT_FOUND` | 主键在当前头快照不存在（包括已经被前一次删除的键）——不静默当成功 |

- 删除同样受 **OCC** 保护：`expectedVersion` 过期抛 409 `SNAPSHOT_CONFLICT`，
  不删除任何行；刷新到最新版本重试即可。

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
| `PRIMARY_KEY_ALREADY_EXISTS` | APPEND 命中已存在主键（注意：被删除的键不算存在，删除后可以重新 APPEND） |
| `DUPLICATE_PRIMARY_KEY_IN_BATCH` | 同一批次内主键重复（写入与删除都适用） |
| `PRIMARY_KEY_NOT_FOUND` | DELETE 命中了当前快照中不存在（或已删除）的主键 |

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
| **RENAME** | **只改列名、列 ID 不变**（主键列也可以改名，schema 中的主键名随之更新）。历史快照仍用旧 schema（旧列名）复现；新快照用新列名读到同一份按 ID 存储的数据，**不会错列**；改名后的删除按新列名定位。 |
| **DROP** | 从新 schema 移除；历史快照保留旧 schema，旧列在历史版本中仍可读。**禁止删除主键列**（无论主键是否改过名）。 |

新增列的缺省语义是**确定且稳定**的：补值只取决于列声明（`defaultValue` / 可空性），
与查询时刻无关。演进同样受 OCC 保护（`SchemaChangeRequest.expectedVersion`）。

---

## 5. 数据文件整理（Compaction）

`compact()` 把当前快照引用的多个文件按 §2.3 的同一合并规则压成一个新文件，
并生成新快照：

- **整理前后按任意快照查询的结果完全一致**（有测试做集合相等断言）；
- 墓碑与普通行用同一套规则合并：被删行在整理结果中**不会复活**，墓碑在「没有任何
  旧值需要遮蔽」时随整理一并丢弃（整理后的单文件只含存活行）；
- 旧文件**不删除**（仍被历史快照引用），因此时间旅行不受影响；要真正释放空间，
  在整理之后对旧快照执行 VACUUM（见 §6）；
- 只有 0/1 个文件时为幂等空操作，头版本不变。

---

## 6. 保留策略与空间回收（VACUUM）

历史快照与其引用的数据文件默认一直保留。`vacuum(RetentionPolicy)` 按保留策略
**过期旧快照并物理删除数据文件**：

- 两种策略**二选一**（`RetentionPolicy.retainAtLeastVersion(min)` 或
  `RetentionPolicy.retainLast(n)`，`n >= 1`）；两者都给或都不给、`n < 1`、
  `min` 超出 `[0, head]` 一律 `400 INVALID_ARGUMENT`。
- **头快照永远在保留集合内**：`retainAtLeastVersion(head)` / `retainLast(1)`
  也不会动当前快照。
- **数据文件删除判定（引用闭包）**：先算出保留快照集合与其引用的全部文件路径；
  只有「被某个过期快照引用、且不被任何保留快照引用」的文件才物理删除。
  因此刚整理完再回收（`compact()` 后旧文件只被旧快照引用）才能真正释放空间；
  只要还有保留快照引用，文件一个都不会删。
- **回收一致性**：保留快照内嵌 schema 与文件清单，删除顺序是「先删过期快照文件、
  后删数据文件」，中断时至多留下无人引用的垃圾文件，绝不会让仍存在的快照指向已删
  文件。回收后对保留版本逐条读取的结果与回收前完全一致（有测试断言）。
- **过期之后**：被删快照的 `readAt(version)` 抛 `IllegalArgumentException`
  （REST 层为 `400 INVALID_ARGUMENT`），这是预期行为而不是损坏。
- **幂等**：对同一保留策略重复回收是空操作。
- **不产生新快照、不推进 HEAD**：VACUUM 是纯清理操作，`VacuumResult` 回传
  回收开始/结束时的头版本、保留与过期的版本列表、删除/保留的文件列表，便于审计。

### 6.1 与并发提交相撞时的安全性

回收在**表级提交锁**内执行，同 JVM 内与写入/删除/演进/整理天然串行化。跨进程下
判定规则本身仍然安全：数据文件名按提交版本分配、**永不复用**，新快照只能继承父快照
的文件列表或引用更高版本的新文件，所以一个并发新提交引用的文件**绝不可能**落入
「仅过期快照引用」的删除集合——既不会删掉刚好被新快照引用的文件，也不会让当前快照
指向缺失文件。若回收期间 HEAD 被推进，`VacuumResult.headVersionAtEnd` 会如实反映。

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
t.evolve(new SchemaChangeRequest(1L,
        List.of(new ColumnChange(Kind.ADD, "note", null,
                new ColumnSpec("note", DataType.STRING, true, null)))));
t.compact();
t.delete(new DeleteRequest(2L, List.of(Map.of("id", 3))));
t.vacuum(RetentionPolicy.retainLast(10));
t.history();         // 全部未过期（保留中）的快照
```

### REST

| 方法与路径 | 说明 |
|---|---|
| `POST /api/tables/{name}` | 建表，body：`{columns:[{name,type,nullable,defaultValue}], primaryKey:[...]}`，返回初始快照 |
| `POST /api/tables/{name}/write` | 写入，body：`{mode:APPEND|MERGE, expectedVersion, records:[{列名:值}]}` |
| `POST /api/tables/{name}/schema` | 结构演进，body：`{expectedVersion, changes:[{kind:ADD|RENAME|DROP, columnName, newName, spec}]}` |
| `POST /api/tables/{name}/compact` | 整理数据文件 |
| `POST /api/tables/{name}/delete` | 按主键批量删除，body：`{expectedVersion, keys:[{主键列名:值}]}`，成功 200 + `committed=true`，整批不合法 200 + `committed=false` + `rejected[]` |
| `POST /api/tables/{name}/vacuum` | 空间回收，body：`{minVersion:3}` 或 `{retainLastSnapshots:5}`（二选一），返回删除/保留清单 |
| `GET  /api/tables/{name}/rows?version=N` | 查询（省略 version 读头快照；版本已被回收则 400） |
| `GET  /api/tables/{name}/history` | 快照历史（仅保留中的快照） |

---

## 8. 验证方法

```bash
mvn test          # 运行全部 33 个测试
```

测试与覆盖点：

| 测试类 | 覆盖 |
|---|---|
| `DataTypeTest` | 类型规范化合法 / 非法路径 |
| `WriteValidationTest` | APPEND/MERGE 确定性、6 类拒绝原因、整批 all-or-nothing |
| `SnapshotIsolationTest` | 每版可复现（含重开 catalog）、过期快照拒绝、演进 OCC |
| `ConcurrentCommitTest` | 8 线程同基版本竞争：恰一成功、其余 SNAPSHOT_CONFLICT；刷新重试全部落地 |
| `SchemaEvolutionTest` | ADD 缺省语义、RENAME 不错列、DROP 历史可读、非法演进、compaction 前后一致 |
| `DeleteTest` | 批量删除当前不可见/历史可读、5 类删除拒绝原因可区分且整批回滚、重复删除报 NOT_FOUND、删除后再插的确定性时间线、删除的 OCC 冲突与重试 |
| `DeleteEvolutionInterplayTest` | 删行/加列/改名/删列/compaction 穿插后逐快照按当时 schema 复现、主键改名后按新名删除 |
| `VacuumTest` | 按版本/按最近 N 个保留、过期快照删除、仅无引用文件被删且引用文件存活、保留范围逐条一致、非法策略拒绝、与并发提交相撞后 HEAD 完整 |
| `ConcurrentVacuumTest` | 4 写线程（更新+删除，冲突重试）与 6 轮回收并发：HEAD 引用文件无一缺失、保留快照均可读 |
| `RestApiIntegrationTest` | HTTP 全生命周期、可区分错误码、delete/vacuum 端到端 |

测试日志会打印**提交版本、快照标识（snapshotId）与判定依据**，例如：

```
[commit:accept] table=orders mode=APPEND baseVersion=0 committedVersion=1 snapshotId=... records=2 basis=headCAS(base=0)
[delete:accept] table=orders baseVersion=1 committedVersion=2 snapshotId=... deleted=2 filesAfter=3 basis=tombstoneFile+headCAS(base=1)
[delete:reject] batchIndex=3 key=99 reason=PRIMARY_KEY_NOT_FOUND msg=primary key does not exist in the current snapshot (or already deleted)
[evolve] RENAME label->title columnId=2: history v1 reads label, head v2 reads title ...
[compact] files 3->1, liveRows=4 ... results identical; history v1 still reproduces old value
[vacuum:accept] table=t headStart=5 headEnd=5 basis=retainLast=3 retainedSnapshots=[3,4,5] expiredSnapshots=[0,1,2] retainedFiles=1 deletedFiles=[data/1-0.json, data/2-delete.json] concurrentCommit=false
[vacuum:race:done] headVersion=29 snapshotId=... headFiles=1 headRows=12 commits=16 deletes=8 conflicts=40 vacuums=6 basis=tableLock serializes; delete-set excludes retained references
```

查看某次测试的日志输出可直接运行：

```bash
mvn test -Dtest=DeleteTest
mvn test -Dtest=VacuumTest,ConcurrentVacuumTest
```

---

## 9. 兼容范围

- **旧数据文件兼容**：墓碑通过给 `Row` 增加 `deleted` 布尔字段实现，旧文件 JSON 中
  没有该字段，反序列化取 Java 默认值 `false`；本特性上线前写出的仓库目录可以直接
  打开、读写与回收（回归测试与重开 catalog 的用例持续保证）。
- **快照/日志**：新快照操作类型新增 `DELETE`（VACUUM 不产生快照）；旧消费者不识别
  新操作类型时只需按未知枚举忽略。
- **部署边界**：本地文件系统、单目录仓库；单 JVM 由表级锁串行化，跨进程并发由
  HEAD 原子 CAS 与「版本化文件名 + 引用闭包删除」保证安全（见 §6.1）。尚未实现
  分布式锁、远程对象存储与快照过期后的时间旅行（需要外置归档）。

---

## 10. 设计取舍小结

- **表级锁 + OCC + 原子 HEAD**：单 JVM 内由锁串行化，跨进程由原子 CAS 兜底，
  冲突可解释且绝不丢已提交数据。
- **列 ID 寻址**：把「数据身份」与「列名」解耦，是改名/删列不错列、历史可复现的根因；
  主键列改名同样只改名、不改 ID。
- **墓碑即普通行**：删除不引入第二套合并机制，复用 `(commitVersion, seq)` 坐标规则，
  因此「删除遮蔽旧值、新插入覆盖墓碑、compaction 不复活、历史不被抹掉」全部是同一
  规则的推论。
- **不可变文件 + 快照清单**：时间旅行与可重复读几乎零额外成本；compaction 只影响
  新快照，旧版本安然无恙。
- **回收 = 引用闭包上的集合差**：先删元数据后删数据、只删「仅过期快照引用」的文件，
  在并发下也不可能删掉活跃快照需要的文件。
