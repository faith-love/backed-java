# Spring Cloud 微服务：从 Gateway 到下游调用

微服务不是把一个项目机械拆成多个仓库，而是把独立部署、独立扩缩容和独立演进的业务边界通过网络连接起来。Spring Cloud 提供 Gateway、服务发现、配置中心、Feign、负载均衡、熔断和链路追踪等基础设施，但它不会自动解决数据一致性、服务权限、幂等和发布顺序。

## 从前端请求看完整链路

```text
浏览器 Axios
  -> Gateway：鉴权入口、路由、跨域、限流、路径改写
  -> 服务发现：根据 serviceId 找到可用实例
  -> LoadBalancer：选择实例
  -> OpenFeign/RestClient：发起下游 HTTP 请求
  -> 下游 Service：再次认证、授权和校验资源归属
  -> 数据库/缓存/消息
  -> 响应沿原链路返回
```

前端 Network 里看到的 `/api/orders/1` 可能经过了 Gateway 前缀、重写和多次内部调用。排错时要记录外部 URL、Gateway 路由、目标 serviceId、实例地址、traceId 和每一段耗时；不能只在浏览器里猜哪个 Controller 被调用。

## 服务拆分先解决什么问题

| 目标 | 适合微服务的情况 | 拆分后的代价 |
|---|---|---|
| 独立发布 | 订单、支付、用户需要不同发布节奏 | 版本兼容和回滚更复杂 |
| 独立扩容 | 报表查询远高于后台配置流量 | 网络调用和数据复制 |
| 团队边界 | 不同团队拥有清晰领域和接口 | 跨团队契约、排错和沟通 |
| 故障隔离 | 某个非核心模块故障不应拖垮主流程 | 超时、熔断和降级设计 |

不适合拆分的情况：两个模块必须共享同一个本地事务、调用频率低且没有独立演进需求、团队无法维护监控和发布链路。先划分业务边界，再决定服务数量。

## Spring Cloud Gateway：路由和入口治理

### 配置路由

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 对外暴露 /api/orders/**，转发给服务发现中的 order-service。
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            # 内部服务只接收 /orders/**，去掉外部的 /api 前缀。
            - StripPrefix=1
            # 限制单个用户/客户端的请求速率，具体限流器需接入 Redis 等共享存储。
            - name: RequestRateLimiter
              args:
                key-resolver: "#{@userKeyResolver}"
```

路由配置要明确：匹配顺序、Path/Host/Header 条件、前缀剥离、重写规则、超时、重试和响应头。Gateway 只应处理入口级能力；复杂业务权限和资源归属要在下游 Service 再校验一次。

### 全局过滤器与请求 ID

```java
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 优先复用已验证的请求 ID，否则生成新的短 ID。
        String requestId = Optional.ofNullable(
                        exchange.getRequest().getHeaders().getFirst("X-Request-Id"))
                .filter(value -> value.matches("[A-Za-z0-9_-]{1,64}"))
                .orElseGet(() -> UUID.randomUUID().toString());

        // 将 ID 写入下游请求和最终响应，便于前端和日志串联。
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-Request-Id", requestId)
                .build();
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        // 越早执行越容易覆盖完整入口链路；顺序要和认证过滤器一起验证。
        return -100;
    }
}
```

不要在 Gateway 过滤器里执行长时间数据库查询或文件处理。Gateway 通常运行在响应式事件循环上，阻塞调用会影响同一事件循环上的多个请求。

## Nacos：服务发现和配置中心是两件事

| 能力 | 解决的问题 | 关键配置/证据 |
|---|---|---|
| 服务发现 | 根据 serviceId 找到实例地址和健康状态 | namespace、group、service、实例心跳 |
| 配置中心 | 集中管理环境配置和动态刷新 | dataId、group、namespace、优先级、审计 |
| 命名空间 | 隔离环境/租户 | dev/test/prod 是否误连 |
| 分组 | 隔离同一命名空间内的服务配置 | group 名称和加载顺序 |

服务发现返回实例不代表实例一定能完成请求。还要检查实例 readiness、版本兼容、网络 ACL、连接超时和下游健康状态。配置中心动态刷新也可能让连接池、线程池和安全开关在运行中变化；生产配置应保留变更审计和回滚版本。

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:public}
        group: ${NACOS_GROUP:DEFAULT_GROUP}
      config:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        file-extension: yaml
        # 生产环境不要把用户名密码硬编码在仓库。
        username: ${NACOS_USERNAME:}
        password: ${NACOS_PASSWORD:}
```

排查“本地正常、部署后 Bean 不存在”时，先确认 Active Profile、Nacos namespace/group、dataId 和配置加载日志，再看 `@ConditionalOnProperty` 是否满足。不要先改代码或把生产地址写入默认配置。

## OpenFeign：远程接口的类型化声明

```java
@FeignClient(
        name = "user-service",
        path = "/api/users",
        fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    @GetMapping("/{id}")
    UserView find(@PathVariable("id") String id,
                  @RequestHeader("X-Request-Id") String requestId);
}

@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        // 降级必须记录下游名称、异常类型和 traceId，但不能把完整 Token 写日志。
        return (id, requestId) -> {
            throw new DownstreamUnavailableException("用户服务暂时不可用", cause);
        };
    }
}
```

Feign 接口像本地 Java 方法，但它会经历 DNS/服务发现、连接池、HTTP 序列化、认证、超时和错误解码。必须配置：

- 连接超时和读取超时，且小于上游请求总超时。
- 只对幂等请求或带幂等键的请求有限重试。
- 错误响应转换为稳定的领域异常，不把下游堆栈返回前端。
- 认证头、traceId 和租户信息的可信传递方式。
- 连接池、并发上限和指标。

## LoadBalancer：实例选择不是业务路由

`lb://order-service` 表示通过服务发现取得实例，再由 Spring Cloud LoadBalancer 选择目标。轮询、随机、权重和 zone 优先都只解决“选哪台实例”，不能替代用户权限、租户隔离或订单归属判断。

```java
// 集成片段：可通过配置或自定义 ReactorServiceInstanceLoadBalancer 调整实例选择。
@Configuration
public class OrderLoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> orderServiceLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory factory) {
        // 实际项目可按 zone、版本或灰度标签过滤实例；必须避免把不兼容版本混在一起。
        return new RoundRobinLoadBalancer(
                factory.getLazyProvider("order-service", ServiceInstanceListSupplier.class),
                environment.getProperty("loadbalancer.order-service.index", Integer.class, 0));
    }
}
```

灰度发布时，实例标签、请求 Header 和用户范围要有明确的可信来源。不能让浏览器随便提交 `X-Version: canary` 就获得内部版本；灰度选择仍需由 Gateway 或服务端策略控制。

## Resilience4j：超时、重试、熔断和限流

### 为什么必须有超时

没有超时，下游一个连接卡住就会长期占用上游线程、连接池和队列，最终形成级联故障。每一层 timeout 要小于调用它的上一层总预算：

```text
浏览器 10s
  -> Gateway 8s
    -> order-service 6s
      -> user-service 2s
      -> database 1s
```

### 注解示例

```java
@Service
public class OrderQueryService {

    private final UserClient userClient;

    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackUser")
    @Retry(name = "userService")
    @TimeLimiter(name = "userService")
    public CompletableFuture<UserView> loadUser(String userId, String requestId) {
        // Feign 默认是阻塞调用；实际使用 TimeLimiter 时要放进有界线程池或改用响应式客户端。
        return CompletableFuture.supplyAsync(
                () -> userClient.find(userId, requestId),
                userExecutor);
    }

    private CompletableFuture<UserView> fallbackUser(
            String userId, String requestId, Throwable cause) {
        // 降级结果不能伪装成“用户不存在”；前端应能区分暂时不可用和真实空数据。
        return CompletableFuture.failedFuture(
                new DownstreamUnavailableException("用户服务暂时不可用", cause));
    }
}
```

重试只适用于明确可重试的异常，且次数和退避必须有限。支付、扣库存、创建订单等非幂等操作不能因为 read timeout 就直接重复提交；应使用幂等键、查询最终状态或 Outbox。

| 机制 | 解决的问题 | 常见误区 |
|---|---|---|
| Timeout | 限制单次等待时间 | 只设置客户端，不设置连接池/网关/数据库 |
| Retry | 短暂网络故障 | 无条件重试非幂等写操作，造成流量放大 |
| CircuitBreaker | 下游持续失败时快速失败 | 熔断后返回不可信默认值 |
| Bulkhead | 隔离不同下游的并发 | 所有调用共享一个无界线程池 |
| RateLimiter | 限制调用速率 | 只限 Gateway，不保护内部下游 |
| TimeLimiter | 限制异步 Future 等待 | 阻塞调用仍占着线程没有真正取消 |

## 服务间鉴权：不能只相信 Gateway

Gateway 可以做入口认证，但每个服务仍应验证调用身份、服务身份和资源归属：

1. 用户 Token 是否由可信 issuer 签发、是否过期、scope 是否足够。
2. 服务到服务调用是否使用 mTLS、短期 service token 或受控网关凭证。
3. 下游是否只相信经过验证的 Header，不能接受浏览器直接伪造的 `X-User-Id`。
4. `orderId`、`reportId` 等资源是否属于当前用户/租户。
5. 日志和链路只记录主体摘要，不记录完整凭证。

```java
@PreAuthorize("hasAuthority('ORDER_READ')")
public OrderView getOrder(String orderId, Authentication authentication) {
    // 功能权限通过后仍然查询资源归属和租户数据范围。
    return orderRepository.findReadable(orderId, authentication.getName())
            .orElseThrow(() -> new NotFoundException("订单不存在"));
}
```

不要用“网关已经检查过了”替代下游授权。内部服务可能被旁路调用、误配路由或批处理任务直接访问。

## 分布式事务与 Outbox

跨服务调用不能依赖本地 `@Transactional` 自动回滚。例如订单服务写成功后，支付服务扣款失败，订单数据库事务无法回滚另一个服务的写入。

优先把流程设计成可补偿的状态机：

```text
订单 CREATED
  -> 记录 PAYMENT_REQUESTED 事件（同一数据库事务）
  -> 支付服务消费并幂等扣款
  -> 成功：ORDER_PAID
  -> 失败：ORDER_PAYMENT_FAILED / 自动补偿 / 人工处理
```

Outbox 表在订单本地事务中写入，发布器再发送到消息系统；消费者按 `eventId` 去重。Saga/补偿事务适合长流程，但每个反向操作都必须定义可执行、可重试和可观测的补偿语义。不要为了“强一致”把所有服务塞进一个长连接、长事务或同步调用链。

## 版本兼容与发布顺序

服务接口要遵守向后兼容：

- 新增 JSON 字段通常比删除/改类型安全。
- 枚举新增值要让旧客户端能容忍未知值。
- 分页结构、错误码和 HTTP 状态不能随意变更。
- 先发布能兼容旧字段的消费者，再发布生产者，最后清理旧字段。
- Gateway 路由、服务发现实例和配置中心变更要有回滚路径。

前端联调时用 Network 记录响应版本、traceId 和错误码；不要只根据页面是否显示判断兼容性。

## 微服务排错矩阵

| 现象 | 第一证据 | 继续检查 |
|---|---|---|
| Gateway 404 | 路由匹配日志 | Path 前缀、顺序、Rewrite/StripPrefix |
| Gateway 503 | serviceId、实例列表 | Nacos 注册、readiness、网络 ACL |
| Gateway 504 | 各段耗时 | Gateway/Feign/DB timeout、线程池和连接池 |
| Feign 401/403 | 下游响应与请求头摘要 | Token relay、scope、服务间凭证 |
| 调用重复写入 | requestId/idempotency key | 重试配置、客户端超时、幂等唯一索引 |
| 配置未生效 | 启动/刷新日志 | namespace、group、dataId、Profile、优先级 |
| 只有某版本失败 | 实例版本和灰度标签 | LoadBalancer 选择、契约兼容、配置差异 |
| 事件丢失/重复 | eventId、Outbox 状态 | 提交时机、发布确认、消费者去重 |

## 学习练习

1. 画出浏览器到 Gateway、订单服务、用户服务和数据库的调用链，并为每一跳标注 timeout。
2. 配置一个 `lb://` 路由，模拟服务实例未 ready，观察 Gateway 返回 503 的证据。
3. 为 Feign 查询增加有限重试和熔断，确保非幂等写接口不会无条件重试。
4. 给订单支付流程设计 Outbox 表、eventId 唯一约束和消费者幂等表。
5. 用 traceId 关联前端、Gateway 和下游日志，定位一次 P95 延迟上升。
6. 修改一个响应字段，按“先兼容消费者、再切换生产者、最后删除旧字段”的顺序发布。

下一步：[异步、事件、缓存与服务集成](./integration.md)、[可观测性与生产运行](./observability.md)、[认证与授权](./security.md)。
