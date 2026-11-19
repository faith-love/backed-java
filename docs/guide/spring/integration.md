# Spring 集成能力：异步、事件、缓存、定时与远程调用

真实后端很少只访问一个数据库。它还要发送通知、调用用户服务、刷新缓存、执行定时任务和发布领域事件。本章用“请求完成后还要做什么”这条主线，说明 Spring 常用集成能力的边界。

## 同步、异步、事件和消息不是同一件事

| 方式 | 调用方是否等待 | 是否持久化 | 失败后的处理 | 适合场景 |
|---|---:|---:|---|---|
| 同步方法调用 | 是 | 否 | 直接返回异常 | 必须立即得到结果的校验和查询 |
| `@Async` | 通常否 | 否 | 线程池异常/重试需自建 | 短暂、可丢失的后台工作 |
| Spring Event | 进程内，可同步或异步 | 否 | 进程崩溃会丢失 | 同一应用内解耦模块 |
| 消息队列 | 否 | 通常是 | 消费重试、死信、幂等 | 跨服务、可靠投递和削峰 |
| Outbox | 否 | 数据库持久化 | 轮询/发布重试 | 业务写入与事件发布的一致性 |

不要把 `@Async` 当作消息队列。异步线程池重启会丢任务，没有消费记录，也不能自然地跨服务传递。

## `@Async`：把可延迟工作移出请求线程

```java
// Spring 集成片段：启动类或配置类需要 @EnableAsync。
@org.springframework.scheduling.annotation.EnableAsync
@org.springframework.context.annotation.Configuration
class AsyncConfig {

    @org.springframework.context.annotation.Bean("notificationExecutor")
    public java.util.concurrent.Executor notificationExecutor() {
        // 有界队列避免请求高峰时无限堆积；线程数要根据外部服务容量配置。
        java.util.concurrent.ThreadPoolExecutor executor =
                new java.util.concurrent.ThreadPoolExecutor(
                        2,
                        8,
                        60,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(200),
                        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}

@org.springframework.stereotype.Service
class NotificationService {

    @org.springframework.scheduling.annotation.Async("notificationExecutor")
    public java.util.concurrent.CompletableFuture<Void> send(String userId, String text) {
        // 这里执行可重试通知；不要依赖调用线程的数据库事务已经提交。
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
```

重点检查：

- 调用是否经过 Spring 代理；同类 `this.send()` 可能不会异步。
- 异常是否能被观察；`void` 异步方法的异常不会自动返回给调用方。
- 线程池是否有上限、拒绝策略、关闭策略和监控指标。
- MDC、SecurityContext 和 Trace ID 是否通过 `TaskDecorator` 传播。
- 任务是否允许丢失；不允许丢失时使用消息队列或 outbox。

## Spring Event：进程内解耦

```java
// 事件对象只携带稳定事实，不要把可变 Entity 直接暴露给异步监听器。
public final class OrderPaidEvent {
    private final String orderNo;

    public OrderPaidEvent(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOrderNo() {
        return orderNo;
    }
}

@org.springframework.stereotype.Service
class PaymentApplicationService {
    private final org.springframework.context.ApplicationEventPublisher publisher;

    PaymentApplicationService(org.springframework.context.ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @org.springframework.transaction.annotation.Transactional
    public void markPaid(String orderNo) {
        // 先保存支付事实，再发布进程内事件。
        publisher.publishEvent(new OrderPaidEvent(orderNo));
    }
}

@org.springframework.stereotype.Component
class OrderPaidListener {

    // 只有事务提交后才发送通知，避免数据库回滚但通知已发出。
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void handle(OrderPaidEvent event) {
        // 这里仍要考虑监听失败、重试和应用重启丢失。
    }
}
```

`@TransactionalEventListener(AFTER_COMMIT)` 解决“只在本地事务提交后执行”的时序问题，但它仍然是进程内事件。若监听器执行失败，已经提交的订单不会自动回滚；需要重试表、outbox 或消息队列。

## 缓存：减少重复读取，不改变事实来源

```java
@org.springframework.stereotype.Service
class ProductQueryService {

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "product",
            key = "#productId",
            unless = "#result == null")
    public ProductView find(String productId) {
        // 缓存未命中才访问数据库。
        return loadFromDatabase(productId);
    }

    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = "product",
            key = "#productId")
    public void invalidate(String productId) {
        // 商品更新后删除缓存；实际更新和删除顺序要结合失败补偿设计。
    }

    private ProductView loadFromDatabase(String id) {
        return new ProductView(id);
    }
}

final class ProductView {
    private final String id;

    ProductView(String id) { this.id = id; }
    public String getId() { return id; }
}
```

缓存排错要看 key、命中率、TTL、空值策略、序列化版本和多实例失效通知。`@Cacheable` 同样依赖代理；同类内部调用可能绕过缓存。缓存数据可能过期、丢失或短暂不一致，数据库仍是事实来源。

## `@Scheduled`：周期任务的并发与幂等

```java
@org.springframework.context.annotation.Configuration
@org.springframework.scheduling.annotation.EnableScheduling
class ScheduleConfig {
}

@org.springframework.stereotype.Component
class ExpiredOrderJob {

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${order.job.delay:60000}")
    public void closeExpiredOrders() {
        // SQL 更新必须幂等，并检查影响行数；任务失败要有日志和告警。
    }
}
```

多副本部署时，每个实例默认都执行任务。结算、扣款、发货等任务需要分布式锁或专用调度平台；单实例清理任务也要设计重入，避免上一次执行未结束时下一次又开始。

## Feign、RestClient 和 WebClient

| 客户端 | 编程模型 | 适合场景 | 关键配置 |
|---|---|---|---|
| OpenFeign | 声明式接口 | Spring Cloud 服务间调用 | 连接/读取超时、重试、错误解码、Trace 传播 |
| `RestTemplate` | 阻塞式模板 | Boot 2.x 老项目、简单同步调用 | 连接池、超时、拦截器 |
| `RestClient` | 新的同步 API | Spring 6/Boot 3 新项目 | 超时、状态码处理、序列化 |
| `WebClient` | 响应式非阻塞 | WebFlux 或高并发 I/O | backpressure、超时、调度器、响应释放 |

```java
// Spring Cloud OpenFeign 集成片段：需要 @EnableFeignClients。
@org.springframework.cloud.openfeign.FeignClient(
        name = "user-service",
        path = "/api/users")
interface UserClient {

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    UserView find(@org.springframework.web.bind.annotation.PathVariable("id") String id);
}
```

声明式接口看起来像本地调用，但失败来自远程网络：DNS、连接、读取超时、网关、限流、认证、下游 5xx 都要转换成稳定的业务错误。不要默认开启无限重试；非幂等 POST 重试可能重复创建数据。

### 远程调用四项必备设计

1. **超时**：连接超时、读取超时、整体截止时间都要设置。
2. **重试**：只对明确可重试的网络错误和幂等操作重试，并设置退避和上限。
3. **隔离**：用连接池、并发上限、舱壁或熔断避免一个下游拖垮全部请求。
4. **观测**：记录下游名称、状态、耗时、重试次数和 traceId，脱敏请求体。

## Outbox：数据库提交与事件发布的桥

当订单写入数据库后还要发送消息时，直接“先写库再发消息”会遇到进程在两步之间崩溃；直接“先发消息再写库”则可能发布不存在的订单。Outbox 把待发布事件和业务数据放进同一个本地事务：

```text
本地事务：订单表 INSERT + outbox_event INSERT
    -> 事务提交成功
Outbox worker：读取未发布事件 -> 发送消息 -> 标记 published
    -> 失败保留记录，按 eventId 重试
```

事件表需要唯一 `event_id`、状态、重试次数、下次重试时间和错误摘要。消费者仍然要幂等，因为消息可能重复投递。Outbox 不提供跨系统的瞬时原子性，但把“业务事实已提交”和“需要发布什么”保存到了同一数据库。

## 集成能力排错矩阵

| 现象 | 证据 | 常见原因 |
|---|---|---|
| `@Async` 没有异步 | 线程名、代理类型 | 未启用、同类调用、执行器名称错误 |
| 任务重复执行 | 实例数、任务日志 | 多副本没有分布式锁或幂等条件 |
| 缓存一直不更新 | key、TTL、失效日志 | key 不一致、代理绕过、更新顺序错误 |
| 远程调用偶发超时 | 下游耗时、连接池、重试 | 没设置超时、连接池耗尽、重试风暴 |
| 订单已提交但没有消息 | outbox 状态、发布 worker | 进程崩溃、worker 停止、消息发送失败 |
| 监听器抛错但接口成功 | 事务阶段、监听器日志 | AFTER_COMMIT 失败不会回滚已提交事务 |

## 前端开发者的阅读路径

1. 从浏览器 Network 找到接口是否同步返回，还是返回任务 ID。
2. 找 Service 是否有 `@Async`、事件发布、缓存注解或远程 Client。
3. 如果是异步任务，确认前端通过轮询、SSE、WebSocket 或消息通知获得最终状态。
4. 如果是缓存，确认更新接口是否清理相关 key，列表与详情是否使用不同 key。
5. 如果是远程调用，确认错误状态是业务失败、超时、熔断还是权限失败。
6. 如果是事件/消息，确认数据库提交、outbox、发布、消费和幂等各自的日志证据。

下一步：[Spring 数据访问](./data-access.md)、[Spring 测试](./testing.md)、[Spring 可观测性](./observability.md)。
