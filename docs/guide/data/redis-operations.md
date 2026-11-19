# Redis 运维与消息语义：从缓存到高可用

现有 [MySQL 与 Redis](./storage.md) 章节介绍 Redis 数据结构、Cache Aside、缓存故障、TTL 和分布式锁。本章继续讲 Redis 在运行环境中的部署、内存、客户端和消息语义，帮助你判断：一个 API 读缓存是否安全、Redis 故障会影响哪些业务、为什么消息会重复或丢失。

Redis 适合低延迟的内存数据访问，但“Redis 命令执行快”不等于“数据永不丢失、消息必达、集群永远可用”。每个使用点都要明确权威数据源、持久化、过期、失败降级和恢复方式。

## 单机、Sentinel 和 Cluster 解决不同问题

| 部署模式 | 主要解决的问题 | 数据分布 | 故障特征 | 适合场景 |
|---|---|---|---|---|
| Standalone | 简化部署和开发 | 单节点 | 实例故障时不可用，除非外部恢复 | 本地开发、可重建且低风险缓存 |
| 主从复制 | 复制读数据、准备故障转移 | 主节点持有全量数据，从节点复制 | 复制通常异步，从节点可能落后 | 读扩展、Sentinel 的底层复制拓扑 |
| Sentinel | 自动监控主从并选举新主 | 仍是一份逻辑数据集 | 提供故障检测和主节点切换，不负责分片 | 需要高可用但单数据集仍能放在一个主节点 |
| Cluster | 横向分片和节点级故障转移 | Key 分布在多个 hash slot/主节点 | 部分 slot 不可用会影响对应 key；多 key 操作受 slot 约束 | 数据量或吞吐已超过单主节点能力 |

Sentinel 与 Cluster 不是两个“高可用等级”：Sentinel 管理复制组的故障转移，但不把数据拆到多个主节点；Cluster 把 key 分片到不同主节点并带有复制和故障转移机制。选择要根据数据规模、可接受故障范围、客户端支持和运维能力决定。

### Sentinel 故障转移仍有数据窗口

主从复制通常是异步的。主节点确认写入后、复制到从节点前发生故障，刚确认的写可能未到新主节点。因此：

- Redis 缓存丢失后能从数据库重建，通常可以接受短暂不一致。
- Redis 中的任务状态、限流计数、会话或业务事实要评估丢失影响。
- 支付、库存、余额等最终事实不应只存在 Redis。
- 需要一致性写入时，仍应使用数据库约束/事务、Outbox 或专门的账本/消息设计。

主节点切换还会造成连接中断。客户端需要使用支持 Sentinel/Cluster 的客户端配置，处理有限重连、命令超时和幂等重试；超时不代表命令一定没执行，写操作要查询结果或使用幂等键。

### Cluster 的 hash slot 与多 key 操作

Cluster 将键映射到固定数量的 hash slot，再由节点持有 slot。多 key 命令、Lua 脚本和事务通常要求涉及的 key 位于同一个 slot，否则会出现 `CROSSSLOT` 错误。

```text
cache:{tenant-42}:user:1001
cache:{tenant-42}:user:1002
```

花括号中的 hash tag 可让相关 key 使用同一个计算片段，从而落入同一 slot。但不要把所有 key 都使用同一个 tag，否则会制造热点 slot。真实 key 设计要平衡“多 key 原子操作需求”和“负载均匀”。

## 内存上限、淘汰策略和大键

Redis 把数据放在内存中，必须关注 `used_memory`、`used_memory_peak`、碎片率、命中率、过期键、驱逐键、复制缓冲和客户端输出缓冲。只看 key 数量无法判断内存风险：一个包含数十万成员的 Set 可能比很多小 key 更危险。

### `maxmemory` 与淘汰策略

常见策略包括：

- `noeviction`：内存达到上限后拒绝会增加内存的写入。适合不希望静默删除数据的场景，但应用必须处理写失败。
- `allkeys-lru` / `allkeys-lfu`：从所有 key 中淘汰近期/低频使用的数据，适合缓存。
- `volatile-lru` / `volatile-lfu`：只从有 TTL 的 key 中淘汰；没有 TTL 的业务 key 不会被淘汰。
- `volatile-ttl`：优先淘汰剩余 TTL 较短的 key。
- `allkeys-random` / `volatile-random`：随机淘汰，一般需要明确评估访问模式。

淘汰策略是全实例级别的配置。如果缓存、验证码、任务状态和锁共用一个实例，缓存压力可能淘汰不希望丢失的短期状态。优先按数据重要性隔离实例/集群，并为可淘汰数据设置 TTL；不能把 Redis 内存淘汰当成业务过期策略。

### 大键和热键的诊断

- **Big Key**：单个 key 对应集合或序列化值过大，读取、删除、复制或过期处理可能占用较长时间。
- **Hot Key**：少数 key 承受过多访问，即使每个 key 很小，也可能打满一个节点/slot 的网络或 CPU。
- 先用监控和采样确认 key 的大小、命令耗时、访问量及所属 slot，再分批处理。
- 避免对生产实例直接执行全量阻塞扫描、一次性 `DEL` 超大集合或将大对象整体序列化进单个 value。
- 大集合删除可按 Redis 版本和数据类型考虑 `UNLINK` 或分批删除；仍需关注后台释放造成的内存和 CPU 压力。

```bash
# 通过抽样扫描估计大 key；不同 Redis 版本的选项可能略有差异。
redis-cli --bigkeys

# 检查单个 key 的近似内存占用，不要在业务热路径上频繁执行。
redis-cli MEMORY USAGE 'cache:tenant:42:report:summary'

# 查看慢命令样本和 Redis 延迟事件；输出可能包含 key 名，分享前先脱敏。
redis-cli SLOWLOG GET 20
redis-cli LATENCY LATEST
```

`redis-cli --bigkeys` 是抽样工具，不是所有业务查询形状的精确容量审计。生产环境用受控时间窗、只读账号和指标平台逐步诊断，避免诊断命令本身给实例造成额外负载。

## `KEYS` 与 `SCAN`：生产遍历要可中断

`KEYS pattern` 会在服务端遍历匹配键，数据量大时可能长时间占用 Redis 主线程。生产代码不要用它做在线清理、分页或定时扫描。

`SCAN` 以游标分批返回，适合后台维护；它不是快照：遍历期间新增/删除 key 时结果可能重复或漏掉，调用方要能幂等处理。`COUNT` 是期望批次大小，不是严格结果数量。

```text
# 从游标 0 开始逐页扫描；后续调用用 Redis 返回的下一游标。
SCAN 0 MATCH cache:tenant:42:* COUNT 200

# 游标返回 0 才表示这一轮结束；即使此前返回空列表也不能提前结束。
```

如果需要对在线业务 key 执行大规模删除，应设计限速、批次、审计、重试和回滚/重建方案。不要让 HTTP 请求线程执行完整实例扫描。

## Pipeline、Redis Transaction 与 Lua 的差异

| 机制 | 解决什么问题 | 是否原子 | 适合场景 |
|---|---|---:|---|
| Pipeline | 减少多条命令的网络往返 | 否 | 批量读取/写入彼此独立的 key |
| `MULTI`/`EXEC` | 把一组命令排队后连续执行 | 命令执行期间不被其他命令插入；不等于跨系统事务 | 同一 Redis 中的简单命令组 |
| `WATCH` | 检查 key 未被其他客户端修改再提交 | 乐观并发控制 | 读-改-写，冲突可重试 |
| Lua | 在 Redis 内原子执行有条件的多步逻辑 | 是，脚本整体运行 | 限流、比较并释放锁、原子读改写 |

Pipeline **不是**事务：如果第 3 条命令失败，其他已发送命令仍可能成功。`MULTI`/`EXEC` 会让排队命令连续执行、不被其他客户端命令插入，但某条命令执行时出错通常不会自动回滚其他已执行命令，也不是 MySQL 那种跨步骤回滚事务。Pipeline 结果要逐条检查；大量批次要限制命令数和值大小，避免缓冲区过大。

```java
// Spring Data Redis 示例：把彼此独立的 key 批量读取以减少网络往返。
List<Object> values = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    // 使用连接层 API 发送多个 GET；结果按发送顺序返回。
    for (byte[] key : serializedKeys) {
        connection.stringCommands().get(key);
    }
    // Pipeline 回调返回值由 executePipelined 统一收集；这里不返回单个业务结果。
    return null;
});

// 调用方仍需处理缺失值、类型/反序列化失败和部分命令错误。
```

上述 API 形态可能随 Spring Data Redis 版本变化；使用项目管理的依赖版本核对泛型、序列化和连接实现。不要为了 Pipeline 把有依赖顺序的命令并行化。

## Pub/Sub 和 Stream：通知与可消费日志的区别

| 能力 | Pub/Sub | Redis Stream |
|---|---|---|
| 消费者离线期间的消息 | 不保留，订阅前消息也收不到 | 保留在 Stream，直到被裁剪/删除 |
| 消费确认 | 无 ACK 和 pending 状态 | 消费组支持 `XACK` 和 pending entries |
| 重放 | 无内建历史 | 可按 ID 从历史读取 |
| 多消费者 | 同频道订阅者各自收到实时推送 | 消费组将消息分派给组内消费者 |
| 投递语义 | 尽力实时通知，不是可靠队列 | 可实现至少一次处理，但消费者要幂等 |
| 适合用途 | 配置刷新提示、在线状态广播 | 轻量持久事件、消费组任务和可恢复处理 |

Redis Stream 仍不自动等同于企业级消息平台：容量增长、裁剪、死信、重试延迟、跨区域复制和可观测性都需要设计。高价值、跨服务事件可根据系统要求选择 Kafka/RabbitMQ/RocketMQ 等消息系统，并用 Outbox 保证数据库事实与事件记录一致。

### Consumer Group：ACK 代表已处理完成

使用消费组时，消息读取后会进入 pending 列表。消费者应在业务结果已持久化后才确认；先 ACK 后写数据库，进程崩溃会造成业务未落库但消息已确认。写库成功但 ACK 失败会重复消费，因此数据库更新必须幂等。

```text
# 创建组；$ 表示从新消息开始消费，0 表示读取已有历史。
XGROUP CREATE order-events order-workers $ MKSTREAM

# 由 consumer-a 读取新消息；消息会进入该消费者的 pending 列表。
XREADGROUP GROUP order-workers consumer-a COUNT 20 BLOCK 5000 \
  STREAMS order-events >

# 业务事务成功提交后再确认；失败时保留 pending 以便排查/重试。
XACK order-events order-workers 1731840000000-0
```

生产消费者还需要：

1. 用业务 ID/eventId 建立唯一约束或去重记录，允许重复投递而不重复产生副作用。
2. 监控 pending 数、最老消息年龄、消费速率、失败数和 Stream 长度。
3. 定期检查失联消费者并使用版本支持的 `XAUTOCLAIM`/`XCLAIM` 转移超时 pending；重领前确认处理动作可幂等重放。
4. 设计重试次数、死信/隔离记录和人工重放工具，不能无限快速重试毒消息。
5. 裁剪 Stream 时考虑消费者组和未处理消息；过度裁剪会丢掉尚未消费的历史。

`XAUTOCLAIM` 从 Redis 6.2 起提供；较老版本需要不同的 pending/claim 工作流。集群、多副本和客户端版本也要在目标 Redis 版本上验证。

### Spring Data Redis 消费闭环：业务库提交后再 ACK

下面示例展示“读到消息 → 数据库事务幂等处理 → 提交成功 → Redis ACK”的顺序。数据库事务和 Redis ACK 不是同一个原子事务，因此仍可能在数据库提交后、ACK 前崩溃；唯一 `event_id` 记录让重复投递可以安全识别。

```java
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// 这是 Spring Data Redis 集成片段，需要 StringRedisTemplate、数据库仓储和事务管理器。
public final class OrderEventConsumer {

    // Stream 与消费组名称应从受控配置读取，不能让浏览器指定。
    private static final String STREAM = "order-events";
    private static final String GROUP = "order-workers";

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate databaseTransaction;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderService orderService;

    // 通过构造器注入依赖，方便测试事务提交、重复消息和 ACK 失败路径。
    public OrderEventConsumer(
            StringRedisTemplate redisTemplate,
            TransactionTemplate databaseTransaction,
            ProcessedEventRepository processedEventRepository,
            OrderService orderService) {
        this.redisTemplate = redisTemplate;
        this.databaseTransaction = databaseTransaction;
        this.processedEventRepository = processedEventRepository;
        this.orderService = orderService;
    }

    // 单条记录的处理必须由消息读取循环调用；读取和 pending 回收策略另行负责。
    public void handle(MapRecord<String, String, String> record) {
        // 生产者提供稳定业务事件 ID；不要只用 Stream ID 代替跨重发去重键。
        Map<String, String> body = record.getValue();
        String eventId = body.get("eventId");
        String orderId = body.get("orderId");
        if (eventId == null || eventId.isBlank() || orderId == null || orderId.isBlank()) {
            // 无法解析的毒消息要写入隔离/死信记录并告警，不能静默 ACK 丢弃。
            throw new IllegalArgumentException("eventId 和 orderId 必须存在");
        }

        try {
            // 先提交数据库事务；event_id 列必须有唯一约束，并与业务写入处于同一事务。
            databaseTransaction.execute(status -> {
                if (!processedEventRepository.exists(eventId)) {
                    // 如果并发消费者同时处理，数据库唯一键负责最终仲裁。
                    processedEventRepository.insert(eventId);
                    // 本地业务写入失败会回滚事件去重记录，消息保留在 pending。
                    orderService.applyEvent(orderId, body);
                }
                // 已存在的 eventId 表示业务事务此前已经提交，可以安全地跳过副作用。
                return null;
            });

            // 只有数据库事务提交返回后才 ACK；ACK 失败会导致重复投递，但可由 eventId 去重。
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (DuplicateKeyException duplicate) {
            // 只在确认去重记录已提交时把唯一键冲突视为重复消息；其他唯一冲突必须继续报错。
            if (processedEventRepository.exists(eventId)) {
                redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
                return;
            }
            throw duplicate;
        }
        // 其他数据库或 Redis 异常向上报告；未 ACK 的记录留在 pending 供有限重试/超时重领。
    }
}
```

`ProcessedEventRepository.exists/insert` 代表数据库访问边界，`event_id` 应建立唯一索引。若业务处理还会调用支付、邮件或另一个服务，数据库事务无法回滚那些副作用；要将副作用改为幂等远程操作，或在同一数据库事务中写 Outbox 后异步发布。消费者还要对失败次数设上限：超过阈值先把原消息、错误原因和重试记录可靠写入隔离/死信存储，再 ACK 原消息，避免毒消息无限快速重试。

## 持久化、复制与消息恢复

RDB/AOF、复制和集群切换共同决定宕机后能恢复多少数据，但它们不能只凭配置名称推断 RPO/RTO：

- RDB 按快照点恢复，最后快照之后的写可能丢失。
- AOF 根据 fsync 策略可能丢失最近一段写入，重写期间也要关注磁盘空间。
- 主从复制通常异步，故障切换可能丢最近复制未完成的写。
- Cluster 的 failover 可能导致部分 slot 短时不可用。
- Stream 的数据保留取决于显式裁剪、删除、内存淘汰和持久化设置。

如果 Redis 是可重建缓存，恢复可以从 MySQL 回填；如果承载验证码、会话或任务状态，则要定义失效后用户/任务如何恢复；若它承载不可丢业务事件，应重新评估是否应由可靠消息系统或数据库 Outbox 保存事实。

### AOF 的 fsync 策略与数据丢失窗口

启用 AOF 后，Redis 将写命令追加到日志；`appendfsync` 决定 Redis 多久要求操作系统把日志刷到持久介质。它影响崩溃时可能丢失的数据量，也会影响写延迟和吞吐。

```ini
# 开启 AOF；具体持久化组合要结合 Redis 版本和 RDB 需求评估。
appendonly yes

# 每秒请求一次 fsync，是常见的延迟与耐久性折中；不等于任何故障下都精确只丢 1 秒。
appendfsync everysec

# 重写期间仍允许按上述策略 fsync；改为 yes 可减轻磁盘压力，但可能扩大崩溃后的数据窗口。
no-appendfsync-on-rewrite no

# 以下阈值是示例，需按写入量、磁盘空间、重写耗时和版本设置容量告警。
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

| 策略 | 取舍 | 常见考虑 |
|---|---|---|
| `appendfsync always` | 每次写命令都请求 fsync，写延迟和磁盘负担较高 | 仅在业务确认能承担性能成本且目标环境实测后采用 |
| `appendfsync everysec` | 通常每秒 fsync 一次，提供常见的平衡点 | 多数缓存/状态服务会从此策略开始评估；故障类型和存储设备会影响实际损失 |
| `appendfsync no` | 由操作系统决定刷盘时机，吞吐更高 | 对最近写入可丢失较多数据有明确容忍时才考虑 |

即使配置了 `everysec`，操作系统崩溃、存储控制器缓存、磁盘故障或主从切换都可能造成不同的数据窗口；fsync 也会让延迟出现尖峰。用与生产相同的磁盘类型、写入负载、AOF 重写和故障演练测量实际 RPO/RTO。Redis 7 起 AOF 通常采用多段文件与 manifest 管理，诊断和备份时应使用 Redis 支持的流程，不要只复制正在变化的单个 AOF 文件。

### 隔离环境：备份并恢复一个 RDB 快照

下面用两个仅绑定到本机回环地址的临时 Redis 容器演示恢复校验。所有容器名、卷名和端口都带 `backend-learning` 前缀；仅在本地或可丢弃的测试环境运行，不要连接生产数据，也不要把真实数据备份到个人电脑。

先启动源实例并写入一条可验证的测试数据：

```bash
# 启动仅映射到本机 6380 的临时源实例；数据目录使用容器内 /data。
docker run -d --name backend-learning-redis-source \
  -p 127.0.0.1:6380:6379 \
  redis:7.4-alpine redis-server \
  --dir /data --dbfilename dump.rdb --appendonly yes --appendfsync everysec

# 写入带有唯一值的实验数据，恢复后通过它确认快照内容。
redis-cli -h 127.0.0.1 -p 6380 SET lab:restore:canary "snapshot-2026-11-18"

# 请求生成快照，并确认后台快照已完成且状态成功。
redis-cli -h 127.0.0.1 -p 6380 BGSAVE
redis-cli -h 127.0.0.1 -p 6380 INFO persistence | grep -E 'rdb_bgsave_in_progress|rdb_last_bgsave_status|rdb_last_save_time'

# 将源容器中的 RDB 快照复制到当前受控练习目录。
mkdir -p ./redis-restore-lab/backup
docker cp backend-learning-redis-source:/data/dump.rdb ./redis-restore-lab/backup/dump.rdb

# 校验快照格式；不同镜像的 redis-check-rdb 命令路径可能不同。
docker exec backend-learning-redis-source redis-check-rdb /data/dump.rdb
```

再创建空的恢复卷和仅监听 `127.0.0.1:6381` 的目标实例。此容器关闭 AOF 和自动 RDB 保存，避免在实验中意外覆盖待恢复文件：

```bash
# 创建专用恢复卷；它与源实例的数据目录隔离。
docker volume create backend-learning-redis-restore-data

# 启动空目标实例；--save "" 禁用自动 RDB 保存，方便观察首次载入快照。
docker run -d --name backend-learning-redis-restore \
  -p 127.0.0.1:6381:6379 \
  -v backend-learning-redis-restore-data:/data \
  redis:7.4-alpine redis-server \
  --dir /data --dbfilename dump.rdb --appendonly no --save ""

# 停止目标实例后，把快照放进其数据目录，再启动以载入 RDB。
docker stop backend-learning-redis-restore
docker cp ./redis-restore-lab/backup/dump.rdb backend-learning-redis-restore:/data/dump.rdb
docker start backend-learning-redis-restore

# 验证关键数据、数据库数量和恢复状态；还要按业务验证数据完整性。
redis-cli -h 127.0.0.1 -p 6381 GET lab:restore:canary
redis-cli -h 127.0.0.1 -p 6381 DBSIZE
redis-cli -h 127.0.0.1 -p 6381 INFO persistence | grep -E 'loading|rdb_last_bgsave_status'
```

源实例启用了 AOF，启动恢复实例时必须清楚 Redis 在 AOF 和 RDB 都存在时会优先使用哪种持久化文件；上面的目标实例显式关闭 AOF 来验证 RDB。Redis 7 的多段 AOF 需要保留 manifest 和所有相关文件，不能只拷贝一个看起来最大的 `.aof` 文件。对 AOF 运行 `redis-check-aof --fix` 会修改文件，只能先复制原件后在隔离副本上修复。

实验完成后，只清理前缀完全匹配的练习资源；删除卷会永久删除其中的数据：

```bash
# 先核对输出，只删除本练习创建的两个容器和一个命名卷。
docker rm -f backend-learning-redis-source backend-learning-redis-restore
docker volume rm backend-learning-redis-restore-data
```

### 隔离环境：观察 Sentinel 与 Cluster 的故障切换

本实验需要预先准备的临时 Redis 拓扑：Sentinel 至少三个进程以形成可用 quorum，主从复制组至少一个主节点和一个副本；Cluster 则需要多个主节点并为关键分片准备副本。先确认容器、节点名和端口，再执行命令。示例中的 `mymaster`、`127.0.0.1:26379` 和 `7000` 都是占位拓扑，不代表当前项目配置。

Sentinel 实验先检查 quorum、主节点地址和复制状态：

```bash
# 确认当前 Sentinel 数量和 quorum 足以选主。
redis-cli -h 127.0.0.1 -p 26379 SENTINEL CKQUORUM mymaster

# 查询 Sentinel 当前认为的主节点地址。
redis-cli -h 127.0.0.1 -p 26379 SENTINEL GET-MASTER-ADDR-BY-NAME mymaster

# 将变量设置为上一步返回的真实地址，再查看主从复制状态和复制偏移量。
MASTER_HOST=127.0.0.1
MASTER_PORT=6379
redis-cli -h "$MASTER_HOST" -p "$MASTER_PORT" INFO replication
```

在**专用临时拓扑**中停止当前主节点（例如 `docker compose stop redis-master`），观察 Sentinel 日志和新主地址变化；新主稳定后，用 Sentinel 客户端重新发现拓扑，写入一个带实验 ID 的值，再确认副本角色与数据。恢复旧主时确认它重新加入为副本；如果旧主仍可写，先隔离它，避免双主写入。记录故障开始、客户端恢复和业务探针成功时间，得到这次实验的实际可用性窗口与已确认数据窗口。不要在生产环境通过停止节点做学习实验。

Cluster 先查看 slot 覆盖和节点角色，再检查 key 路由：

```bash
# 查看集群是否可用以及失败 slot 数量。
redis-cli -c -h 127.0.0.1 -p 7000 CLUSTER INFO

# 查看主从节点、slot 范围和节点状态；对照每个关键 slot 是否有副本。
redis-cli -c -h 127.0.0.1 -p 7000 CLUSTER NODES

# 检查相关业务 key 的 slot；同一个花括号 tag 会参与 slot 哈希。
redis-cli -c -h 127.0.0.1 -p 7000 CLUSTER KEYSLOT 'order:{tenant-42}:1001'
redis-cli -c -h 127.0.0.1 -p 7000 CLUSTER KEYSLOT 'order:{tenant-42}:1002'
```

在测试集群中停止某个节点后，分别请求落在该节点 slot 和其他节点 slot 的 key，观察客户端 `MOVED` 重定向、短暂超时或 `CLUSTERDOWN`；恢复节点后检查 slot 状态、复制和数据。Cluster 是否在部分 slot 不可用时继续服务，受副本状态及 `cluster-require-full-coverage` 等配置影响，必须在目标 Redis 版本和客户端上实测。Sentinel 用客户端重新发现主节点，Cluster 客户端则需要更新 slot 拓扑；这两者不是同一种故障恢复流程。

## 客户端连接池、超时与序列化

- Redis 客户端要有连接/命令超时、连接池上限和等待上限；无限等待会把 Redis 故障传导到 Web 线程池。
- Lettuce 通常支持线程安全的共享连接，Jedis 常见配置通过池管理连接；以项目实际客户端和版本为准，不要不加判断地为每次调用创建/关闭连接。
- Pipeline/阻塞命令和普通缓存请求可能需要隔离连接池，避免慢操作占满业务请求连接。
- 对每种值定义序列化格式和版本；Java 类改名、字段变化或 JSON 配置变化可能让旧缓存无法反序列化。
- 避免把不可信字节流当成任意 Java 对象反序列化；敏感字段不要写入共享缓存或监控标签。
- 清理缓存时考虑版本前缀，例如 `app:v2:product:{id}`，并在发布时设计旧 key 过渡和清理策略。

## 安全：网络边界、ACL 和最小权限

1. Redis 不应直接暴露到公网；使用私有网络、TLS（如果跨不可信网络）、防火墙和受控服务发现。
2. 生产启用认证和 ACL 用户；按业务限制可访问的 key pattern 和命令集合，避免应用账号拥有不必要的 `FLUSHALL`、`CONFIG`、`DEBUG` 等管理权限。
3. Redis 6+ 支持 ACL 用户；旧版本用的认证方式和能力不同，先确认服务端版本。
4. 区分应用读写账号、监控账号和运维账号；密钥放 Secret 管理，不写入源码、日志和前端配置。
5. key/value 里可能包含用户数据，监控、慢日志、诊断输出和备份文件都要按敏感数据处理。
6. 管理操作要审计：谁执行了扫描、删除、切主、恢复或配置变更，执行了多少 key，是否可以恢复。

## Redis 故障如何影响前端接口

| 故障/指标 | 可能的浏览器表现 | 下一步证据 |
|---|---|---|
| Redis 连接超时/连接池耗尽 | API 偶发 500/504，加载变慢 | Redis command latency、客户端 pending、线程池等待 |
| 缓存命中率骤降 | 页面变慢，数据库负载上升 | hit/miss、DB QPS、回源并发和缓存重建耗时 |
| 驱逐数快速增加 | 用户看到状态反复或缓存频繁失效 | `evicted_keys`、maxmemory、TTL 与 key 大小 |
| 主节点切换 | 短暂失败或重试后恢复 | Sentinel/Cluster 事件、客户端重连、写命令结果 |
| Stream pending 增长 | 后台任务延迟，页面状态长期处理中 | 消费速率、最老 pending、消费者存活和失败原因 |
| CPU/网络饱和 | 多个页面接口同时变慢 | 热点 key、慢命令、单节点分片和输出缓冲 |

故障降级要按用途分别决定：可重建缓存可有界回源；认证状态验证失败不能默认放行；限流存储失效不能让敏感接口无限通过；锁获取失败不能继续执行关键非幂等操作。降级必须有告警和限流，否则 Redis 故障容易变成数据库雪崩。

## datacenter-cloud 中的 Redis 阅读路径

当前源码中可从以下位置确认项目采用了哪些 Redis 能力，不要仅根据依赖或 `@EnableCaching` 推断业务都走注解缓存：

1. `gykj-common/gykj-common-redis/src/main/java/com/gykj/common/redis/configure/RedisConfig.java`：看 Spring Cache 启用和 `RedisTemplate` 的 key/value/hash 序列化配置。
2. `gykj-common/gykj-common-redis/src/main/java/com/gykj/common/redis/service/RedisService.java`：看 String、List、Set、Hash、TTL 等操作封装。
3. 搜索具体业务的 `RedisService` 调用或 `@Cacheable`，记录 key 是否带租户/用户/版本维度、是否设置 TTL、Redis 失败如何处理。
4. 不要把配置文件中的真实密码、主机、Token 或密钥复制到学习文档、截图和日志。

## 学习练习

### 基础

1. 用 `SCAN` 分批列出某个业务 key 前缀，解释为什么不能在生产接口里用 `KEYS`。
2. 给一个 cache key 估算 TTL、对象大小和淘汰策略，观察 `hit rate` 与 `evicted_keys` 的关系。
3. 用 Pipeline 批量读取 100 个独立 key，比较单条往返和 Pipeline 的 RTT；再说明为什么 Pipeline 不能保证整批原子。

### 进阶

4. 设计 Stream 消费：数据库写入成功后 ACK，模拟进程在“写库后、ACK 前”退出并验证重复消费幂等。
5. 对比 Pub/Sub 与 Stream：让消费者离线一分钟，判断上线后能否收到期间的消息。
6. 画出 Sentinel 与 Cluster 的 key 路由和故障路径，说明多 key Lua/事务怎样使用 hash tag。
7. 模拟 Redis 不可用，观察可重建缓存、验证码、限流和分布式锁接口采取的不同失败策略。
8. 按“RDB 生成 → 校验 → 临时实例恢复 → 业务 key/数量核对”完成一次隔离环境恢复演练，记录 RPO/RTO。
9. 在三 Sentinel 测试拓扑停掉当前主节点，验证新主发现、客户端重连、旧主重新加入和副本复制状态。

### 运维排错

10. 检查 `INFO memory`、`INFO stats`、`INFO commandstats`、慢日志和客户端连接数，建立“内存、延迟、命中率、驱逐、连接池”诊断表。
11. 模拟大键或热键，比较单 key 拆分、批次读取、短本地缓存和限流的影响，并评估一致性代价。
12. 检查 ACL 用户、key pattern、可执行命令、TLS/网络边界和密钥轮换过程。

继续学习：[MySQL 与 Redis 基础](./storage.md)、[异步、事件、缓存与服务集成](../spring/integration.md)、[数据层与持久层入口](./index.md)。
