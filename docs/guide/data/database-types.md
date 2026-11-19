# 数据库类型全景：关系型、缓存型与时序型

“数据库”不是一种固定的数据结构。MySQL、Redis、InfluxDB、Prometheus、MongoDB 和 Elasticsearch 使用不同的数据模型、查询方式、事务范围与恢复语义。选型时先问：**系统保存的事实是什么、数据如何写入和查询、丢失后能否重建、允许多旧的结果、单条数据会保留多久**，再比较产品。

## 一张选型地图

| 类型 | 主要数据模型 | 擅长的问题 | 不应默认承担的角色 |
|---|---|---|---|
| 关系型数据库 | 表、行、列、主外键和 SQL | 订单、账户、配置等结构明确且需要约束、事务和关联查询的数据 | 无限制高频指标写入或所有搜索/分析负载 |
| 缓存/键值数据库 | key-value、Hash、List、Set、ZSet 等内存数据结构 | 热点读取、短期状态、计数、限流、缓存和轻量队列 | 默认作为唯一业务事实来源；除非已单独设计持久性、备份和恢复 |
| 时序数据库/时序分析引擎 | 按时间组织的采样点或带时间列的事件表 | 设备遥测、服务指标、IoT 采样、趋势和时间窗口聚合 | 通用订单/用户关系数据的唯一 OLTP 主库 |
| 文档数据库 | JSON/BSON 等文档 | 字段变化较快、按文档整体读取的聚合对象 | 需要复杂多表约束但仍未设计一致性边界的关系业务 |
| 搜索引擎 | 倒排索引和文档 | 全文检索、相关性、分面筛选 | 事务主库；索引通常从主数据异步构建，可能滞后 |
| 列式分析数据库/数仓 | 列式存储、分区与批量分析 | 大范围扫描、聚合、报表和离线分析 | 高频单行更新与低延迟事务写入 |
| 图数据库 | 节点、边和路径 | 多跳关系、关系推荐、路径分析 | 没有图关系查询需求的普通 CRUD |

这些是数据模型/工作负载的分类，不是绝对产品边界。同一产品可能覆盖多个场景，但使用的存储引擎、部署形态和一致性配置会改变实际行为。

## 关系型数据库：用结构和约束守住业务事实

### 它解决什么问题

关系型数据库把数据组织成有 schema 的表；主键唯一标识一行，外键表达关联，唯一约束和检查约束限制非法状态，事务把同一数据库内的一组更改作为本地提交/回滚单元。SQL 提供筛选、关联、分组、排序和分页能力。

典型场景：订单、账户、权限、配置、支付单、库存台账等需要可靠写入、业务约束或多条件查询的数据。常见架构会把关系数据库作为权威数据源（source of truth），缓存和搜索索引从它派生。

### 从表设计理解一致性

```sql
-- 订单主表保存一次交易的业务事实；金额使用定点数避免二进制浮点误差。
CREATE TABLE orders (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '订单唯一标识',
    tenant_id BIGINT NOT NULL COMMENT '租户范围，查询和唯一性都要考虑隔离',
    order_no VARCHAR(64) NOT NULL COMMENT '业务订单号',
    status VARCHAR(24) NOT NULL COMMENT '由状态机约束可接受的状态流转',
    amount DECIMAL(18, 2) NOT NULL COMMENT '金额使用定点数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
);

-- 数据库唯一约束可以在多个应用实例并发插入时阻止重复订单号。
CREATE UNIQUE INDEX uk_orders_tenant_order_no
    ON orders (tenant_id, order_no);

-- 支付记录引用订单；外键是否在生产启用需结合项目架构和迁移策略评估。
CREATE TABLE payments (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '支付记录主键',
    tenant_id BIGINT NOT NULL COMMENT '租户范围',
    order_id BIGINT NOT NULL COMMENT '关联订单 ID',
    channel_trade_no VARCHAR(100) NULL COMMENT '渠道交易号，待支付时可能为空',
    status VARCHAR(24) NOT NULL COMMENT '支付状态',
    amount DECIMAL(18, 2) NOT NULL COMMENT '本次支付金额'
);
```

唯一约束比“应用代码先查有没有再插入”可靠：两个请求可以同时通过查询，但只有数据库约束能在并发写入的最后边界阻止重复。事务同样只覆盖其数据库/事务管理器范围；跨服务通知需要 Outbox、幂等和补偿设计。

### 什么时候用、什么时候小心

- **使用：** 业务写入是事实、需要唯一性/外键/事务，或页面要灵活关联筛选。
- **注意：** 索引会加速部分查询但增加写放大和磁盘；以 `EXPLAIN` 和真实负载验证。
- **注意：** Offset 深分页、宽表大范围扫描和 N+1 可能很慢；考虑稳定游标、批量加载或分析型副本。
- **注意：** 主从复制与缓存会带来读后写旧值；设计保存后读取策略。
- **参考：** [SQL 查询与写入实战](./sql-practice.md)、[MySQL/Redis 基础](./storage.md)、[MySQL 生产机制](./mysql-operations.md)。

## 缓存型数据库：用较快的访问路径减少重复计算

### 缓存与键值模型

Redis 等缓存数据库通常通过 key 直接读写对象或数据结构。它适合热点数据、短 TTL 状态、计数和受控并发协调；读写速度很重要，但还要明确内存上限、淘汰策略、持久化、复制、故障切换和重建来源。

缓存常见的 Cache Aside 流程是：先读 Redis，未命中时查关系型主库，再写入带 TTL 的缓存；更新时先提交主库，再删除缓存。并发下仍可能发生旧值回填，所以要评估 TTL、版本号、延迟双删/消息失效等复杂度。缓存 key 必须覆盖租户、用户权限、语言、数据版本等会改变返回结果的维度。

```text
前端请求订单详情
  -> GET order:{tenantId}:{orderId}
      命中：返回缓存的 DTO
      未命中：查询关系库 -> 组装 DTO -> 写入有限 TTL -> 返回

修改订单
  -> 事务提交关系库
  -> 删除对应缓存 key 或发出失效事件
```

### 缓存不是因为“在内存里”就天然可靠

若 Redis 只是可重建缓存，丢失后可以从权威库回填，但故障期间要限流回源，避免数据库雪崩。若 Redis 保存会话、验证码、任务状态或 Stream 事件，就要明确数据丢失的用户影响、AOF/RDB 策略、复制窗口和恢复流程。不能一边按可丢缓存部署，一边让业务依赖 Redis 中唯一的一份事实。

缓存选择时检查：

- key 的命名和命中维度是否正确，是否包含敏感信息。
- TTL 是否有上限，热点过期是否会同时击穿数据库。
- 淘汰策略是否会淘汰业务仍依赖的数据。
- 大 key/热 key、慢命令、连接池耗尽如何监控。
- Redis 不可用时哪些接口可以有界回源，哪些敏感操作必须失败关闭。

具体 Redis 数据结构、缓存一致性和故障降级见 [MySQL 与 Redis 基础](./storage.md) 与 [Redis 运维](./redis-operations.md)。

## 时序数据库：把时间作为查询和保留策略的核心维度

### 时序数据长什么样

时序数据通常是一串带时间戳的观测值：设备/主机/传感器身份、指标名、标签和某时刻的值。查询常围绕最近 N 分钟、每分钟均值、峰值、变化率、缺失区间和历史趋势展开。

```text
time=2026-11-18T10:00:00Z
series=temperature
tags={site="east", device="sensor-17"}
value=23.4
```

一个很适合时序存储的页面场景是监控图表：前端选设备和时间范围；API 用设备标签筛选、按窗口聚合并返回点列。单个订单的支付状态则应由事务型数据库保存，不能因为也有 `updated_at` 就建成指标点。

### 时序数据库解决的压力

- **高频追加：** 传感器或系统指标不断产生新点，写入模式通常以 append 为主。
- **按时间裁剪：** 查询或删除最近一小时、保留 30 天等时间范围。
- **窗口聚合：** 把秒级点聚合成分钟/小时平均值、最大值、计数或分位数。
- **保留和降采样：** 保留近期原始精度，较老数据保留更粗粒度摘要。
- **压缩与分区：** 时间连续的数据便于分区、压缩和顺序读取。

时序数据库并不会自动让所有写入更快。标签基数失控、大量随机历史更新、无时间范围全表查询和无节制保留都可能耗尽内存、索引或磁盘。

### 标签基数是建模关键

**Series（时间序列）**通常由测量名和标签组合唯一确定。例如 `cpu_usage{host="a", region="east"}` 是一条序列；每增加一个新的标签组合，就可能产生另一条序列。适合做标签的通常是有限、稳定、可筛选的维度；用户 ID、请求 ID、订单号、每次请求生成的 UUID 等高基数字段，不应不加评估地作为 Prometheus label 或 Influx tag，否则活跃序列和索引会大量增长。

| 字段 | 常见处理方式 | 原因 |
|---|---|---|
| `region`、`device_type`、`service` | 稳定标签 | 取值有限，通常用于筛选和分组 |
| 传感器设备 ID | 依规模评估 | 固定设备目录可控；设备数量巨大或动态无限时要估算序列数 |
| `request_id`、traceId | 放日志/Trace 系统 | 几乎每次请求唯一，指标标签基数会失控 |
| 温度、时延、CPU 值 | 数值字段 | 值需要聚合，不适合作为标签索引 |
| `time` | 时间戳/分区维度 | 决定时间范围查询和保留策略 |

### 建表与查询示例

下面用 TimescaleDB（基于 PostgreSQL）演示；`create_hypertable` 需要安装 TimescaleDB 扩展。其他产品的数据模型和 DDL 不同，不能直接复制使用。

```sql
-- 保存传感器的温度观测；时间与设备组成复合主键，表示同一设备同一时刻最多一条采样。
CREATE TABLE sensor_reading (
    time TIMESTAMPTZ NOT NULL,
    device_id TEXT NOT NULL,
    site_id TEXT NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (device_id, time)
);

-- PostgreSQL 使用 COMMENT ON COLUMN 为列添加说明。
COMMENT ON COLUMN sensor_reading.time IS '带时区时间戳，建议 API 与存储统一 UTC';
COMMENT ON COLUMN sensor_reading.device_id IS '设备标识，先评估设备总数和索引基数';
COMMENT ON COLUMN sensor_reading.site_id IS '站点维度，值集合通常有限';
COMMENT ON COLUMN sensor_reading.temperature IS '采样值；计量精度要求高时选择合适数值类型';

-- 将普通 PostgreSQL 表转换为按时间分块的 hypertable；仅适用于已安装扩展的 TimescaleDB。
SELECT create_hypertable('sensor_reading', 'time', if_not_exists => TRUE);

-- 查询指定设备在半开时间区间内的原始点，半开区间便于相邻窗口无重叠。
SELECT time, temperature
FROM sensor_reading
WHERE device_id = $1
  AND time >= $2
  AND time < $3
ORDER BY time ASC;

-- 将近期原始采样按 5 分钟汇总，给趋势图减少返回点数。
SELECT time_bucket(INTERVAL '5 minutes', time) AS bucket,
       device_id,
       AVG(temperature) AS avg_temperature,
       MIN(temperature) AS min_temperature,
       MAX(temperature) AS max_temperature,
       COUNT(*) AS sample_count
FROM sensor_reading
WHERE device_id = $1
  AND time >= $2
  AND time < $3
GROUP BY bucket, device_id
ORDER BY bucket ASC;
```

示例 DDL 和查询使用 PostgreSQL/TimescaleDB 语法；JDBC/MyBatis 执行时，参数占位方式要按驱动和框架语法传递，MySQL 方言也需相应改写。

### 时间范围、延迟数据和采样质量

- **使用 UTC 存储并约定展示时区：** 时间戳转换错误会导致图表偏移或跨日统计错位。
- **用半开区间 `[start, end)`：** 邻接窗口不会重复包含边界点；API 要说明 start/end 是 epoch、ISO-8601 还是本地时间。
- **接受迟到点：** 设备离线缓存后可能补传历史点；要设置允许回补窗口，超窗口数据怎样处理。
- **去重策略：** 同设备同时间可能重传。可用复合唯一键、样本 ID 或幂等写入；更新旧样本会增加写放大。
- **采样频率和缺失值：** 缺点、零值和设备停机不是同一语义；聚合时不能把“没有数据”当成 0。
- **查询点数上限：** 长时间范围按原始采样返回可能压垮 API 与浏览器；根据像素宽度降采样，或使用分钟/小时聚合。
- **时钟来源：** 设备时钟漂移会造成乱序和错误窗口；服务端可记录接收时间与事件时间两个字段。

## 时序技术不是一个产品类别

| 系统 | 常见定位 | 页面/API 场景 | 关键边界 |
|---|---|---|---|
| Prometheus | 拉取式监控指标、PromQL 与告警 | 服务 CPU、请求速率、延迟直方图 | 指标标签基数必须控制；通常不是长期任意业务事件存档 |
| InfluxDB | 时序点写入、标签过滤、窗口查询 | IoT/设备测量、运行指标 | 版本间 Flux/SQL/存储引擎差异大，按目标版本选文档 |
| TimescaleDB | PostgreSQL 扩展，以 SQL 管理时间分块表 | 业务 SQL 与时序查询结合 | 需要了解 PostgreSQL 和扩展版本、压缩/保留策略 |
| ClickHouse | 列式分析数据库，可承载大规模时间分区事件分析 | 高频日志、宽时间范围聚合与报表 | 适合分析扫描，不等同于事务关系库；数据模型与更新模式不同 |
| OpenTelemetry Collector + 后端 | 采集/转发指标、日志、Trace 的可观测性管道 | 微服务链路与基础设施指标 | Collector 是采集与处理组件，不是最终数据库；后端存储/查询能力另选 |

选型前确定写入协议（push/pull）、保留时长、查询语言、告警能力、标签基数、压缩、备份、集群高可用、部署运维和版本升级路径。不要因为 Prometheus 能存指标就把业务事件全部建成指标，也不要把 Trace ID 当作指标 label。

## 多种数据库并存时的数据流

```text
关系型主库：订单、账户、权限等权威业务事实
  ├─ Outbox / CDC -> 消息代理 -> 搜索索引或缓存的异步更新
  ├─ 业务事件 -> 时序库/列式分析库 -> 趋势、聚合和报表
  └─ Cache Aside -> Redis -> 降低热点详情读取延迟

前端页面
  -> 命令/权威查询优先走业务 API
  -> 高频指标图表走聚合查询 API
  -> 搜索建议走搜索服务
```

派生存储可以延迟或丢失后重建时，界面要明确“同步中”或“数据截至某时刻”。删除/修改主数据也要传播 tombstone 或失效事件，避免缓存和搜索索引永久保留旧资料。

## 项目选型练习

1. 把一个数据集拆成权威事实、可重建缓存、搜索投影、设备时序采样四类，并指出每类的唯一写入者。
2. 为每个接口写出读后写一致性、允许延迟和数据丢失后的恢复目标。
3. 估算设备数 × 每设备序列标签组合 × 每日采样数，评估存储量、活跃序列和图表返回点数。
4. 设计一个 24 小时趋势 API：约定 UTC 时间范围、聚合窗口、缺失点语义、最大点数和前端展示状态。
5. 让 Redis 或搜索索引在测试环境停用，验证哪些 API 能有界回源、哪些应返回明确不可用，以及前端是否会误显示为空数据。

继续学习：[JDBC](./jdbc.md)、[JDBC、MyBatis 与 ORM](./persistence.md)、[MySQL 与 Redis 基础](./storage.md)、[MySQL 生产机制](./mysql-operations.md)、[Redis 运维](./redis-operations.md)。
