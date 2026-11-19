# JDBC、MyBatis 与 ORM：Java 服务如何访问持久数据

持久层负责把业务需要的数据读写到数据库，并把数据库结果转换成 Java 对象。读前端接口时，它回答：“这个响应里的数据从哪张表或哪个数据源来？筛选、排序、分页和事务在哪里发生？”

## 一条查询链路

~~~text
HTTP 请求
  -> Controller 绑定查询 DTO
  -> Service 校验业务条件、确定数据范围
  -> Mapper / Repository 描述数据访问
  -> JDBC 驱动执行 SQL
  -> 数据库返回行
  -> MyBatis 映射成 Entity / DTO
  -> Service 组装 VO
  -> Controller 返回 JSON
~~~

每一层解决不同问题：Controller 把 HTTP 转成应用调用；DTO 定义输入及校验；Service 执行业务规则；Mapper 描述数据访问；Entity 表示持久化结构；VO 组织当前接口需要返回的内容。这和前端的 route handler、表单类型、业务 service、ORM model、response type 有相似之处，但后端这些对象还受事务、安全和数据库映射影响。

## 本章与 Spring 数据访问章节的分工

本章先建立 JDBC、Mapper、ORM 和对象映射的概念；事务传播、隔离级别、连接池、游标分页、N+1 和乐观锁的详细实践集中在 [Spring 数据访问与事务](../spring/data-access.md)。MySQL 的表设计、索引、锁等待和 Redis 缓存细节见 [MySQL 与 Redis](./storage.md)。这样可以避免同一机制在不同页面重复堆叠，也便于你按问题回到对应层。

## 从一个前端列表请求追踪数据

```text
GET /api/orders?status=PAID&pageNum=1&pageSize=20
  -> Controller：绑定 status/pageNum/pageSize
  -> Query DTO：校验状态枚举和分页上下限
  -> Service：补充当前用户/租户可见范围
  -> Mapper：构造 WHERE、ORDER BY、LIMIT
  -> MySQL：选择索引、读取行和 total
  -> Entity/Row：数据库列映射成 Java 类型
  -> VO：只保留前端需要的字段
  -> Result<Page<OrderVO>>：序列化为 JSON
```

调试时分开记录每一层：Network 请求参数、Controller 绑定后的值、Service 加上的权限条件、Mapper SQL 模板和参数、数据库实际行、最终 VO。只看页面显示为空，无法判断是前端参数、权限筛选、逻辑删除、SQL 条件还是映射失败。

## 持久层常见组件和责任

| 组件 | 主要责任 | 不应该承担的责任 |
|---|---|---|
| DataSource | 提供 JDBC Connection，通常由连接池包装 | 决定某个用户是否有权读取业务行 |
| JDBC Driver | 把 JDBC API 翻译成数据库协议 | 业务分页和 VO 组装 |
| Mapper/DAO | 暴露查询/更新方法，执行 SQL 并映射结果 | 接收任意前端字段并拼接 SQL |
| Entity/PO | 表结构对应的持久化模型 | 不加筛选地作为每个 API 的响应 DTO |
| Service | 编排多个数据访问和业务规则，划定事务边界 | 保存当前请求的共享实例状态 |
| DTO | 描述输入/命令/查询参数 | 暴露内部数据库字段作为可写入口 |
| VO/Response | 描述接口输出契约 | 直接依赖 ORM 懒加载代理 |

DTO/Entity/VO 不一定必须是三个不同的类；小项目可以复用，但要知道复用会把 API 契约与数据库结构绑在一起。出现字段泄露、联表结构复杂或接口版本兼容要求时，应拆开模型。

## JDBC：数据库访问的底层接口

JDBC（Java Database Connectivity）解决 Java 程序如何通过统一接口连接不同关系型数据库的问题。数据库驱动负责将 JDBC 操作翻译为 MySQL、Oracle 等数据库的网络协议。

直接使用 JDBC 时要处理连接、预编译语句、结果集和资源关闭。连接池复用物理连接，避免每次 HTTP 请求都重新创建连接。

JDBC API 的完整执行过程、批处理、事务、连接池和异常排查见 [JDBC 专题](./jdbc.md)。本页继续介绍框架怎样在 JDBC 之上减少映射样板。

~~~java
// 通过 DataSource 从连接池申请连接，避免每次查询新建物理连接。
try (Connection connection = dataSource.getConnection();
     // 使用预编译 SQL，把用户输入作为参数绑定，避免拼接 SQL。
     PreparedStatement statement =
         connection.prepareStatement("select id, name from sys_user where id = ?")) {
    // 将用户 ID 绑定到第一个参数；JDBC 参数位置从 1 开始。
    statement.setLong(1, userId);

    // 执行查询并取得数据库结果集。
    try (ResultSet resultSet = statement.executeQuery()) {
        // 逐行读取查询结果。
        while (resultSet.next()) {
            // 按列名读取字段，减少列顺序变化导致的错误。
            String name = resultSet.getString("name");
            // 真实业务通常会把字段映射成 DTO 或 Entity。
            System.out.println(name);
        }
    }
    // try-with-resources 会自动关闭结果集、语句和连接并归还连接池。
}
~~~

**解决的问题：** 提供数据库无关的 Java 访问约定，并安全处理 SQL 参数。

**取舍：** JDBC 灵活且 SQL 行为明确，但映射和资源管理较繁琐。MyBatis 等框架在 JDBC 上减少重复代码，底层仍使用 JDBC。

**安全重点：** 值用问号占位符，不要拼进 SQL 文本。表名、列名不能像普通值一样绑定，动态标识符必须通过白名单转换。

## MyBatis：保留 SQL，同时减少映射样板

MyBatis 解决“SQL 需要精确控制，但手工将每行 ResultSet 转为 Java 对象很重复”的问题。Mapper 接口声明方法，XML 或注解提供 SQL，框架负责执行和结果映射。

Java Mapper 示例：

~~~java
// Mapper 描述数据访问契约，具体 SQL 放在对应的 XML 中。
public interface UserMapper {
    // 查询指定状态的用户；参数会传递给 XML SQL。
    List<UserEntity> selectActiveUsers(@Param("status") Integer status);
}
~~~

对应 XML：

~~~xml
<!-- namespace 必须与 Mapper 接口全限定名一致。 -->
<mapper namespace="com.example.user.UserMapper">
    <!-- id 对应 Mapper 方法名，resultType 指定结果映射对象。 -->
    <select id="selectActiveUsers" resultType="com.example.user.UserEntity">
        <!-- #{} 进行参数绑定，最终使用 PreparedStatement 参数。 -->
        SELECT id, user_name AS userName, status
        FROM sys_user
        WHERE status = #{status}
        ORDER BY id
    </select>
</mapper>
~~~

**解决的问题：** SQL 可见、可调优，同时把参数绑定、结果映射和资源关闭交给框架。

**取舍：** 开发者仍需维护 SQL、字段映射、分页和数据库差异。动态 SQL 要测试，避免漏掉过滤条件后查出过多数据。

MyBatis 的 #{} 是安全参数绑定；美元符号加花括号表示文本替换，可能造成 SQL 注入。文本替换只应接收经过严格白名单验证的标识符，不能直接使用用户输入。

## MyBatis-Plus：简化常见 CRUD

MyBatis-Plus 在 MyBatis 上增加通用 Mapper、分页和条件构造器，解决简单增删改查需要重复写 SQL 的问题。

~~~java
// 继承 BaseMapper 后可使用按实体映射生成的常见 CRUD 方法。
public interface UserMapper extends BaseMapper<UserEntity> {
    // 复杂查询仍可增加自定义方法和 SQL。
}
~~~

条件构造器示意：

~~~java
// 创建一个针对 UserEntity 的类型安全查询条件。
LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<>();
// 只查启用状态的用户。
query.eq(UserEntity::getStatus, 1);
// 按主键排序，使翻页顺序稳定。
query.orderByAsc(UserEntity::getId);
// 执行列表查询；大结果集应使用项目配置的分页方式。
List<UserEntity> users = userMapper.selectList(query);
~~~

**解决的问题：** 常见 CRUD 不必重复书写相同 SQL 和映射代码。

**取舍：** 复杂 join、聚合、方言特性或性能敏感查询仍适合显式 SQL。不要为了“全用 Wrapper”把业务查询藏成难读的链式调用。

本知识库对应的仪表盘 DAO 继承 MyBatis-Plus BaseMapper；有些 CRUD 来自基类，自定义查询还可能在 XML 或注解中。

## Mapper 参数绑定：`#{}` 和 `${}` 差别很重要

```xml
<!-- #{} 会生成 JDBC 预编译参数，适合用户传入的值。 -->
<select id="findOrders" resultType="com.example.OrderEntity">
    SELECT id, status, created_at
    FROM orders
    WHERE customer_id = #{customerId}
      AND status = #{status}
    ORDER BY created_at DESC, id DESC
</select>
```

- `#{value}`：参数作为值绑定，最终通常由 `PreparedStatement` 发送；这是默认选择。
- `${value}`：将字符串直接替换进 SQL 文本，常用于列名/表名等不能参数化的位置，存在注入风险。
- 列名、排序方向和表名不能通过 `#{}` 绑定。需要动态排序时，将前端枚举映射成服务端固定白名单字段，不能把原始 Query 字符串直接传入 `${}`。

```java
// 安全示意：用固定映射选择排序列，绝不把前端传来的字符串直接嵌入 SQL。
Map<String, String> allowedSortColumns = Map.of(
        "createdAt", "created_at",
        "amount", "amount",
        "id", "id");
String column = allowedSortColumns.getOrDefault(request.getSortBy(), "created_at");
boolean ascending = "asc".equalsIgnoreCase(request.getSortOrder());

// 生产代码最好继续让 QueryWrapper/自定义 Mapper 构造排序，而不是拼接未经白名单的文本。
```

LIKE 搜索也要按产品需求转义 `%` 和 `_`；否则用户输入 `%` 可能变成全表模糊查询，造成性能问题，即使不存在 SQL 注入。

## 动态 SQL：筛选条件必须有明确语义

Mapper XML 中的 `<if>`、`<where>`、`<foreach>` 解决“可选筛选、批量 ID 查询和动态更新”的问题，但每个动态条件都改变查询结果和索引选择。不要让空值自动等同于“查全部”，除非 API 明确允许这样做。

```xml
<!-- 动态条件示意：只有 DTO 中明确提供了值时才添加筛选条件。 -->
<select id="searchOrders" resultType="com.example.OrderEntity">
    SELECT id, customer_id, status, created_at
    FROM orders
    <where>
        <if test="customerId != null">
            AND customer_id = #{customerId}
        </if>
        <if test="status != null and status != ''">
            AND status = #{status}
        </if>
        <if test="createdFrom != null">
            AND created_at &gt;= #{createdFrom}
        </if>
        <if test="createdTo != null">
            AND created_at &lt; #{createdTo}
        </if>
        <!-- 权限/租户条件应由可信服务端上下文提供，不能只依赖前端传参。 -->
        AND tenant_id = #{tenantId}
    </where>
    <!-- 加唯一字段作为第二排序键，保证相同时间的翻页边界稳定。 -->
    ORDER BY created_at DESC, id DESC
</select>
```

前端需要向后端确认“筛选字段缺省、空字符串、null、空数组”的区别。比如 `status` 未传可能表示不筛选，而 `status=[]` 可能表示没有任何状态；服务端不能总把它们折叠成同一含义。

## Entity 映射与数据库类型

ORM/Mapper 要把数据库列映射成 Java 字段。字段名可能通过驼峰映射、别名、`resultMap`、TypeHandler 或注解匹配；仅仅 Java 字段存在，不代表查询结果一定会填入。

常见类型边界：

| 数据库类型 | Java 常见类型 | 容易踩的坑 |
|---|---|---|
| `BIGINT` | `Long` | JSON 传给 JS Number 可能超过安全整数范围；大 ID 考虑序列化成字符串 |
| `DECIMAL(p,s)` | `BigDecimal` | 金额不要用 `float/double` 做精确计算 |
| `TINYINT/INT` 状态 | `Integer` 或 enum 转换 | 状态码必须维护前后端兼容映射 |
| `DATETIME/TIMESTAMP` | `LocalDateTime`/`Instant`/`Date` | 时区、数据库会话和 JSON 格式要统一 |
| `JSON`/扩展字段 | JSON 字符串/对象 + TypeHandler | 数据库支持与序列化器版本影响读写 |
| nullable 字段 | Java 包装类型 | `int`/`boolean` 无法表示数据库 NULL |

TypeHandler 可能影响 JSON 列、枚举、加密字段或特殊数据库类型。排查“数据库里有值但接口返回 null”时，检查 SQL 是否选了该列、别名是否匹配、resultMap 是否配置、TypeHandler 是否正确、VO 转换有没有遗漏。

## 查询结果映射：`resultType` 和 `resultMap`

- `resultType` 适合列名和属性名可直接映射，或 SQL 已通过别名规范字段名的简单结果。
- `resultMap` 适合明确配置主键、嵌套对象、一对多集合和复杂列转换。
- 联表列表优先返回扁平 DTO/VO；直接映射成含多个集合的复杂对象可能产生重复行、分页 total 错误或额外查询。
- 分页 SQL 中一对多 JOIN 会放大行数；需要先分页主表再批量查关联，或使用经验证的两阶段查询。

```xml
<!-- 复杂映射示意：列名到属性的对应关系明确可审查。 -->
<resultMap id="OrderViewMap" type="com.example.OrderView">
    <id column="order_id" property="id"/>
    <result column="order_status" property="status"/>
    <result column="customer_name" property="customerName"/>
</resultMap>
```

## ORM 懒加载、N+1 和对象关系边界

ORM 的关系字段可能延迟查询。循环访问每行的关联对象时，容易形成“一条主查询 + N 条关联查询”。它会让一个网络请求看似只调一次 API，服务端却发出大量 SQL。

避免 N+1 的常见方法：

1. 使用显式 JOIN、批量 `IN` 查询或投影 DTO 一次拿齐展示数据。
2. 查看 SQL 数量和 trace 中的数据库 span，不只检查 Java 方法调用次数。
3. 分页先确定主表记录，再批量补关联数据，避免一对多 JOIN 破坏分页。
4. Entity 不直接序列化，避免 getter 触发懒加载、循环引用或意外字段暴露。

## 事务边界与 ORM 代理

事务通常放在 Service 的一个完整业务动作上，让订单、库存和支付状态等相关写操作可以统一提交/回滚。Controller 一般不管理底层连接，Mapper 也不应该在每个 SQL 外单独开启一个业务事务。

```java
@Service
public class OrderApplicationService {

    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;

    public OrderApplicationService(OrderMapper orderMapper, InventoryMapper inventoryMapper) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
    }

    // 通过 Spring 代理从外部调用时，订单与库存更新可以共享一个本地事务。
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(CreateOrderCommand command) {
        // 先扣库存，SQL 需带 available >= quantity 条件并检查影响行数。
        int updated = inventoryMapper.decreaseIfEnough(command.getSku(), command.getQuantity());
        if (updated != 1) {
            throw new IllegalStateException("库存不足或版本冲突");
        }

        // 库存成功后保存订单；任一步异常应按事务规则整体回滚。
        orderMapper.insert(command.toEntity());
    }
}
```

事务代理、自调用、异常回滚规则、传播级别、隔离级别和多数据源边界详见 [Spring 数据访问与事务](../spring/data-access.md)。本地数据库事务不会自动回滚 Redis、远程 HTTP、消息队列或其他数据库的操作。

## 逻辑删除、乐观锁与唯一约束

- **逻辑删除**：保留记录并标记删除状态。所有查询、唯一键和自定义 SQL 都要考虑删除字段；它不是数据脱敏。
- **乐观锁**：用 version 条件更新，并检查影响行数，适合冲突概率低的编辑场景。
- **唯一约束**：由数据库保证并发下不重复；应用层“先查再插”不能替代唯一索引。
- **幂等键**：支付回调、重复提交和重试请求用稳定业务键防止重复产生结果。

前端两个标签页同时编辑时，后端返回 `409 Conflict` 比无声覆盖更安全；响应应携带可识别的业务错误码，前端提示“数据已更新，请刷新后重试”。

## 动态数据源与跨库访问

多数据源路由通常由 Spring AOP、ThreadLocal 上下文或框架代理决定当前 Connection 来自哪个 DataSource。检查时要确认：注解放在类还是方法上、事务代理和数据源路由谁先执行、线程切换后路由上下文是否清理、当前方法实际用的是哪个事务管理器。

物理外部库和平台元数据库往往是不同数据源。一次操作若先对外部库执行 DDL，再更新平台元数据，普通本地 `@Transactional` 不能把两端合成一个原子事务。需要保存操作阶段、检测部分成功，设计重试/补偿或人工修复，而不能把“方法抛异常”理解为所有库都回滚。

## 分库分表：ShardingSphere 路由与查询代价

分库分表是数据规模和单库能力达到边界后的扩展手段，会把原本一条 SQL 的成本转成路由、跨分片聚合、分页合并、全局唯一 ID 和迁移治理成本。先验证索引、SQL、连接池、读写分离和归档，确实无法满足容量/吞吐需求后再考虑拆分。

```text
业务 SQL
  -> SQL 解析与分片条件提取
  -> 按 sharding column + algorithm 计算目标库/表
  -> 仅访问目标分片，或在缺少路由条件时访问多个分片
  -> 合并结果/排序/分页/聚合
```

| 概念 | 解决的问题 | 设计重点 |
|---|---|---|
| 分库键/分表键 | 决定数据放在哪个库/表 | 常用查询必须能携带该字段，且值分布不能严重倾斜 |
| `actualDataNodes` | 描述逻辑表对应哪些真实数据节点 | 规则必须与实际库表命名和已部署数据一致 |
| 分片算法 | 由分片键计算节点 | 范围查询、空值、历史数据和算法版本要明确 |
| 广播表 | 在多个分片复制小型字典表 | 更新需要传播到所有节点并处理失败 |
| 全局 ID | 避免各分片主键冲突 | 雪花 ID/号段等生成器要考虑时钟、机器号和迁移 |
| 跨分片查询 | 合并多个库的结果 | 全局排序、分页、COUNT、JOIN 和聚合可能很贵或受限 |

缺少分片键的查询可能广播到多个分片；复杂 JOIN、事务、排序和深分页也可能触发跨分片合并。是否发生路由应查看 ShardingSphere SQL 日志、执行计划/追踪和真实配置，不能从 Mapper 方法名推断。

### datacenter-cloud 中的 ShardingSphere 阅读路径

当前源码提供了可读的配置装配示例，但**依赖和工厂类存在不代表目标环境一定启用了分片**。要结合当前 Profile、`sharding.enabled` 和部署配置验证实际运行行为；不要把连接地址、用户名或密码复制到文档、日志或截图。

1. `gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/config/sharding/ShardingConfig.java`：`@ConfigurationProperties(prefix = "sharding")` 定义数据源组、实际数据节点、库/表策略、算法和主键生成器结构。
2. 同目录的 `ShardingDataSourceFactory.java`：把数据源组创建为 Hikari DataSource map，再构建 `ShardingRuleConfiguration`，调用 `ShardingSphereDataSourceFactory` 生成组合数据源。
3. `gykj-modules/gykj-cloud` 下也存在对应的配置和工厂实现；比较两个模块的规则、数据源生命周期和调用边界，不要假设实现完全相同。
4. `CollectDatasourceSchemaServiceImpl` 类级 `@DS("cloud")` 用于选择平台数据源；它和 ShardingSphere 根据 SQL 分片键路由到具体物理节点是不同层次。
5. 追一条实际查询，确认它包含的分片键、生成的目标节点、SQL 数量、分页/count 合并以及结果总数。

前端页面最容易感知的是：列表翻页变慢、统计 total 不匹配、单个租户特别慢、写入后读取到不同节点状态。排查时先确认当前请求是否命中 ShardingSphere 路由，再检查分片键、数据倾斜和跨分片聚合；不要只调整前端分页参数。

## 在 datacenter-cloud 中验证这些概念

以当前 checkout 静态源码为例，可按下列顺序阅读；运行配置和外部数据源是否可用需要在目标环境单独验证：

1. `gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/DashboardApplication.java`：查看 `@MapperScan` 与 `MybatisPlusInterceptor`/`PaginationInnerInterceptor` 注册。
2. `.../module/manage/controller/DashboardPageController.java` → `.../module/manage/service/impl/DashboardPageServiceImpl.java` → `.../module/basic/dao/DashboardPageDao.java`：沿着列表请求看 DTO、Service 条件和 `BaseMapper` CRUD。
3. `gykj-modules/gykj-cloud/src/main/java/com/gykj/cloud/mapper/CollectDatasourceTableSchemaMapper.java` 和同名 XML：看 `IPage` 分页、`#{}` 参数、动态 keyword、resultMap、统计子查询和排序。
4. `.../service/impl/CollectDatasourceSchemaServiceImpl.java`：看 `@DS("cloud")`、分页结果转换、Mapper 调用和 `TransactionTemplate`。
5. [ShardingSphere 分片配置和工厂](#分库分表shardingsphere-路由与查询代价)：区分平台动态数据源路由和 SQL 分片路由，并确认 Profile 中是否启用。
6. 同服务的外部结构同步/DDL 流程：外部数据库结构变更和本地元数据写入是分开的操作；检查部分成功处理，不能假设单一事务覆盖两边。
7. Redis 可从 `gykj-common/gykj-common-redis/.../RedisConfig.java` 和 `RedisService.java` 阅读：确认 String key 序列化、value/hash 序列化与 TTL 操作。项目有 `@EnableCaching` 不等于每个 Redis 使用点都走 Spring Cache 注解。

当前项目多模块里 MyBatis-Plus 分页和 PageHelper 都能找到使用场景。读某个接口时以它所属模块的插件配置和调用写法为准，不要把一种分页插件的行为套到全项目。

## 前端开发者怎样追查数据问题

1. 从页面 API wrapper 记录请求参数和预期响应字段。
2. 找 DTO，确认参数绑定、类型、默认值和校验。
3. 找 Controller 调用的 Service，检查筛选条件和权限范围。
4. 找 Mapper 方法、BaseMapper 调用或 XML SQL。
5. 检查实体映射、JSON type handler、逻辑删除和动态数据源。
6. 比较 SQL 参数、数据库行、最终 VO 与浏览器 Network 响应。
7. SQL 命中但响应缺字段时，继续查 DTO/VO 转换和序列化。
8. 保存后仍显示旧值时，确认事务是否提交、读写是否命中不同数据源、缓存 key/TTL/删除是否对应当前租户。
9. 出现 409 时，核对前端是否提交版本号、后端影响行数判断和刷新后重试体验。

## 常见误区

- 把 Entity 当作前端响应类型，数据库改动意外改变 API。
- 字符串拼接筛选值，留下 SQL 注入风险。
- 分页没有稳定排序，翻页时重复或遗漏记录。
- 只加前端 loading，却不查看 SQL、索引或网络往返。
- 认为事务可以覆盖所有下游服务。
- 认为 BaseMapper 自动解决查询设计和性能问题。
- 认为 `@Transactional` 会跨数据库、Redis、Feign 和消息队列一起回滚。
- 认为 `@EnableCaching` 表示业务类都自动缓存；仍要找到具体 cache 使用点及 key/TTL。
- 认为项目统一使用某一个分页插件；应按模块和 Mapper 查询逐条确认。

## 练习

选一个列表接口，写下请求 DTO、Service 查询条件、Mapper/SQL、Entity 和响应 VO。改变一个筛选参数，在浏览器 Network、服务日志和 SQL 日志中验证条件经过了哪些层。

进一步练习：分别追踪一个 MyBatis-Plus `IPage` 查询和一个 PageHelper 查询，记录它们的插件配置、总数查询、排序和响应 `rows/total`；再找一个跨数据源写入，画出哪些步骤可以回滚、哪些需要补偿。

继续学习：[数据层与持久层入口](./index.md)、[MySQL 与 Redis](./storage.md)、[Spring 数据访问与事务](../spring/data-access.md)。
