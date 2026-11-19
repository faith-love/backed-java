# Spring 数据访问：从连接池、Mapper 到事务边界

Spring 数据访问解决的是：**一次请求如何安全、可观测、可回滚地读写数据库**。前端看到的是列表、详情和保存按钮；后端还必须处理连接池、SQL 参数、对象映射、分页稳定性、事务边界、并发更新和慢查询。

## 先看完整链路

```text
Axios/fetch
  -> Controller：绑定 query/path/body，校验输入
  -> Service：确定业务规则、权限范围和事务边界
  -> Mapper/Repository：声明查询或更新
  -> DataSource：从连接池借连接
  -> JDBC Driver：把 JDBC 调用翻译为数据库协议
  -> Database：执行 SQL、锁定、提交/回滚
  -> Mapper：把行映射为 Entity/DTO
  -> Service：组合并转换为 VO
  -> Controller：序列化 JSON
```

每一层的失败含义不同：

| 现象 | 首先检查 | 可能的根因 |
|---|---|---|
| 连接超时 | 连接池指标、数据库网络 | 连接池耗尽、数据库不可达、连接泄漏 |
| 查询为空 | SQL 参数、数据范围条件 | 参数绑定错误、逻辑删除、租户条件缺失 |
| 分页重复/遗漏 | `ORDER BY` 和游标 | 排序不稳定、数据在翻页期间变化 |
| 保存部分成功 | 事务日志和异常 | 方法没有经过代理、异常被吞、跨库写入 |
| 更新覆盖别人修改 | 版本字段、影响行数 | 没有乐观锁或没有检查更新结果 |
| 列表越来越慢 | SQL、执行计划、返回行数 | N+1、缺索引、查出过多列/行 |

## JDBC、JdbcTemplate、MyBatis 和 JPA 怎么选

| 方案 | 解决的问题 | 优点 | 成本/风险 | 适合场景 |
|---|---|---|---|---|
| JDBC | 直接控制连接、语句和结果集 | 行为最明确、依赖少 | 样板多，易忘记关闭资源 | 极少量底层适配、特殊驱动 |
| `JdbcTemplate` | 统一资源管理、参数绑定和异常转换 | 比 JDBC 简洁，SQL 仍透明 | 映射代码仍需维护 | 简单查询、报表、批处理 |
| MyBatis | 保留 SQL，同时生成 Mapper 代理和映射 | 复杂 SQL 可控、性能可调 | XML/注解和映射需要测试 | 传统业务系统、复杂查询 |
| MyBatis-Plus | 简化 CRUD、分页和条件构造 | 常规增删改查快 | Wrapper 可能隐藏复杂 SQL，插件配置影响行为 | 以 MyBatis 为主的 CRUD 系统 |
| Spring Data JPA | 以实体和对象关系建模 | CRUD 和关系导航方便 | SQL 可能不直观，懒加载/N+1 需治理 | 领域模型稳定、关系映射较多 |

项目已经使用 MyBatis/MyBatis-Plus 时，不要为了“统一”突然引入 JPA；先遵循现有事务、分页和 Mapper 约定。选型的关键是查询复杂度、团队排错能力和数据库控制需求。

## `JdbcTemplate`：小查询和批处理的直接选择

`JdbcTemplate` 由 Spring 管理连接申请、PreparedStatement 参数绑定、ResultSet 关闭和 `SQLException` 到 `DataAccessException` 的转换。它仍然执行你写的 SQL，所以需要自己负责字段、索引和分页。

```java
// Spring 集成片段：需要 spring-jdbc、数据库驱动和 DataSource Bean。
@Repository
public class UserJdbcRepository {

    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // 构造器注入 DataSource 创建的 JdbcTemplate，便于测试替换。
    public UserJdbcRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserView findById(long userId) {
        // 使用 ? 参数绑定，不把 userId 拼进 SQL 文本。
        String sql = "select id, user_name, status from sys_user where id = ?";

        // query 方法把每一行交给 RowMapper 映射成一个视图对象。
        java.util.List<UserView> rows = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new UserView(
                        resultSet.getLong("id"),
                        resultSet.getString("user_name"),
                        resultSet.getInt("status")),
                userId);

        // 业务上 id 应唯一；找不到时返回 null 还是抛异常要由项目约定决定。
        return rows.isEmpty() ? null : rows.get(0);
    }
}

// 示例 DTO；实际项目可放在 api/vo 包中。
final class UserView {
    private final long id;
    private final String userName;
    private final int status;

    UserView(long id, String userName, int status) {
        this.id = id;
        this.userName = userName;
        this.status = status;
    }

    public long getId() { return id; }
    public String getUserName() { return userName; }
    public int getStatus() { return status; }
}
```

使用 `queryForObject` 时要注意：没有结果或多行都会抛异常。如果“没有结果”是正常分支，`query` 后判断列表通常更容易表达语义。不要在 RowMapper 中执行额外查询，否则很容易形成 N+1。

### 批量写入

```java
// Spring 集成片段：批量插入时仍要控制单批大小，避免一次占用过多内存。
public int[] insertTags(java.util.List<Tag> tags) {
    return jdbcTemplate.batchUpdate(
            "insert into order_tag(order_id, tag_name) values (?, ?)",
            tags,
            500,
            (statement, tag) -> {
                // 第一个参数绑定订单 ID。
                statement.setLong(1, tag.getOrderId());
                // 第二个参数绑定标签文本。
                statement.setString(2, tag.getName());
            });
}

final class Tag {
    private final long orderId;
    private final String name;

    Tag(long orderId, String name) {
        this.orderId = orderId;
        this.name = name;
    }

    public long getOrderId() { return orderId; }
    public String getName() { return name; }
}
```

批处理只是减少网络往返，不代表数据库一定一次性提交。是否在事务中执行、失败时如何处理部分批次，要结合 JDBC 驱动和事务配置验证。

## MyBatis：SQL、Mapper 和映射边界

MyBatis 的 Mapper 接口是数据访问契约，框架在启动时生成代理。`@Mapper` 或 `@MapperScan` 负责注册 Mapper；XML/注解 SQL 负责查询；`resultMap`、类型别名和 TypeHandler 负责映射。

```java
// Spring 集成片段：由 @MapperScan 或接口上的 @Mapper 注册。
@org.apache.ibatis.annotations.Mapper
public interface OrderMapper {

    // 多参数用 @Param 明确名字，XML 中才能稳定使用 #{customerId}。
    java.util.List<OrderRow> findPage(
            @org.apache.ibatis.annotations.Param("customerId") Long customerId,
            @org.apache.ibatis.annotations.Param("offset") int offset,
            @org.apache.ibatis.annotations.Param("pageSize") int pageSize);
}
```

```xml
<!-- XML 集成片段：namespace 必须与 Mapper 接口全限定名一致。 -->
<mapper namespace="com.example.order.OrderMapper">
    <select id="findPage" resultType="com.example.order.OrderRow">
        SELECT id, order_no, status, created_at
        FROM orders
        WHERE customer_id = #{customerId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{offset}, #{pageSize}
    </select>
</mapper>
```

`#{}` 是预编译参数绑定；`${}` 是文本替换，只能接收经过白名单转换的表名、列名或排序片段。分页排序不要直接接收前端传来的字符串：先把 `sort=createdAt` 映射成固定 SQL 片段，再拼接。

### MyBatis 映射要检查什么

1. 数据库列名与 Java 字段名是否通过下划线转驼峰或显式 `resultMap` 正确映射。
2. `Long`、`BigDecimal`、时间、JSON、枚举是否有正确 TypeHandler。
3. 逻辑删除、租户条件和数据范围是否出现在所有业务查询路径。
4. XML 动态 SQL 在空列表、null 参数和组合条件下是否仍然合法。
5. 返回 Entity、DTO 还是聚合结果，是否会把内部字段带给前端。

## MyBatis-Plus：通用 CRUD 不是查询设计替代品

```java
// Spring 集成片段：实体注解决定表名和字段映射。
@com.baomidou.mybatisplus.annotation.TableName("orders")
public class OrderEntity {

    @com.baomidou.mybatisplus.annotation.TableId
    private Long id;

    @com.baomidou.mybatisplus.annotation.TableField("order_no")
    private String orderNo;

    // 逻辑删除字段需要配合全局配置；自定义 SQL 仍要人工检查。
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted;

    // 更新时带上旧版本，影响行数为 0 表示并发冲突或记录不存在。
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}

@org.apache.ibatis.annotations.Mapper
interface OrderPlusMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<OrderEntity> {
}
```

```java
// 只查询需要的列，避免 select * 将大字段带入列表接口。
com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderEntity> wrapper =
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
wrapper.eq(OrderEntity::getDeleted, 0)
       .eq(OrderEntity::getCustomerId, customerId)
       .orderByDesc(OrderEntity::getCreatedAt)
       .orderByDesc(OrderEntity::getId);

// 分页参数来自服务端限制后的 pageNum/pageSize，不能直接信任前端的大页码。
com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderEntity> page =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);
com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderEntity> result =
        orderMapper.selectPage(page, wrapper);
```

Wrapper 适合简单条件；复杂 join、窗口函数、聚合和数据库特有语法仍然应该写清楚 SQL。不要因为“有 BaseMapper”就跳过索引、数据范围和返回字段设计。

## 事务：边界、传播、隔离和回滚

事务解决的是一组数据库操作的原子性和一致性。它不自动覆盖 HTTP、Redis、文件系统或第二个数据库。

### 事务应该放在哪里

通常把事务放在 Service 的业务用例边界，而不是 Controller 或单条 Mapper 上：

```java
@org.springframework.stereotype.Service
public class OrderApplicationService {

    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;

    public OrderApplicationService(OrderMapper orderMapper, InventoryMapper inventoryMapper) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
    }

    // 创建订单、扣减库存必须作为一个本地数据库事务提交。
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public void createOrder(CreateOrderCommand command) {
        // 先写订单，再扣库存；失败时两步一起回滚。
        orderMapper.insert(command.toEntity());
        int affected = inventoryMapper.decreaseIfEnough(
                command.sku(), command.quantity());

        // 用影响行数判断库存是否真的扣减，不能只看 SQL 是否执行完成。
        if (affected != 1) {
            throw new IllegalStateException("库存不足或商品不存在");
        }
    }
}
```

### 默认回滚与 `rollbackFor`

Spring 默认对 `RuntimeException` 和 `Error` 回滚，对受检异常不一定回滚。业务方法如果声明并抛出受检异常，应明确配置 `rollbackFor`，或转换成项目统一的运行时业务异常。

```java
// 受检异常也需要触发本地事务回滚时显式声明。
@Transactional(rollbackFor = java.io.IOException.class)
public void publishFile() throws java.io.IOException {
    // 数据库写入与文件发布并不是同一个事务资源；失败补偿仍需单独设计。
}
```

不要在事务方法内部 `catch (Exception e)` 后只打印日志并返回成功。这样代理看不到异常，事务可能正常提交，前端却得到成功响应。

### 传播行为怎么理解

| 传播行为 | 含义 | 使用场景 | 风险 |
|---|---|---|---|
| `REQUIRED` | 有事务就加入，没有就新建 | 默认业务用例 | 内层异常可能影响外层整体 |
| `REQUIRES_NEW` | 暂停外层，单独新建事务 | 审计日志、独立状态记录 | 外层回滚时内层已提交，语义容易误解 |
| `NESTED` | 在外层事务中创建保存点 | 局部回滚 | 依赖 JDBC/事务管理器支持 |
| `SUPPORTS` | 有就加入，没有也可运行 | 可选事务查询 | 调用方不同会产生不同一致性 |
| `MANDATORY` | 必须已有事务 | 强制由上层统一编排 | 独立调用会直接失败 |
| `NOT_SUPPORTED` | 挂起事务后非事务执行 | 不应占用事务连接的读取 | 读到的状态可能不在同一事务 |
| `NEVER` | 有事务就报错 | 明确禁止事务的操作 | 使用场景少 |

传播行为是调用协作契约，不是“性能开关”。`REQUIRES_NEW` 会额外占用连接；在高并发下，外层事务已经借了一个连接，内层新事务还要再借一个，连接池过小可能出现等待甚至死锁。

### 隔离级别与并发读写

| 隔离级别 | 主要保证 | 代价 |
|---|---|---|
| `READ_UNCOMMITTED` | 允许读到未提交数据 | 脏读、不可重复读、幻读风险高 |
| `READ_COMMITTED` | 只能读到已提交数据 | 同一事务两次读取可能不同 |
| `REPEATABLE_READ` | 同一行重复读取更稳定 | 锁/快照成本更高，幻读依数据库实现 |
| `SERIALIZABLE` | 最强隔离 | 并发度最低，容易锁等待 |

隔离级别不能替代业务约束。扣库存、唯一性和幂等通常还需要唯一索引、条件更新、版本号或数据库锁，并检查更新影响行数。

## 事务代理常见失效原因

1. 对象是 `new` 出来的，不是 Spring Bean。
2. 同类内部 `this.createOrder()` 调用，绕过代理。
3. 方法是 `private`、`final` 或代理类型无法覆写。
4. 异常被 catch 后吞掉，或者异常类型没有触发回滚。
5. 数据源/事务管理器不是当前 Mapper 使用的那个。
6. 多数据源、多线程、异步任务或远程服务被误认为共享同一事务。

如果确实需要在同一类内触发代理，可以把不同事务边界拆到另一个 Bean；不要优先使用暴露代理的技巧掩盖职责混乱。

## 分页：offset、游标和稳定排序

### Offset 分页

```sql
SELECT id, order_no, created_at
FROM orders
WHERE customer_id = #{customerId}
ORDER BY created_at DESC, id DESC
LIMIT #{offset}, #{pageSize};
```

适合后台列表和页码跳转，但深页的 `OFFSET` 需要扫描并丢弃前面的行；数据在翻页期间插入或删除时，可能重复或遗漏。必须有稳定、唯一的排序组合，例如 `created_at DESC, id DESC`，不能只按可能重复的时间排序。

### 游标/Keyset 分页

```sql
SELECT id, order_no, created_at
FROM orders
WHERE customer_id = #{customerId}
  AND (created_at, id) < (#{lastCreatedAt}, #{lastId})
ORDER BY created_at DESC, id DESC
LIMIT #{pageSize};
```

游标分页适合无限滚动、导出和大数据量列表。前端保存 `nextCursor`，不要把数据库内部自增 ID 当成可猜测的权限凭证；游标可以签名或编码，并在服务端校验查询条件没有被篡改。

### 分页接口契约

```ts
type PageResult<T> = {
  rows: T[]
  total?: number       // offset 分页通常提供 total
  nextCursor?: string  // 游标分页通常提供下一页游标
}
```

不要让前端同时依赖 `rows.length` 和后端 `total` 的不同语义。后端必须限制最大 `pageSize`，并对排序字段使用白名单。

## 连接池和资源生命周期

连接池解决的是复用数据库连接和限制并发连接数，不是“连接越多越快”。常见配置包括：

- `maximumPoolSize`：同时借出的最大连接数，应与数据库最大连接、实例数量和线程池并发共同计算。
- `connectionTimeout`：借不到连接时等待多久，超时要能被监控和定位。
- `maxLifetime`：连接最大存活时间，通常要小于数据库/代理强制断开时间。
- `idleTimeout`：空闲连接回收策略，不能频繁创建销毁。
- 验证查询/心跳：确认连接没有被网络设备静默断开。

如果 HTTP 线程池有 200 个线程而数据库连接池只有 10 个，额外线程只会排队；如果连接池配置为 1000 而数据库只允许 200 个连接，多实例部署后会互相抢占。调优要同时看请求并发、SQL 耗时、连接持有时间和数据库 CPU。

不要手工关闭由 Spring 管理的共享 DataSource；应用只关闭自己创建并拥有的资源。直接 JDBC 必须使用 try-with-resources，MyBatis/JdbcTemplate 会替你关闭语句和连接。

## N+1、慢查询和大结果集

N+1 的典型形态是先查一页订单，再在循环里为每个订单查询用户或商品：一次列表查询 + N 次详情查询。解决方式包括：

1. 用 JOIN 或批量 `IN` 查询一次取回关联数据。
2. 先取主键，再批量查详情并在 Service 中按 ID 分组。
3. 只返回接口真正需要的字段，避免 Entity 全量序列化。
4. 让 SQL 有稳定条件和索引，使用执行计划确认是否命中。
5. 对外部 HTTP/缓存调用做批量或并发上限，不要把数据库连接占着等待远程服务。

```java
// 反例：循环内每次调用 mapper，订单数越多 SQL 越多。
for (OrderEntity order : orders) {
    order.setCustomer(customerMapper.selectById(order.getCustomerId()));
}

// 改进：先收集 ID，再一次批量查询，最后用 Map 组装。
java.util.Set<Long> customerIds = orders.stream()
        .map(OrderEntity::getCustomerId)
        .collect(java.util.stream.Collectors.toSet());
java.util.Map<Long, CustomerView> customers = customerMapper
        .selectByIds(customerIds)
        .stream()
        .collect(java.util.stream.Collectors.toMap(CustomerView::getId, value -> value));
orders.forEach(order -> order.setCustomer(customers.get(order.getCustomerId())));
```

监控至少要记录 SQL 模板（脱敏）、耗时、返回行数、连接池等待时间、数据库实例和 traceId。不要把完整参数、密码、Token 或支付签名写入日志。

## 乐观锁、悲观锁和幂等更新

### 乐观锁

```sql
UPDATE inventory
SET available = available - #{quantity}, version = version + 1
WHERE sku = #{sku}
  AND version = #{version}
  AND available >= #{quantity};
```

更新影响行数为 1 才表示成功；为 0 可能是库存不足、版本冲突或 SKU 不存在，Service 必须区分并返回合适业务错误。

### 幂等键和唯一约束

支付回调、批量导入和重试请求不能只依赖 Java 内存锁。使用业务幂等键和数据库唯一索引：

```sql
CREATE UNIQUE INDEX uk_payment_request ON payment_record(request_id);
```

插入重复键时捕获并查询已存在结果，或使用数据库的 upsert 语义。事务只能保证本地写入原子性，不能阻止客户端重复提交；幂等约束要落到持久化层。

## 测试数据访问代码

| 测试方式 | 适合验证 | 代价 |
|---|---|---|
| Mapper 单测 + Mock | 参数转换和分支 | 不能证明真实 SQL |
| `@JdbcTest` | JdbcTemplate、RowMapper、SQL | 只加载 JDBC 切片，配置需准备 |
| `@MybatisTest` | Mapper、XML、TypeHandler | 通常需要测试数据库或替身 |
| `@SpringBootTest` | 完整 Bean、事务和配置 | 启动慢、环境依赖多 |
| Testcontainers | 真实数据库方言和索引 | 需要容器运行环境 |

```java
// Spring 集成片段：测试关注“库存不足时不写入订单”的事务结果。
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.transaction.annotation.Transactional
class OrderApplicationServiceTest {

    @org.junit.jupiter.api.Test
    void rollsBackWhenInventoryIsInsufficient() {
        // 准备订单和库存数据，调用 service，然后断言订单表没有残留半成品。
        // 真实项目应使用测试数据库，而不是只 Mock Mapper。
    }
}
```

测试中的 `@Transactional` 回滚不代表生产代码一定配置正确；要至少跑一次真实数据库集成测试，验证索引、约束、方言、锁和事务行为。

## 数据访问排错清单

### 1. 注入失败

检查 `@Mapper`/`@MapperScan`、包路径、数据源 Bean、Profile 和自动配置条件。`@Repository` 不会自动把普通接口变成 MyBatis Mapper。

### 2. SQL 参数不对

把浏览器请求参数、Controller DTO、Service 组装值、Mapper 参数和最终 SQL 模板逐层记录。使用脱敏参数，不要复制完整 Token 或个人信息。

### 3. 数据总是为空

检查租户/组织条件、逻辑删除字段、状态条件、时区、大小写、事务隔离和读写分离延迟。不要先断言“数据库没有数据”。

### 4. 事务没有回滚

确认调用经过代理、异常向外抛出、`rollbackFor` 合适、Mapper 使用同一数据源，且数据库表引擎支持事务。检查是否在异步线程或远程服务中误以为仍在同一事务。

### 5. 连接池耗尽

查看 active/idle/等待数、慢 SQL、连接持有时间和线程堆栈。常见原因是忘记关闭原生 JDBC 资源、事务里调用慢 HTTP、连接池过小或数据库锁等待。

### 6. 分页结果不稳定

确认排序包含唯一字段，前端没有混用 offset 与 cursor，后端没有把 `pageSize=0` 或超大值直接传给数据库。

## 给前端开发者的源码阅读练习

1. 任选一个列表接口，沿 Controller → Service → Mapper → XML/注解 SQL 跟踪 `status`、`pageNum` 和 `pageSize` 的变化。
2. 找一处 `@Transactional`，画出它覆盖的写操作，标记哪些操作实际上在数据库之外。
3. 在 SQL 日志中统计一次列表请求执行了多少条 SQL，判断是否存在 N+1。
4. 给更新接口增加版本号和影响行数判断，模拟两个浏览器标签页同时保存。
5. 查看连接池监控，比较请求线程数、连接数、SQL 平均耗时和等待时间。

下一步：[IoC 与依赖注入](./ioc.md)、[AOP 与代理](./aop.md)、[Spring Boot 配置](./boot.md) 或 [数据库与持久层总览](../data/persistence.md)。
