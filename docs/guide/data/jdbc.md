# JDBC：Java 如何执行 SQL、管理连接和读取结果

JDBC（Java Database Connectivity）是 Java 访问关系型数据库的标准 API。MyBatis、JPA、Spring JDBC 等数据访问框架最终仍会通过 JDBC 驱动发送 SQL。理解 JDBC 可以帮助你沿着一条列表请求检查：参数在哪里绑定、连接从哪里来、事务何时提交、结果如何映射，以及连接为什么可能被耗尽。

## 一次 JDBC 查询包含哪些对象

```text
应用代码
  -> DataSource：提供 Connection，通常由连接池管理
  -> Connection：表示一次数据库会话，可承载事务
  -> PreparedStatement：SQL 模板和绑定参数
  -> ResultSet：游标式读取数据库返回行
  -> JDBC Driver：将 API 调用编码为数据库协议
  -> MySQL / PostgreSQL / Oracle 等数据库
```

| JDBC 类型 | 解决的问题 | 使用边界 |
|---|---|---|
| `DataSource` | 统一提供连接，也能封装连接池和连接配置 | 通常由 Spring/连接池创建和管理，不要每个请求 new 一个 |
| `Connection` | 执行 SQL、控制本地事务、访问元数据 | 不是线程安全的共享对象；一笔事务期间由同一线程/事务上下文使用 |
| `Statement` | 执行固定 SQL | 用户输入拼接有注入风险；通常选 PreparedStatement |
| `PreparedStatement` | 预编译 SQL 并绑定值参数 | `?` 只能绑定值，不能绑定表名、列名或 `ORDER BY` 语法 |
| `ResultSet` | 按游标读取查询行 | 需要及时关闭；驱动取数方式和 fetch size 影响内存/网络行为 |
| `SQLException` | 表示 SQL、连接和数据库协议错误 | 应结合 SQLState、厂商错误码和异常链判断，不能只看 message |

## 安全查询：参数绑定与结果映射

下面示例可作为标准 JDBC 代码片段。假设 `dataSource` 和 `tenantId` 已由应用提供；try-with-resources 负责按逆序关闭结果集、语句和逻辑连接。

```java
// 从连接池申请逻辑连接；离开 try 作用域后归还连接池。
try (Connection connection = dataSource.getConnection();
     // 用问号绑定值，禁止把用户输入直接拼到 SQL 字符串。
     PreparedStatement statement = connection.prepareStatement(
         "SELECT id, order_no, status, amount " +
         "FROM orders WHERE tenant_id = ? AND status = ? " +
         "ORDER BY created_at DESC, id DESC LIMIT ?")) {

    // 参数位置从 1 开始；参数类型尽量与数据库字段类型对应。
    statement.setLong(1, tenantId);
    // 将筛选状态作为值绑定，数据库不会把它解析为 SQL 语法。
    statement.setString(2, "PAID");
    // 分页大小也作为值传入，应用应先校验范围上限。
    statement.setInt(3, 20);

    // 执行查询并以游标方式取得结果。
    try (ResultSet rows = statement.executeQuery()) {
        // next() 前进到下一行；返回 false 表示没有更多结果。
        while (rows.next()) {
            // 按列名取值比依赖列序号更不易因 SQL 改列顺序而出错。
            long id = rows.getLong("id");
            String orderNo = rows.getString("order_no");
            String status = rows.getString("status");
            BigDecimal amount = rows.getBigDecimal("amount");

            // 教学示例仅展示读取；真实代码通常映射成 DTO/Entity。
            System.out.printf("%d %s %s %s%n", id, orderNo, status, amount);
        }
    }
}
```

如果动态排序字段来自前端，不能写 `ORDER BY ?` 期待占位符代表列名。应将请求字段映射到服务端固定白名单：`createdAt -> created_at`、`amount -> amount`，方向只允许 `ASC` 或 `DESC`，再安全生成 SQL 语法。值与 SQL 结构的边界也是 MyBatis `#{}` 和 `${}` 的核心区别。

## 查询、更新和批处理分别如何执行

| 场景 | JDBC 方法 | 返回内容 | 常见错误 |
|---|---|---|---|
| SELECT | `executeQuery()` | `ResultSet` | 把大结果集一次复制进巨大 List，造成堆内存上升 |
| INSERT/UPDATE/DELETE | `executeUpdate()` | 影响行数 | 不检查行数，误把乐观锁冲突当成功 |
| 统一处理不确定 SQL 类型 | `execute()` | 是否有 ResultSet，再通过 API 读取结果 | 无需所有语句都用它，代码分支较多 |
| 批量写入 | `addBatch()` + `executeBatch()` | 每条语句更新数/特殊常量 | 超大批次、超长事务、驱动批处理差异和部分失败 |

```java
// 使用一个事务执行一批写入；事务范围和批大小需按业务与数据库容量确定。
try (Connection connection = dataSource.getConnection();
     PreparedStatement statement = connection.prepareStatement(
         "INSERT INTO order_tag (order_id, tag) VALUES (?, ?)")) {

    // 保留原自动提交状态，方法结束时恢复，避免污染连接池复用的连接。
    boolean previousAutoCommit = connection.getAutoCommit();
    try {
        // 关闭自动提交，让这批关联写入使用一个本地事务。
        connection.setAutoCommit(false);

        // 每个循环只绑定一行数据，并把语句加入驱动批次。
        for (OrderTag tag : tags) {
            // 订单 ID 是数据库数值字段，按 long 绑定。
            statement.setLong(1, tag.orderId());
            // 标签内容按字符串值绑定。
            statement.setString(2, tag.tag());
            // 暂存当前参数组，稍后批量发送。
            statement.addBatch();
        }

        // 执行批次，返回值应根据驱动与业务预期检查。
        int[] updateCounts = statement.executeBatch();
        // 全部成功后提交本地数据库事务。
        connection.commit();
        // 示例输出影响行数数组，真实代码可校验异常和 EXECUTE_FAILED。
        System.out.println("batch rows=" + updateCounts.length);
    } catch (SQLException | RuntimeException failure) {
        // 批次失败时回滚当前本地事务，避免保留部分业务写入。
        connection.rollback();
        // 保留原始异常，让上层根据 SQLState/错误码转换业务错误。
        throw failure;
    } finally {
        // 归还连接之前恢复连接原始模式，避免影响下一个借用者。
        connection.setAutoCommit(previousAutoCommit);
    }
}
```

批量操作的事务语义受数据库和 JDBC 驱动影响。超大批次宜拆成有上限的小批次；如果业务要求所有行全成或全败，则确认事务日志、锁持有和包大小能够承受整个事务。不要在一个数据库事务中等待用户交互或远程支付网络。

## JDBC 事务：Connection 是本地事务边界

默认情况下连接通常为自动提交；每条语句完成后数据库就提交。需要多条 SQL 一起成功时，用同一个 `Connection` 关闭自动提交，最后 `commit()`；失败时 `rollback()`。

示例中的 `decreaseInventory` 与 `insertOrder` 表示应用自己的 DAO 方法；它们必须使用传入的同一个 `Connection` 执行语句。该方法应声明 `throws SQLException`，确保调用方看得到数据库失败。

```java
// 同一个连接上的两条更新构成同一个关系型数据库本地事务。
try (Connection connection = dataSource.getConnection()) {
    // 保存连接原配置，以便 finally 时恢复连接池的连接状态。
    boolean previousAutoCommit = connection.getAutoCommit();
    try {
        // 关闭每条语句自动提交，开始本地事务工作。
        connection.setAutoCommit(false);

        // 先扣减库存；SQL 应以可用数量为条件并检查更新行数。
        int inventoryRows = decreaseInventory(connection, sku, quantity);
        // 更新数不是 1 时视为库存不足或 SKU 不存在。
        if (inventoryRows != 1) {
            throw new IllegalStateException("库存不足或商品不存在");
        }

        // 在同一连接中创建订单，因此库存和订单可共同提交或回滚。
        insertOrder(connection, orderId, sku, quantity);

        // 只有业务要求的 SQL 都成功后才提交。
        connection.commit();
    } catch (SQLException | RuntimeException failure) {
        // 本地事务失败时撤销同一数据库中的未提交修改。
        try {
            // 若回滚本身也失败，把它附加在原异常上而不覆盖原始根因。
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // suppressed 异常可在日志异常链中检查回滚失败。
            failure.addSuppressed(rollbackFailure);
        }
        // 继续传播原失败，不要吞掉异常并误报成功。
        throw failure;
    } finally {
        // 恢复自动提交，避免连接池把非默认状态交给下一请求。
        connection.setAutoCommit(previousAutoCommit);
    }
}
```

这个事务只能覆盖同一个数据库连接/事务管理器管理的资源，不会自动回滚 Redis、HTTP、消息代理、文件写入或另一数据源。若 `commit()` 时连接中断，客户端可能不知道数据库是否已经提交；不能把不确定结果简单当作“没成功”再盲目重试，需用幂等键或查询业务状态确认。Spring `@Transactional` 是框架层围绕同类 JDBC 事务的声明式管理，传播、异常回滚规则和代理边界见 [Spring 数据访问与事务](../spring/data-access.md)。

## 连接池：为什么 Connection 不能随手创建

建立数据库网络连接和认证有成本。`DataSource` 通常从 HikariCP、Druid 等连接池借出逻辑连接；调用 `Connection.close()` 多数情况下是归还连接池，而非关闭物理 socket。连接池用最大连接数限制数据库并发压力，同时要设置获取等待上限、空闲/最大生命周期和连接泄漏检测。

| 现象 | 可能原因 | 要检查的证据 |
|---|---|---|
| `getConnection()` 等很久 | 池中连接全被借走、数据库响应慢、连接泄漏 | active/idle/pending、获取连接耗时、慢 SQL 和连接持有时长 |
| 请求线程很多都卡在 SQL | 查询慢、行锁等待或数据库容量饱和 | SQL 耗时、锁等待、连接池等待和线程栈 |
| 数据库连接数过高 | 池上限按每个实例设置后总和过大、实例扩容 | 实例数量 × 单实例 maxPoolSize 与数据库 max_connections |
| 偶发连接失效 | 防火墙/NAT 空闲回收、数据库切换、连接寿命过长 | 连接错误码、池健康检查与数据库切换时间 |

Connection、Statement、ResultSet 应在确定的作用域内使用，不要保存在 Spring 单例 Service 字段中或跨线程共享。连接池上限是所有应用实例共同的预算，扩容应用实例会乘大总连接数。

## 类型映射、NULL 与生成主键

- SQL `DECIMAL` 映射金额时使用 Java `BigDecimal`；不要用 `double` 表示精确金额。
- SQL `TIMESTAMP`/`DATETIME` 到 `Instant`、`LocalDateTime` 的语义取决于数据库类型、驱动、时区和应用设置，要统一 UTC/业务时区约定。
- `ResultSet.getInt()` 在 SQL 值为 NULL 时通常返回 `0`，还要用 `wasNull()` 区分；可用包装类型或显式 NULL 处理。
- 列别名是结果映射协议的一部分：`select user_name as userName` 可与驼峰属性映射配合，但大小写和驱动行为要实测。
- 插入后需要自增键时用 `Statement.RETURN_GENERATED_KEYS`，再读取 generated keys；数据库和驱动对批量生成键的支持有差异。

## 资源关闭与异常处理

try-with-resources 会在正常结束、return 和异常路径都关闭实现 `AutoCloseable` 的资源。关闭顺序按声明的逆序：ResultSet、Statement、Connection。若流和数据库连接嵌套，应确定由哪一层拥有并关闭各资源，避免重复关闭或把返回给调用方的流提前关掉。

`SQLException` 常包含 vendor code、SQLState、cause 和 batch update counts。日志应记录 SQL 模板、耗时、影响行数和经过脱敏的参数摘要；不要把完整密码、Token、个人信息和支付卡数据写入日志。错误要继续转换为稳定的 API 错误契约，不能把驱动堆栈直接返回前端。

## JDBC 与 MyBatis、JPA 的关系

| 方式 | 你直接控制什么 | 框架帮你做什么 | 适合场景 |
|---|---|---|---|
| JDBC | SQL、参数、事务调用、ResultSet | 连接通过 DataSource 提供 | 学习基础、少量轻量 SQL、需要明确控制时 |
| Spring JDBC | SQL 与 RowMapper | 异常转换、模板资源管理 | 保留 SQL 控制力并减少样板代码 |
| MyBatis | SQL 模板、Mapper 和映射规则 | 参数绑定、执行、结果映射 | SQL 复杂、需要读写明确 SQL 时 |
| JPA/Hibernate | Entity 关系和 Repository 操作，也可写 JPQL/SQL | 持久化上下文、脏检查、关系映射 | 领域对象关系丰富且团队理解 ORM 生命周期时 |

高层框架降低重复代码，但不消除连接池、SQL 执行计划、事务、锁和数据库版本差异。理解 JDBC 后再读框架生成的最终 SQL，更容易判断慢查询、参数类型和事务边界。

## 前端联调与练习

1. 从浏览器列表 Query 找到 Controller DTO 和 Mapper SQL，记录值如何绑定到 JDBC 参数。
2. 在测试库运行一次 SELECT、一次条件 UPDATE 和一次事务回滚，比较 affected rows 与页面反馈。
3. 调整连接池上限或制造慢查询时，观察连接等待、请求线程和浏览器耗时的关系。
4. 检查某个 ORM 框架最终生成的 SQL、参数类型和执行计划，不只看 Java 方法名。
5. 设计一条批量写入，确定事务原子范围、批次大小、重试幂等和失败反馈。

继续学习：[JDBC、MyBatis 与 ORM 总览](./persistence.md)、[关系型/缓存/时序数据库](./database-types.md)、[SQL 查询与写入实战](./sql-practice.md)。
