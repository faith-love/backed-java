# MySQL 进阶与生产运维：索引、并发、复制和恢复

本章从“前端看到的接口请求”延伸到 MySQL 内部：数据库怎样组织索引，多个请求怎样同时读写，主从切换后为什么可能读到旧数据，以及怎样安全发布表结构并恢复误删数据。示例以 InnoDB 和 MySQL 为主；锁、DDL 和复制行为会随 MySQL 版本、隔离级别、索引、SQL 执行计划及兼容数据库而变化，生产结论必须在目标版本上验证。

## 本章解决的问题

| 前端或服务现象 | 本章要回答的问题 |
|---|---|
| 详情能查到但列表越来越慢 | 主键/二级索引如何组织，SQL 是否回表或扫描过多行 |
| 同一事务前后读取结果不同 | 当前是快照读还是锁定读，隔离级别如何影响可见性 |
| 两个用户同时编辑或扣库存 | 锁定读、条件更新和版本号怎样防止覆盖/超卖 |
| 保存成功后刷新却读到旧值 | 读请求是否被路由到有复制延迟的只读副本 |
| 上线后应用报列不存在 | Schema 变更如何与滚动发布版本兼容 |
| 误删数据后只能重建 | 是否有可验证的备份和时间点恢复方案 |

先掌握 [MySQL 与 Redis 基础](./storage.md)，再学习本章。常用 JOIN、聚合、子查询、分页和写入 SQL 可配合 [SQL 查询与写入实战](./sql-practice.md) 阅读。

## InnoDB 如何组织主键和二级索引

InnoDB 的数据行按聚簇索引组织。通常主键索引就是聚簇索引，叶子节点保存完整行；二级索引叶子节点保存二级索引键以及对应主键值。通过二级索引找到主键后，再到聚簇索引读取其他列，这一步常称为回表。

```text
聚簇索引（通常是主键）
  id=101 -> 完整订单行
  id=102 -> 完整订单行

二级索引 idx_status_created
  (PAID, 2026-11-17, id=102) -> 聚簇索引 id=102
  (WAIT, 2026-11-16, id=101) -> 聚簇索引 id=101
```

这解释了几个设计取舍：

- 主键应稳定且尽量紧凑，因为主键值会复制到二级索引叶子中。
- 随机且很宽的主键会扩大索引，并可能增加聚簇页分裂；顺序 ID 也有写入热点等其他取舍。
- 查询只需要的列若都在某个索引里，优化器可能使用覆盖索引，减少回表；不能为了覆盖查询把所有字段都塞进索引。
- 二级索引不是“另一份完整行数据”；通过它筛选后可能仍需访问聚簇索引。

### 用执行计划确认是否回表和扫描过多

```sql
-- 示例查询：列表只返回页面真正需要的字段。
EXPLAIN
SELECT id, status, created_at
FROM orders
WHERE tenant_id = 1001
  AND status = 'PAID'
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- MySQL 8.0.18+ 可用 EXPLAIN ANALYZE 查看实际执行行数与耗时；它会真正执行 SQL。
-- 对 UPDATE/DELETE 或昂贵查询运行前，先确认数据范围和环境。
```

阅读计划时关注使用的 key、预估/实际扫描行数、过滤比例、排序和临时表、回表成本。`EXPLAIN` 是优化器对当前统计信息和参数的判断，不等于线上延迟测量；还要结合慢查询日志、返回行数、锁等待和连接池等待。

### 对照 `EXPLAIN ANALYZE` 看索引是否真的减少工作量

先在测试库运行同一查询的估算计划和实际计划。`EXPLAIN ANALYZE`（MySQL 8.0.18+）会执行被分析的查询并报告每个迭代器的实际行数、循环数和耗时；它不是“只看一眼、不产生工作”的静态命令。大表或复杂查询要先限制范围，在低风险环境验证。

```sql
-- 第一步：保存原始计划；记录 key、rows、filtered、Extra 和排序操作。
EXPLAIN
SELECT id, status, created_at
FROM orders
WHERE tenant_id = 1001
  AND status = 'PAID'
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- 第二步：在隔离的测试库测实际执行行数和时间；参数应使用代表性数据。
EXPLAIN ANALYZE
SELECT id, status, created_at
FROM orders
WHERE tenant_id = 1001
  AND status = 'PAID'
ORDER BY created_at DESC, id DESC
LIMIT 20;

-- 第三步：测试候选复合索引；是否采用应由真实查询分布与写入成本决定。
CREATE INDEX idx_orders_tenant_status_created
    ON orders (tenant_id, status, created_at, id);

-- 第四步：用完全相同的参数再次执行两条 EXPLAIN，比较实际行数、排序和耗时。
```

计划输出的具体数字依赖数据量、统计信息、版本和缓存状态，不能把某段演示输出当作固定答案。若 `tenant_id/status` 过滤后仍扫描很多行，检查字段基数、参数类型是否发生隐式转换、排序方向和过滤条件；若减少了扫描却增加了写入耗时或索引空间，也要评估收益。新增索引前后分别记录读查询、写入和磁盘占用，再决定是否保留。

## MVCC：一致性读如何看到历史版本

InnoDB 使用 MVCC（多版本并发控制）支持一致性读。更新行时，数据库会维护旧版本信息（常与 undo log、行版本字段关联）；一致性读根据事务的 Read View 判断哪些版本对当前事务可见。

```text
事务 A：开始一致性读 -> 读到 status=WAIT
事务 B：把 status 更新成 PAID -> 提交
事务 A：再次一致性读
  READ COMMITTED：通常能看到 B 已提交的新版本
  REPEATABLE READ：在该事务快照规则下通常仍看到第一次读取的版本
```

上图描述常见 InnoDB 行为，不代表所有 SQL 都是快照读。`UPDATE`、`DELETE`、`SELECT ... FOR UPDATE` 等属于当前读/锁定读范畴，会检查当前可更新版本并可能加锁。隔离级别、事务开始/首次一致性读时机、语句类型和 MySQL 版本都重要。

长时间不提交的事务可能让旧版本无法及时清理，导致 undo 历史增长、purge 落后、磁盘或查询性能受影响。遇到“数据库一直有旧版本/历史链很长”时，应检查长事务和连接泄漏，而非只增加缓存。

### 快照读与锁定读

| 读法 | 典型语句 | 主要用途 | 注意点 |
|---|---|---|---|
| 一致性快照读 | 普通 `SELECT` | 报表读取、查询某个事务可见的数据版本 | 通常不为读行加排他锁；可见性受 Read View 影响 |
| 锁定读 | `SELECT ... FOR UPDATE` | 读取后要基于当前值进行写入 | 持锁直到提交/回滚；必须有超时和短事务 |
| 共享锁定读 | `SELECT ... FOR SHARE`（MySQL 8 常见语法） | 需要读时阻止他人修改 | 老版本可能使用不同语法，需核对目标版本 |
| 条件原子更新 | `UPDATE ... WHERE ...` | 库存、额度等带条件的原子状态变化 | 检查 affected rows，常比“先读再写”简单可靠 |

只要业务动作能表达为一个带条件的 `UPDATE`，通常优先让数据库原子检查条件和写入；只有必须对多行/多步骤进行决策时才考虑锁定读。

## 行锁、间隙锁和 Next-Key Lock

InnoDB 的锁主要由索引记录和范围决定，而不是简单地“锁住某张表”。锁住哪些记录，会受 SQL 条件、命中的索引、隔离级别和执行计划影响。

- **记录锁（record lock）**：锁住索引中的某个记录。
- **间隙锁（gap lock）**：锁住索引记录之间的范围，阻止其他事务在该间隙插入。
- **Next-Key Lock**：记录锁与前方间隙锁组合，常用于范围锁定，以减少某些幻读。
- **意向锁**：表级标记，帮助协调表锁和行级锁；它不等于业务代码显式锁整张表。

不同版本和隔离级别的细节不同。InnoDB Repeatable Read 下范围锁定常涉及 Next-Key Lock；READ COMMITTED 下部分间隙锁行为不同。不要只凭“查询了某行”推断锁范围，需看索引与锁监控。

```sql
-- 例子：事务内锁定某 SKU 当前记录，检查后再扣减。
START TRANSACTION;

SELECT available
FROM inventory
WHERE sku = 'sku-1'
FOR UPDATE;

-- 应用在同一事务中确认数量足够，再执行更新。
UPDATE inventory
SET available = available - 2
WHERE sku = 'sku-1'
  AND available >= 2;

COMMIT;
```

更简单的原子更新通常可以避免应用层“查到 2、多个请求同时扣 2”的竞态：

```sql
-- 返回 affected rows=1 才表示扣减成功；0 表示条件不满足或 SKU 不存在。
UPDATE inventory
SET available = available - 2
WHERE sku = 'sku-1'
  AND available >= 2;
```

`SELECT ... FOR UPDATE` 必须在有效事务里使用；自动提交模式下语句结束后锁可能立即释放。即使锁定了记录，也要处理超时、死锁和连接中断。不要在持锁事务中等待用户确认、调用耗时 HTTP 或读取大文件。

### 怎样缩小锁范围

1. 用唯一索引或高选择性索引定位目标记录。
2. 更新/删除条件尽量包含主键或唯一业务键。
3. 给外键、关联字段和批量更新过滤字段配置合适索引。
4. 对批量处理按固定主键顺序锁定，降低交叉等待概率。
5. 缩短事务，及时提交；避免事务范围覆盖无关查询和远程调用。
6. 检查真实执行计划；缺索引可能让范围扫描和锁范围扩大。

## 死锁：让失败可重试，而不是简单调大超时

死锁是两个或多个事务持有不同锁并互相等待。InnoDB 会检测死锁并回滚其中一个事务，应用必须把这次操作作为失败处理。

```text
事务 A：锁住库存行 1 -> 等待库存行 2
事务 B：锁住库存行 2 -> 等待库存行 1
结果：形成循环等待，数据库回滚其中一个事务
```

排查时记录死锁涉及的 SQL、索引和访问顺序；查看数据库死锁信息/`SHOW ENGINE INNODB STATUS`，必要时通过 Performance Schema 查询当前锁等待（表和字段会依 MySQL 版本不同）。

修复方向：

- 统一多行更新顺序，例如总是按主键升序。
- 缩短事务和减少无关锁定读。
- 补充缺失索引以减少扫描锁范围。
- 对已识别的瞬时死锁有限重试整个幂等事务，并添加退避；不能只重试失败的最后一条 SQL。
- 通过唯一约束、版本号和业务幂等键保证重试不会重复扣款或重复创建记录。

死锁重试要设最大次数和总超时。把所有数据库异常无限重试会放大压力；若操作发送了外部副作用，重试前还需考虑 Outbox/幂等边界。

### 双会话实验：亲眼看到记录锁、间隙锁和死锁

以下实验必须在可丢弃的 MySQL 测试实例中执行。先创建专用数据库和表；不要把示例表名改成真实业务表。等待行为只在一个会话仍持有事务时发生，实验结束后应提交或回滚两个会话的事务。

```sql
-- 初始化一次：所有实验只操作这个专用表。
CREATE DATABASE IF NOT EXISTS backend_learning_lab;
USE backend_learning_lab;

CREATE TABLE IF NOT EXISTS lab_inventory (
    id INT NOT NULL PRIMARY KEY,
    available INT NOT NULL
) ENGINE = InnoDB;

-- 用固定 ID 初始化实验数据；重复运行不会覆盖已有实验状态。
INSERT IGNORE INTO lab_inventory (id, available) VALUES (1, 10), (2, 10), (10, 10), (20, 10);
```

**实验一：唯一主键记录锁。** 在会话 A 开启事务并锁住一条记录；在会话 B 尝试更新同一行，B 会等待。A 提交后 B 才能继续。

```sql
-- 会话 A：持有 id=1 对应记录的排他锁。
USE backend_learning_lab;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT available FROM lab_inventory WHERE id = 1 FOR UPDATE;
```

```sql
-- 会话 B：执行后会等待会话 A 释放记录锁，再检查 affected rows。
USE backend_learning_lab;
START TRANSACTION;
UPDATE lab_inventory SET available = available - 1 WHERE id = 1;
-- 会话 A COMMIT 后，本语句返回；实验结束后提交会话 B。
COMMIT;
```

**实验二：范围锁与间隙。** A 使用主键范围做锁定读；在 Repeatable Read 下，InnoDB 通常会对扫描范围施加 Next-Key/间隙锁。B 插入范围内的 `id=15` 时可能等待；A 提交后再观察 B 的结果。实际锁范围仍要结合执行计划、隔离级别和 MySQL 版本确认。

```sql
-- 会话 A：范围锁定读；记录行 10 与 20 之间的间隙。
USE backend_learning_lab;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT id FROM lab_inventory WHERE id >= 10 AND id < 20 FOR UPDATE;
```

```sql
-- 会话 B：在被 A 锁住的范围中插入；可能等待，直到 A 提交或回滚。
USE backend_learning_lab;
START TRANSACTION;
INSERT INTO lab_inventory (id, available) VALUES (15, 10);
-- 会话 A COMMIT 后再提交本会话。
COMMIT;
```

**实验三：制造一个死锁。** 严格按下面的交替顺序执行：A 更新 1，B 更新 2，A 更新 2 并等待，B 更新 1。最后一条语句形成循环等待，InnoDB 会回滚其中一个事务；查看报错会话，再在另一个会话提交或回滚。

```sql
-- 会话 A 第一步：先锁住 id=1。
START TRANSACTION;
UPDATE lab_inventory SET available = available + 1 WHERE id = 1;
```

```sql
-- 会话 B 第二步：再锁住 id=2。
START TRANSACTION;
UPDATE lab_inventory SET available = available + 1 WHERE id = 2;
```

```sql
-- 回到会话 A 第三步：请求 id=2，等待 B。
UPDATE lab_inventory SET available = available + 1 WHERE id = 2;
```

```sql
-- 回到会话 B 第四步：请求 id=1，形成 A 等 B、B 等 A 的环。
UPDATE lab_inventory SET available = available + 1 WHERE id = 1;
-- 其中一个会话收到 deadlock 报错并被回滚；另一个应由实验者显式 COMMIT 或 ROLLBACK。
```

可从第三个会话观察当前等待（需要访问相应 Performance Schema 视图的权限）：

```sql
-- MySQL 8 可从 sys 视图查看等待者与阻塞者；视图字段会随版本变化。
SELECT waiting_pid, blocking_pid, wait_age, locked_table, waiting_query, blocking_query
FROM sys.innodb_lock_waits;

-- 死锁已经结束后，查看最近一次 InnoDB 死锁记录。
SHOW ENGINE INNODB STATUS;
```

实验结束后可删除整个专用数据库，但删除操作不可恢复，先确认只选择了 `backend_learning_lab`；也可以保留它并手动将测试行恢复到初始数量。不要在生产库执行本节脚本。

## JOIN 与聚合：页面数据怎样从多张表组装

前端一个列表行经常由订单、客户和订单项共同组成。JOIN 在数据库中组合行，聚合函数可以计算数量和总额；分页和一对多关系要特别小心。

```sql
-- 订单列表：先按租户和状态筛选，再关联客户展示名。
SELECT o.id,
       o.order_no,
       o.status,
       o.created_at,
       c.display_name AS customer_name
FROM orders AS o
JOIN customers AS c
  ON c.id = o.customer_id
 AND c.tenant_id = o.tenant_id
WHERE o.tenant_id = ?
  AND o.status = ?
ORDER BY o.created_at DESC, o.id DESC
LIMIT ?;
```

把关联关系和租户范围一起写进条件，避免只凭前端传来的 `customerId` 跨租户关联。`INNER JOIN` 只保留两边都匹配的行；`LEFT JOIN` 保留左侧行并让无匹配右侧列为 NULL。把右表过滤条件写在 `WHERE` 里可能会把 `LEFT JOIN` 变成近似 `INNER JOIN`，要确认产品希望保留还是丢弃缺失关联记录。

```sql
-- 按状态统计订单数和金额；WHERE 先筛明细，GROUP BY 再聚合。
SELECT status,
       COUNT(*) AS order_count,
       SUM(amount) AS total_amount
FROM orders
WHERE tenant_id = ?
  AND created_at >= ?
  AND created_at < ?
GROUP BY status
HAVING COUNT(*) >= ?
ORDER BY order_count DESC;
```

`WHERE` 过滤分组前的行，`HAVING` 过滤分组后的结果。聚合字段的 NULL 语义、金额精度、时区范围和分组排序都应在 API 契约中明确。

### 一对多 JOIN 与分页陷阱

订单 JOIN 多个订单项后，一个订单可能变成多行。直接在 JOIN 结果上 `LIMIT 20`，可能只返回几笔订单；`COUNT(*)` 也可能统计订单项行数而不是订单数。

常见方案：

1. 先分页查询订单主键，再批量查询这些订单的订单项并在应用层组装。
2. 使用子查询先确定一页主表，再 JOIN 关联数据，并验证 total 语义。
3. 列表只返回摘要和 `itemCount` 聚合，详情接口再加载全部明细。
4. 对 `COUNT(DISTINCT o.id)` 的成本和执行计划做实测，不把 `DISTINCT` 当作盲目修正。

JOIN、聚合、子查询、分页和写入的完整可练习示例放在 [SQL 查询与写入实战](./sql-practice.md)。

## 主从复制与读写分离

主库执行写入并记录变更日志，副本读取日志后重放。常见复制链路具有异步性；半同步、组复制或云数据库服务会改变确认和故障语义。应用不能假设“主库刚提交，所有只读副本马上可见”。

| 场景 | 推荐读取位置 | 原因 |
|---|---|---|
| 保存后立即刷新详情 | 主库或具备会话粘性的读主策略 | 副本可能还没追上刚提交的数据 |
| 用户权限/余额/库存的关键判断 | 按业务一致性要求读主 | 旧副本数据可能导致越权或重复操作 |
| 允许短暂延迟的分析列表 | 只读副本 | 可分担主库读压力，但接受滞后 |
| 长报表/离线分析 | 分析副本或数仓 | 避免重查询影响在线事务 |

### 前端“保存成功但看不到”的常见原因

```text
POST 更新 -> 主库提交成功
紧接 GET -> 负载均衡到副本
副本还未重放对应日志 -> 返回旧值
```

可选策略包括保存后短期读主、同一会话读主、携带版本/提交位置并等待副本追上，或通过乐观更新让 UI 暂时展示刚保存的对象。具体方案取决于数据库复制能力和接口一致性要求。

复制延迟应监控副本落后时间/位置、复制线程状态、错误和只读请求耗时。故障切换时要确认新主身份、旧主是否被隔离、客户端连接池是否刷新，避免双主写入或回退已确认数据。GTID、binlog 位点和 failover 能力需按部署版本与平台验证。

## 数据库 Schema 变更与在线发布

表结构变更需要和多个应用版本兼容。滚动发布时旧实例和新实例可能同时运行，所以“先改表、再上线代码”也不保证安全。

### Expand → Migrate → Contract

```text
1. Expand：先添加可空列/新表/兼容索引，旧代码仍能运行
2. Migrate：发布兼容代码，双读/双写或分批回填旧数据
3. Verify：核对行数、校验和、错误率和新旧字段一致性
4. Switch：切换读取到新字段，观察指标和回滚窗口
5. Contract：确认旧版本退出后，才删除旧列/旧索引
```

Flyway/Liquibase 等迁移工具可以版本化管理 DDL/DML，记录哪些脚本已执行；工具本身不会自动让不可逆 DDL 可回滚，也不会自动保证大表变更无锁。脚本应代码评审、测试数据验证、生产备份和可观测发布。

### Flyway 版本脚本与部署顺序示例

代码优先项目常把迁移脚本放在 `src/main/resources/db/migration`。Flyway 默认用 `flyway_schema_history` 保存版本、脚本描述、校验和、执行时间与成功状态；已在环境执行的版本脚本应保持不变，后续修正通过新版本脚本向前迁移。

```text
src/main/resources/db/migration/
  V1__create_orders.sql
  V2__add_nullable_source_code.sql
  V3__backfill_source_code_in_batches.sql  # 大表回填应评估拆批，不一定直接做成单个 DML 脚本。
```

```sql
-- V1__create_orders.sql：首次建立最小订单表的示例；真实表要补业务约束和索引。
CREATE TABLE orders (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_orders_order_no (order_no)
) ENGINE = InnoDB;
```

```sql
-- V2__add_nullable_source_code.sql：先增加可空列，让旧版本代码仍可运行。
ALTER TABLE orders
    ADD COLUMN source_code VARCHAR(32) NULL COMMENT '订单来源';
```

迁移执行顺序示例：先在 CI 空库执行全部版本脚本，再在一份脱敏的旧版本结构副本上验证升级路径；部署时先运行兼容的 V2，再发布能够读写新列的后端；完成分批回填并核对数据后，切换读取逻辑。等旧版本实例退出且回滚窗口关闭，再单独发布删除旧字段的 Contract 迁移。

常见配置形状如下，账号密码从受控环境变量/Secret 注入，不提交到 YAML：

```yaml
spring:
  flyway:
    enabled: true # 仅在明确使用 Flyway 的环境启用；不要与另一套迁移器重复管理同一张表。
    locations: classpath:db/migration # 约定版本脚本扫描目录。
    validate-on-migrate: true # 校验已执行脚本的校验和，发现被改写时尽早失败。
```

MySQL 的许多 DDL 会隐式提交，不能假设把 `ALTER TABLE` 包进事务后就能 `ROLLBACK` 恢复原表。MySQL 某些版本/操作支持原子 DDL，也不等于所有结构变更都能事务回滚。迁移中途失败时，先检查真实 schema 与迁移历史，再决定修复或新增前向脚本；`baseline` 只用于经过盘点的既有数据库，`repair` 只在确认数据库状态与迁移历史后处理校验和/失败记录，不能当作通用跳过错误按钮。

### DDL 和大表迁移的风险

- 不同 MySQL 版本和存储引擎对 `ALGORITHM`/`LOCK` 支持不同；某些操作可能重建表或持有元数据锁。
- 长事务可能阻止 DDL 获得元数据锁，即使变更看起来是在线的。
- 大表新增索引可能消耗磁盘、I/O 和复制带宽，导致复制延迟。
- `ALTER TABLE`、索引重建和数据回填的锁与事务语义必须在目标版本、目标表大小上演练。
- 先评估变更耗时、磁盘空间、复制影响、超时和回退方式；不要在高峰期直接试生产命令。

回填使用小批量、可重入的游标范围；记录进度和错误行，避免一个超大事务长时间占连接、锁住大量记录或产生不可控 binlog。

## 备份与恢复：有备份文件不等于可恢复

| 方案 | 适用场景 | 主要限制 |
|---|---|---|
| 逻辑备份（例如 `mysqldump`） | 中小数据库、跨版本迁移、单表恢复 | 大库速度慢，恢复时间长，需处理一致性快照 |
| 物理备份 | 大库快速恢复和整实例恢复 | 版本、存储格式和工具兼容性要求更高 |
| Binlog/时间点恢复 | 找回某个时刻之前的数据 | 依赖基线备份、连续日志和准确时间/位点 |
| 云数据库快照 | 快速创建实例副本 | 需要确认保留期、跨区域、加密和恢复粒度 |

恢复计划应定义 **RPO**（最多允许丢失多少数据）和 **RTO**（多久恢复服务），并定期在隔离环境演练：恢复完整性、账号权限、字符集、时区、索引、复制关系和应用连接。恢复时要先隔离错误写入源，避免旧实例重新覆盖已恢复数据。

生产备份应加密、限制访问、设置保留周期并测试恢复。备份账号与应用运行账号分离；不要把生产备份随意复制到开发机或公开对象存储。

### 在隔离环境演练逻辑备份与恢复

下面命令用于说明流程，路径、账号权限、认证文件和 MySQL 版本需按环境调整。先在测试库演练；不要把生产口令写入命令行参数、Shell 历史或文档。

```bash
# 从受限权限文件读取认证信息；mysqldump 要求 defaults-extra-file 放在最前面的选项位置。
# single-transaction 为 InnoDB 表提供一致性快照；非事务表不受此快照保证。
# source-data=2 将复制起始文件/位置以注释形式写入 dump，供后续时间点恢复使用。
# routines、events、triggers 和 hex-blob 分别保留存储程序、事件、触发器和二进制字段。
mysqldump --defaults-extra-file=/secure/backup-client.cnf \
  --single-transaction --source-data=2 \
  --routines --events --triggers --hex-blob \
  --databases app_db > /backup/app_db.sql

# 在隔离的恢复实例执行；dump 包含数据库选择/创建语句，目标实例应预先准备好权限和容量。
mysql --defaults-extra-file=/secure/recovery-client.cnf < /backup/app_db.sql

# 根据 dump 中记录的日志文件与起始位置，选出误操作前的结束位置并先导出待检查 SQL。
# 示例位置仅用于演示；真实值必须从备份坐标和 binlog 内容核实，且要包含连续日志文件。
mysqlbinlog --start-position=456 --stop-position=9812 \
  /backup/binlog.000123 > /backup/pitr-review.sql

# 检查恢复 SQL 的时间范围、数据库和事务边界后，再应用到隔离恢复实例。
mysql --defaults-extra-file=/secure/recovery-client.cnf < /backup/pitr-review.sql
```

MySQL 新版本可能使用 `--source-data`，旧版本常见 `--master-data`；GTID、权限和参数行为也会随版本及托管数据库平台变化。先用目标环境的 `mysqldump --help` 确认参数，并验证认证文件权限。恢复前要确认 binlog 连续、备份坐标有效、目标实例与业务隔离；恢复后核对行数、关键业务数据、字符集、时区、触发器和应用读写，再记录实际 RPO/RTO。不要直接把未检查的 binlog 输出回放到生产主库。

## 数据库账号与敏感数据

- 应用运行账号仅拥有所需库/表的 `SELECT/INSERT/UPDATE/DELETE` 等权限；不要默认授予 `CREATE`、`ALTER`、`DROP` 或全局管理员权限。
- Schema migration 使用独立账号和受控发布流程；报表/只读任务使用只读账号。
- 密码、证书和连接串从 Secret 管理系统或环境配置注入，代码仓库不保存明文。
- 个人信息、支付数据按业务和法规要求最小化收集，传输加密、访问审计、日志脱敏；需可查询加密时选择经过评审的字段级方案。
- 用户密码采用成熟的自适应哈希（如 BCrypt/Argon2 的项目支持实现），不要可逆加密或直接 SHA/MD5。
- SQL 参数日志只记录脱敏后的模板/摘要；避免把 Token、身份证号、卡号和完整个人信息写入 SQL 慢日志之外的应用日志。

## 生产排错顺序

```text
浏览器 Network：请求 ID、筛选条件、状态码、响应时间
  -> 应用日志：traceId、SQL 模板、参数脱敏摘要、事务耗时
  -> 连接池：active/idle/waiting、连接获取超时
  -> MySQL：慢查询、EXPLAIN、锁等待、死锁、活动事务
  -> 复制：副本延迟、复制线程/位点、读写路由
  -> 发布：Schema 版本、迁移脚本、应用版本、备份点
```

不要直接在生产执行未经评审的 UPDATE/DELETE。先用只读查询确认命中行数和条件，再在受控事务/维护窗口内分批执行，保留操作人、脚本版本、影响行数和回滚/恢复方案。

## 练习路线

1. 为订单详情和订单列表分别画出主键/二级索引结构，使用 `EXPLAIN` 判断可能的回表和扫描范围。
2. 用两个事务模拟 Repeatable Read 与 Read Committed 下的快照读，再比较普通 SELECT 和 `FOR UPDATE`。
3. 让两个事务按相反顺序更新两行，观察死锁日志；调整访问顺序并设计有限幂等重试。
4. 在测试环境模拟主从延迟，验证保存后立即读取如何避免前端短暂显示旧值。
5. 设计新增字段的 expand/backfill/switch/contract 发布步骤，说明每一步如何兼容旧版本。
6. 从备份恢复到隔离环境，记录恢复耗时、缺失日志和实际 RPO/RTO。

继续学习：[MySQL 与 Redis 基础](./storage.md)、[SQL 查询与写入实战](./sql-practice.md)、[Spring 数据访问与事务](../spring/data-access.md)。
