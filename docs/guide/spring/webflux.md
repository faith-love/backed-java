# Spring WebFlux：响应式请求、背压与 MVC 选型

WebFlux 是 Spring 提供的响应式 Web 技术栈，基于 Reactor 的 `Mono` 和 `Flux` 表达异步数据流。它适合高并发 I/O、流式响应和连接数较多的场景，但不会因为把返回类型改成 `Mono` 就自动让阻塞 JDBC、文件和 HTTP 调用变成非阻塞。

## 前端开发者的直觉映射

| 前端概念 | WebFlux 中相近的理解 | 关键差异 |
|---|---|---|
| Promise | `Mono<T>` | 0 或 1 个异步结果，异常也是信号的一部分 |
| AsyncIterable/Observable | `Flux<T>` | 0 到多个元素，可能持续很久 |
| `await` | `map`/`flatMap`/订阅 | 应尽量组合流水线，不在事件循环线程阻塞等待 |
| RxJS operator | Reactor operator | 操作符会改变数据流、线程、背压和错误传播 |
| SSE/WebSocket | `Flux<ServerSentEvent<?>>` / WebSocket Flux | 连接长时间保持，需考虑取消、心跳和资源释放 |

## `Mono`、`Flux` 与订阅

```java
// WebFlux 集成片段：需要 spring-boot-starter-webflux。
@RestController
@RequestMapping("/api/products")
public class ProductReactiveController {

    private final ProductReactiveService service;

    public ProductReactiveController(ProductReactiveService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public Mono<ProductView> find(@PathVariable String id) {
        // Mono 表示最多一个结果；服务不存在时可以返回 Mono.empty() 或异常。
        return service.find(id);
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ProductView>> stream() {
        // Flux 表示连续数据；客户端断开时要让上游收到取消信号。
        return service.stream()
                .map(item -> ServerSentEvent.builder(item).build());
    }
}
```

响应式链条只有在被框架订阅时才会执行。不要在业务代码里随意手工调用 `subscribe()`，否则异常、取消和事务边界会脱离 WebFlux 请求生命周期。Controller 返回 `Mono/Flux`，由 WebFlux 适配器负责订阅、写响应和处理取消。

## `map`、`flatMap` 和 `concatMap`

```java
public Mono<OrderView> findOrder(String id) {
    return orderRepository.findById(id)
            // map 用于同步转换，不会再产生一个 Mono。
            .map(this::toView);
}

public Flux<PriceView> loadPrices(List<String> productIds) {
    return Flux.fromIterable(productIds)
            // flatMap 用于调用返回 Mono 的异步方法；可能并发执行且顺序不保证。
            .flatMap(priceClient::find, 8)
            // 如果必须保持输入顺序，可使用 concatMap，但吞吐通常更低。
            .concatMap(this::normalize);
}
```

选择操作符时先明确语义：

- `map`：一个元素同步变成另一个元素。
- `flatMap`：一个元素展开成异步 Publisher，允许并发交错。
- `concatMap`：逐个等待完成，保持顺序。
- `switchIfEmpty`：没有结果时切换到备用 Publisher。
- `timeout`：超过时间发送错误信号；后面还要决定重试、降级还是取消。
- `retryWhen`：只对幂等或有幂等键的操作重试，不能无条件用于支付/写入。

## 背压：下游处理不过来怎么办

背压是下游通过请求量告诉上游“我现在能处理多少”。它解决的是生产速度和消费速度不匹配，而不是自动消除内存和数据库压力。

```java
public Flux<LogLine> readLogStream(Flux<LogLine> source) {
    return source
            // 每次最多缓存 1000 条；队列满时按业务选择丢弃、错误或最新值策略。
            .onBackpressureBuffer(1000,
                    dropped -> log.warn("log buffer overflow, dropped={}", dropped),
                    BufferOverflowStrategy.ERROR)
            // 控制下游并发，避免同时打开过多文件或远程连接。
            .flatMap(this::process, 4);
}
```

`onBackpressureBuffer` 不是越大越好。大缓冲只会把压力转成堆内存；实时日志、SSE、文件流和消息消费要分别决定丢弃、限速、暂停读取或持久化。对用户可见的数据流，要给出断线重连、最后事件 ID 和重复事件处理策略。

## WebFlux 不能安全调用阻塞代码

以下调用会阻塞事件循环或响应式线程：

- JDBC/MyBatis/JPA 同步数据库访问。
- `RestTemplate`、同步 Feign、阻塞式 SDK。
- `Files.readAllBytes`、传统压缩和大文件同步计算。
- 任何等待锁、`Thread.sleep` 或同步队列获取。

如果暂时无法替换阻塞依赖，可以隔离到弹性线程池，但这只是迁移措施：

```java
public Mono<ProductView> findWithLegacyDao(String id) {
    return Mono.fromCallable(() -> legacyDao.find(id))
            // 将阻塞调用移出事件循环；线程池仍需有界并监控队列。
            .subscribeOn(Schedulers.boundedElastic())
            // 将 null 转为明确的空结果，而不是让响应式链出现空值。
            .flatMap(value -> value == null ? Mono.empty() : Mono.just(value));
}
```

更长期的方案是使用 R2DBC、响应式 Redis、响应式 HTTP 客户端和非阻塞文件/消息库，并逐层验证驱动是否真的非阻塞。不要把所有代码包进 `boundedElastic`，否则只是把阻塞线程池搬到另一个位置。

## MVC 与 WebFlux 如何选择

| 场景 | MVC（Servlet） | WebFlux（响应式） |
|---|---|---|
| 团队主要使用 JDBC/MyBatis | 简单、成熟、调试直观 | 需要隔离阻塞调用，复杂度更高 |
| 大量同步业务规则 | 适合 | 需要谨慎切换线程 |
| 长连接/SSE/流式响应 | 可以支持，但线程模型需设计 | 数据流表达更自然 |
| 高并发 I/O、连接数多 | 依赖线程池和连接池调优 | 事件循环+非阻塞驱动更有优势 |
| 生态和排错经验 | 更广 | 需要理解 Reactor、背压和取消 |
| 迁移成本 | 低 | 需要端到端响应式，不能只改 Controller 返回类型 |

不要用“响应式一定更快”作为选型理由。先测量瓶颈：如果主要时间在 CPU、慢 SQL 或外部服务，改 WebFlux 不能消除根因。一个项目可以在边缘流式接口使用 WebFlux，但仍要隔离阻塞的业务模块，边界必须清楚。

## 错误、超时、取消与重试

```java
public Mono<PaymentStatus> queryPayment(String orderId) {
    return paymentClient.query(orderId)
            // 远程服务必须有有限超时。
            .timeout(Duration.ofSeconds(3))
            // 只对明确可重试的网络错误重试，次数很少并带退避。
            .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                    .filter(this::isTransientNetworkError))
            // 最终失败转换为稳定业务错误；不要把异常堆栈返回前端。
            .onErrorMap(TimeoutException.class,
                    ex -> new DownstreamTimeoutException("支付查询超时", ex));
}
```

取消是响应式的重要信号：用户关闭页面、浏览器取消请求或下游超时后，上游应该停止昂贵工作。使用 `doFinally` 记录完成、错误和取消三种结局；不要只在 `doOnSuccess` 中释放资源。

## WebTestClient 测试

```java
// 集成片段：需要 spring-boot-starter-test 和 WebFlux 测试支持。
@WebFluxTest(ProductReactiveController.class)
class ProductReactiveControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ProductReactiveService service;

    @Test
    void returnsProduct() {
        // 返回一个确定的 Mono，测试只关注 HTTP 和响应式序列化。
        given(service.find("p-1"))
                .willReturn(Mono.just(new ProductView("p-1", "键盘")));

        webTestClient.get()
                .uri("/api/products/p-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("p-1");
    }
}
```

测试流式接口时要验证：事件顺序、完成/错误信号、超时、客户端取消和重复订阅。不要在测试里无限等待 `block()`；如果必须阻塞读取，设置明确 timeout，并说明这是测试边界而非生产代码。

## 响应式事务和上下文

响应式事务通常通过 Reactor Context 传播，而不是传统 ThreadLocal。SecurityContext、traceId、租户信息也可能通过 Context 或 WebFilter 传递。切换线程、调用阻塞库或手工 `subscribe` 会让上下文丢失。

```java
public Mono<OrderView> create(CreateOrderCommand command) {
    return currentUser()
            .flatMap(user -> orderRepository.insert(command, user.id()))
            // 同一个响应式事务中组合数据库写入；具体事务配置取决于 R2DBC 驱动。
            .as(transactionalOperator::transactional);
}
```

不要把传统 `@Transactional` 的线程绑定假设直接套到响应式链上；使用项目支持的响应式事务管理器，并对跨数据库、远程调用和消息发布重新设计一致性。

## 生产排错清单

| 现象 | 优先检查 | 常见根因 |
|---|---|---|
| CPU 不高但请求堆积 | event loop、连接池、pending 请求 | 阻塞 JDBC/HTTP、下游超时 |
| 内存持续增长 | backpressure、buffer、未取消订阅 | 无界缓存、慢客户端、Flux 未结束 |
| SSE 断开后仍在生成 | cancel 信号、资源释放日志 | 手工 subscribe、忽略取消 |
| 响应顺序错乱 | `flatMap` 并发与 `concatMap` | 需要顺序却使用了并发操作符 |
| 重试放大流量 | retry 指标和下游状态码 | 对非幂等操作无条件重试 |
| Trace/用户信息丢失 | Reactor Context 和线程切换 | 阻塞桥接、上下文未传播 |

## 学习练习

1. 用 `Mono` 实现详情查询，用 `switchIfEmpty` 返回 404 业务异常。
2. 用 `Flux` 实现 SSE，并在浏览器关闭连接后验证上游收到取消信号。
3. 把一个阻塞 DAO 调用移到 `boundedElastic`，记录线程名和队列指标，再评估替换为响应式驱动的成本。
4. 用 `flatMap` 和 `concatMap` 分别处理批量远程查询，比较顺序、吞吐和下游压力。
5. 为一个 WebFlux 接口写 `WebTestClient` 测试，覆盖超时、错误、空结果和客户端取消。

下一步：[Spring MVC 与 Controller](./mvc.md)、[异步、事件、缓存与服务集成](./integration.md)、[可观测性与生产运行](./observability.md)。
