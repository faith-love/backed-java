# SQL 查询与写入实战：筛选、关联、聚合与分页

前端看到的是筛选表单、列表和统计卡片；数据库最终执行的是 SQL。读懂查询的关键，是把每个前端条件映射到 `WHERE`，把列表列映射到 `SELECT`，把关联字段映射到 `JOIN`，把统计口径映射到 `GROUP BY`，再通过索引和执行计划验证代价。

本章用订单场景说明常用查询。表结构仅作教学示例，字段和数据库版本要以实际项目为准。

## 先建立查询链路

```text
前端 Query/Body
  -> Controller 参数绑定
  -> Query DTO 校验与默认值
  -> Service 补充当前用户/租户的数据范围
  -> Mapper 参数绑定和 SQL
  -> MySQL 选择索引并执行
  -> Row/Entity/DTO 映射
  -> Service 组装 VO 与分页 total
```

前端传入 `status=PAID` 只说明请求带了这个筛选值。要确认结果正确，需要检查 Controller 是否绑定、Service 是否改写、Mapper 是否添加条件、数据库是否命中预期记录，以及 VO 是否映射正确。

## 一、WHERE：把筛选表单转换成条件

```sql
-- 返回当前租户的已支付订单；参数由 JDBC/MyBatis 绑定，不拼接到 SQL 文本。
SELECT id, order_no, status, amount, created_at
FROM orders
WHERE tenant_id = ?
  AND status = ?
  AND created_at >= ?
  AND created_at < ?
ORDER BY created_at DESC, id DESC
LIMIT ?;
```

常见条件：

| SQL | 场景 | 注意点 |
|---|---|---|
| `column = ?` | 等值查询 | 索引列类型和参数类型要一致 |
| `column IN (?, ?, ...)` | 多选状态、批量 ID | 空列表必须定义语义，不能误生成无条件查询 |
| `column >= ? AND column < ?` | 时间范围 | 半开区间避免结束时刻精度边界 |
| `LIKE 'prefix%'` | 前缀搜索 | 通常比前置通配符更容易使用普通索引 |
| `IS NULL` | 区分未填写/未知 | `= NULL` 不会得到期望结果 |
| `AND` / `OR` | 组合筛选 | 多条件混用时加括号明确逻辑 |

不要把客户端提交的 `tenantId`、`userId` 当作授权依据。身份与数据范围应从服务端认证上下文取得，并参与 SQL 条件。

## 二、JOIN：把关联表数据组合起来

假设 `orders.customer_id` 指向 `customer.id`，列表需要展示客户名称：

```sql
-- LEFT JOIN 保留没有匹配客户行的订单；INNER JOIN 会丢弃无匹配记录。
SELECT
    o.id,
    o.order_no,
    o.status,
    o.amount,
    c.display_name AS customer_name
FROM orders AS o
LEFT JOIN customer AS c
    ON c.id = o.customer_id
WHERE o.tenant_id = ?
  AND o.status = ?
ORDER BY o.created_at DESC, o.id DESC
LIMIT ?;
```

| JOIN 类型 | 返回规则 | 典型用途 |
|---|---|---|
| `INNER JOIN` | 只保留两边都匹配的行 | 订单必须有有效客户才进入报表 |
| `LEFT JOIN` | 保留左表全部行，右侧缺失字段为 NULL | 主列表允许关联数据缺失 |
| `RIGHT JOIN` | 保留右表全部行 | 通常可交换表顺序改写成 LEFT JOIN |
| `CROSS JOIN` | 两表笛卡尔积 | 少见，必须清楚组合规模 |

把右表筛选条件写在 `WHERE` 可能把 `LEFT JOIN` 的效果变成类似 `INNER JOIN`：

```sql
-- 这会排除 c.status 为 NULL 的订单行，可能改变 LEFT JOIN 的语义。
WHERE c.status = 'ACTIVE'

-- 若需要保留左表行，把右表筛选写入 ON 条件。
LEFT JOIN customer AS c
  ON c.id = o.customer_id
 AND c.status = 'ACTIVE'
```

`JOIN` 之前要判断关系基数：一对一、一对多还是多对多。一对多 JOIN 会把主表行复制成多行，分页和 `COUNT(*)` 可能因此偏大；需要先分页主表，再批量查询子表或聚合后再关联。

## 三、聚合：统计卡片与报表口径

```sql
-- 按状态统计当前租户的订单数和金额。
SELECT
    status,
    COUNT(*) AS order_count,
    SUM(amount) AS total_amount,
    AVG(amount) AS average_amount,
    MIN(created_at) AS first_order_at,
    MAX(created_at) AS latest_order_at
FROM orders
WHERE tenant_id = ?
  AND created_at >= ?
  AND created_at < ?
GROUP BY status
ORDER BY order_count DESC;
```

- `COUNT(*)` 统计行；`COUNT(column)` 不统计该列为 NULL 的行。
- `SUM`、`AVG` 遇到 NULL 的处理与产品口径要一致。
- 金额列使用 `DECIMAL`，Java 侧用 `BigDecimal`，避免浮点精度问题。
- `GROUP BY` 的每个非聚合输出列要符合当前 SQL 模式要求；不要依赖数据库关闭严格模式后的非确定行为。
- 统计结果要明确时间边界、时区、逻辑删除状态和数据权限，不能只看 SQL 是否返回一个数字。

### `HAVING` 与 `WHERE` 的位置

```sql
-- WHERE 先过滤输入行，GROUP BY 聚合，HAVING 再过滤聚合结果。
SELECT customer_id, COUNT(*) AS order_count
FROM orders
WHERE tenant_id = ?
  AND status = 'PAID'
GROUP BY customer_id
HAVING COUNT(*) >= ?
ORDER BY order_count DESC;
```

能在聚合前确定的条件尽量放 `WHERE`，减少需要参与聚合的数据；聚合结果条件才放 `HAVING`。

## 四、子查询与 EXISTS

```sql
-- 查询至少有一笔已支付订单的客户。
SELECT c.id, c.display_name
FROM customer AS c
WHERE c.tenant_id = ?
  AND EXISTS (
      SELECT 1
      FROM orders AS o
      WHERE o.customer_id = c.id
        AND o.tenant_id = c.tenant_id
        AND o.status = 'PAID'
  );
```

`EXISTS` 表达“是否存在关联行”，有时比先取出所有订单 ID 更清楚。优化器可能重写子查询，因此不要凭写法外观断言谁一定更快；用代表性数据的 `EXPLAIN` 和实际指标验证。

需要取每个客户最新订单时，可以使用窗口函数（数据库版本支持时）或先聚合最大时间再 JOIN。必须处理相同时间多条记录的并列情况；只按 `MAX(created_at)` 不能唯一定位订单。

## 五、列表分页和 total

```sql
-- 数据页：稳定排序必须包含唯一的第二排序键。
SELECT id, order_no, status, amount, created_at
FROM orders
WHERE tenant_id = ?
  AND status = ?
ORDER BY created_at DESC, id DESC
LIMIT ? OFFSET ?;

-- 总数查询：过滤条件必须与数据页保持一致。
SELECT COUNT(*)
FROM orders
WHERE tenant_id = ?
  AND status = ?;
```

前端常见响应是 `{ rows, total }`。要确认 total 是精确值还是估算值；一对多 JOIN 统计时应使用正确的主键去重或先聚合，避免 total 与页面行数不一致。深 offset 会越来越慢时，改为游标分页并在接口契约中定义 `nextCursor`。

## 六、INSERT、UPDATE、DELETE 与 UPSERT

查询之外，写入语句决定数据如何创建、修改和删除。前端提交成功后，后端要确认 SQL 的影响行数、唯一约束、事务边界和重复请求语义。

```sql
-- 插入新商品；问号占位符由 JDBC/MyBatis 绑定真实参数。
INSERT INTO inventory (sku, name, quantity, version)
VALUES (?, ?, ?, 0);

-- 按主键和旧版本号更新订单，避免覆盖其他用户刚保存的修改。
UPDATE orders
SET status = ?,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = ?
  AND tenant_id = ?
  AND version = ?;

-- 只在确认业务允许物理删除时执行，并限制到当前租户的目标记录。
DELETE FROM draft_orders
WHERE id = ?
  AND tenant_id = ?;
```

更新或删除后要检查 affected rows：乐观锁更新返回 `0` 通常表示记录不存在、租户条件不匹配或版本已过期；不能把它一概当成成功。真实项目常使用逻辑删除；物理删除要考虑外键、审计和数据保留要求。

### UPSERT：用唯一键处理重复提交

UPSERT 表示“唯一键不存在时插入，已存在时更新”。它需要数据库唯一约束兜住并发竞争；仅在 Service 中先查再插，两个请求仍可能同时通过检查。

```sql
-- sku 必须有 UNIQUE 约束；相同请求重复执行会把数量更新为同一个目标值。
INSERT INTO inventory (sku, name, quantity)
VALUES (?, ?, ?) AS incoming
ON DUPLICATE KEY UPDATE
    name = incoming.name,
    quantity = incoming.quantity;
```

上面的行别名语法适用于 MySQL 8.0.19 及更新版本。MySQL 8.0.20 起 `VALUES(column)` 在 `ON DUPLICATE KEY UPDATE` 中已弃用；旧版本和 PostgreSQL、SQLite 的 UPSERT 语法不同，应按目标数据库版本查阅文档。计数器累加等“每次请求都会改变结果”的 UPSERT 并不天然幂等，支付、库存等业务还需要请求幂等键和事务设计。

批量插入可使用多值 `INSERT` 或 JDBC/MyBatis batch 减少往返；按可控大小分批，并依据业务原子性选择是否放进同一事务。超大批次会增加锁持有时间、binlog 体积和失败重试成本。

## 七、动态筛选与空值语义

查询 DTO 应区分字段未传、传 null、空字符串和空数组。例如：

| 请求值 | 可能语义 | 需要在接口中约定 |
|---|---|---|
| 不传 `status` | 不按状态筛选 | 是否返回所有可见状态 |
| `status=PAID` | 只查已支付 | 是否大小写敏感、未知值怎么处理 |
| `status=` | 空字符串 | 忽略、拒绝还是代表空状态 |
| `statusIds=[]` | 空集合 | 返回空列表还是忽略此条件 |

不要使用 `${sortBy}` 直接拼接前端字符串。把允许排序的 API 字段映射到服务端固定 SQL 列名和方向，再由 Mapper/QueryWrapper 生成排序。

## 八、查询计划和性能验证

1. 记录一次实际请求的 URL、筛选参数、页码、排序和响应 rows/total。
2. 找对应 Mapper 方法和最终 SQL 模板，确认租户、状态、时间条件均存在。
3. 使用脱敏后的代表性参数执行 `EXPLAIN`，查看 key、扫描行数、排序和回表。
4. 在测试/预发环境观察执行耗时、SQL 次数、返回行数和连接池等待。
5. 对比改动前后的页面响应时间及数据库负载；单次本地 EXPLAIN 不是生产性能证明。

关联索引和 MySQL 执行计划详见 [MySQL 与 Redis](./storage.md) 和 [MySQL 生产机制与运维](./mysql-operations.md)。

## 练习

1. 为订单列表实现按状态、时间范围、客户名搜索和稳定分页，并给出对应索引方案。
2. 实现“客户订单统计”查询，分别使用 JOIN+GROUP BY 与子查询，核对重复行和统计口径。
3. 为“订单数大于 N 的客户”写 `HAVING` 查询，并说明为何阈值条件不能放入普通 WHERE。
4. 让前端提交空数组、空字符串和未知状态，观察 DTO 校验、动态 SQL 和响应结果。
5. 给一条慢列表查询建立证据：Network 参数、最终 SQL、EXPLAIN、SQL 耗时和返回 rows/total。

下一步：[JDBC、MyBatis 与 ORM](./persistence.md)、[MySQL 生产机制与运维](./mysql-operations.md)、[Spring 数据访问与事务](../spring/data-access.md)。
