# 数据层与持久层：从请求数据到数据库记录

这个区域回答两个连在一起的问题：**Java 服务怎样安全地访问和映射数据，以及 MySQL/Redis 怎样保存、查询和保护这些数据**。前端开发者可以沿着一个列表或保存请求，从 Axios 的参数一路跟到 DTO、Service、Mapper、SQL、事务、数据库，再回到响应 VO。

## 两条互补的学习线

| 学习线 | 关注点 | 入口 |
|---|---|---|
| JDBC 底层 | Connection、PreparedStatement、ResultSet、事务、批处理、连接池和异常 | [JDBC 专题](./jdbc.md) |
| 持久层框架 | JdbcTemplate、MyBatis、ORM、参数绑定、对象映射、分页和分库分表 | [JDBC、MyBatis 与 ORM](./persistence.md) |
| 数据库类型 | 关系型、缓存型、时序型、文档型、搜索和列式分析模型及选型 | [数据库类型全景](./database-types.md) |
| SQL 查询与写入 | WHERE、JOIN、聚合、分页、INSERT、UPDATE、DELETE 和 UPSERT | [SQL 查询与写入实战](./sql-practice.md) |
| 数据库与缓存基础 | MySQL 表结构、约束、索引、事务锁、Redis 数据结构和缓存一致性 | [MySQL 与 Redis 基础](./storage.md) |
| MySQL 生产机制 | InnoDB 索引/MVCC/锁、主从复制、Schema 发布、备份恢复和安全 | [MySQL 生产机制与运维](./mysql-operations.md) |
| Redis 生产运维 | Sentinel/Cluster、大键热键、Pipeline、Stream、持久化和故障恢复 | [Redis 运维与消息语义](./redis-operations.md) |
| Spring 实战 | Spring 管理数据源、Mapper、声明式事务、动态数据源和数据访问测试 | [Spring 数据访问与事务](../spring/data-access.md) |

建议按“**先建立整体地图，再向底层下钻，最后回到项目验证**”学习：先读本页和[数据库类型全景](./database-types.md)，建立数据模型与选型概念；再读[持久层](./persistence.md)，理解 Controller/Service/Mapper 和 SQL 映射的调用关系；接着用[JDBC](./jdbc.md) 下钻连接、参数绑定、结果读取和本地事务；然后练 SQL、MySQL、Redis，最后回到 Spring 数据访问与项目代码验证。

如果你只想先了解数据库类型，可以从[数据库类型全景](./database-types.md)开始；如果你正在追一段 JDBC 代码，也可以先看[JDBC 底层](./jdbc.md)，再回到[持久层](./persistence.md)补全框架层。它们是按当前问题进入的捷径，不改变上面的推荐主线。

## 从前端问题定位到数据层

| 浏览器现象 | 后端要核对的内容 |
|---|---|
| 列表少数据 | Query DTO、Service 筛选条件、租户/逻辑删除条件、SQL 参数 |
| 排序/分页时重复行 | `ORDER BY` 是否稳定、排序字段是否唯一、是否适合游标分页 |
| 保存后页面仍是旧值 | 数据库是否提交、读写分离延迟、Redis 缓存是否失效 |
| 两个页面同时编辑互相覆盖 | 乐观锁版本号、更新影响行数、冲突响应是否为 409 |
| 页面详情偶发很慢 | SQL 执行计划、索引、N+1、连接池等待和远程调用 |
| 外部数据源表结构没同步 | 物理 DDL 与本地元数据事务是否分属不同数据库 |

## 推荐学习路径

1. [数据层入口与数据库类型](./database-types.md)：先区分关系型、缓存型、时序型和分析型系统各自解决的问题。
2. [持久层：JDBC、MyBatis 与 ORM](./persistence.md)：建立从 Service 到 Mapper、SQL 和结果映射的完整调用图。
3. [JDBC 底层](./jdbc.md)：认识 Java 如何取连接、绑定参数、执行 SQL、映射结果及控制事务。
4. [SQL 查询与写入实战](./sql-practice.md)：练习 WHERE、JOIN、聚合、分页和常见写入。
5. [MySQL 与 Redis 基础](./storage.md)：学习表约束、索引、事务锁、缓存读写和 Redis 可用性。
6. [MySQL 生产机制与运维](./mysql-operations.md)：理解 InnoDB 并发、读写分离、Schema 发布与恢复。
7. [Redis 运维与消息语义](./redis-operations.md)：理解高可用、内存、大键、Stream 和消息确认边界。
8. [Spring 数据访问与事务](../spring/data-access.md)：掌握 Spring 事务代理、传播/隔离、分页、连接池和并发更新。
9. [项目地图](../project-map.md)：沿当前数据中台的 Controller、Service、Mapper 和数据源实现真实代码。

## 检查自己是否掌握

- 能把一个筛选字段从前端请求追到最终 SQL 条件。
- 能说明 Entity、DTO、VO 的差别，以及为什么不应默认把 Entity 直接返回前端。
- 能指出数据库唯一约束如何防止并发重复提交，事务为什么不能替代幂等。
- 能从 `EXPLAIN`、慢查询日志和连接池指标解释列表变慢的原因。
- 能读懂常见 JOIN/聚合 SQL，并解释 `WHERE`、`HAVING` 和一对多分页的区别。
- 能说清缓存 key 的用户/租户维度、TTL、失效时机和 Redis 故障时的行为。
- 能解释读写分离延迟、Redis 故障转移数据窗口和分库分表的跨分片代价。
- 能识别物理 DDL、元数据库和外部服务不可能被一个普通本地事务整体回滚的场景。
