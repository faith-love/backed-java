# Spring 可观测性：日志、健康检查、指标与链路追踪

可观测性解决的是“服务为什么慢、错在哪里、是否真的可用”。只看前端错误提示通常不够：需要把浏览器请求 ID、Gateway、Controller、数据库、远程服务和后台任务串成一条证据链。

## 三类信号

| 信号 | 回答的问题 | 典型工具/实现 |
|---|---|---|
| Logs 日志 | 某一次请求发生了什么 | SLF4J、Logback、结构化 JSON |
| Metrics 指标 | 一段时间内有多少、多久、是否饱和 | Micrometer、Prometheus、Grafana |
| Traces 链路 | 一个请求跨过哪些服务和数据库 | OpenTelemetry、Zipkin/Tempo/Jaeger |

三者要用同一个 traceId/requestId 关联。日志适合看单次细节，指标适合告警和趋势，链路适合定位跨服务延迟。

## Spring Boot Actuator：暴露运行状态

```yaml
# Spring Boot 集成配置示意；不要在生产环境无选择地暴露所有端点。
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized
```

常用端点：

- `/actuator/health`：应用健康摘要。
- `/actuator/health/liveness`：进程是否还能运行；不应因为一个可恢复下游暂时失败就重启。
- `/actuator/health/readiness`：是否准备接收流量；数据库、配置中心未就绪时可暂不接流量。
- `/actuator/metrics/{name}`：线程、HTTP 请求、连接池等指标。
- `/actuator/prometheus`：Prometheus 抓取格式，必须保护访问权限。
- `/actuator/loggers`：动态调整日志级别时要严格限制管理员权限。

健康检查的目的不是证明所有业务都成功，而是给编排系统一个稳定的生命周期信号。liveness 失败会触发重启，readiness 失败通常只摘流量；两者配置错误会造成重启风暴或把未就绪实例暴露给用户。

## 自定义 HealthIndicator

```java
// Spring 集成片段：只返回非敏感的状态，不把连接密码或完整异常返回给 HTTP。
@org.springframework.stereotype.Component
public class SearchHealthIndicator
        implements org.springframework.boot.actuate.health.HealthIndicator {

    private final SearchClient client;

    public SearchHealthIndicator(SearchClient client) {
        this.client = client;
    }

    @Override
    public org.springframework.boot.actuate.health.Health health() {
        try {
            // 使用轻量探测，不要在健康检查里执行全量查询。
            client.ping();
            return org.springframework.boot.actuate.health.Health.up().build();
        } catch (Exception exception) {
            // 详细异常写服务端日志，响应只返回稳定状态。
            return org.springframework.boot.actuate.health.Health.down().build();
        }
    }
}

interface SearchClient {
    void ping();
}
```

健康检查也会消耗资源。数据库探测频率过高、每次都建立新连接或同步调用多个下游，会反过来增加故障压力。

## 结构化日志和上下文

```java
@org.springframework.stereotype.Service
public class OrderLogService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderLogService.class);

    public void markPaid(String orderNo, String traceId) {
        // 使用固定字段和脱敏值，便于日志平台检索和聚合。
        log.info("order_paid orderNo={} traceId={}", orderNo, traceId);
    }
}
```

日志应包含事件名、资源 ID、结果、耗时、traceId 和错误分类。不要写密码、完整 Authorization、Cookie、支付签名、身份证号或完整请求体。异常日志保留堆栈，但对外响应只返回稳定错误码。

MDC 可以让同一线程中的日志自动带上 traceId，但异步线程不会天然继承 ThreadLocal；需要在执行器上配置 `TaskDecorator` 或使用框架提供的上下文传播。线程池复用线程时还要清理 MDC，防止上一个请求的 ID 泄漏到下一个请求。

## Micrometer 指标设计

```java
@org.springframework.stereotype.Component
public class OrderMetrics {

    private final io.micrometer.core.instrument.Counter paidCounter;

    public OrderMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        // 标签只能使用低基数值，例如状态和渠道，不能把 orderNo 当标签。
        this.paidCounter = registry.counter("orders.paid", "channel", "web");
    }

    public void recordPaid() {
        paidCounter.increment();
    }
}
```

指标标签不能使用用户 ID、订单号、URL 原始路径等高基数值，否则 Prometheus 时序数量会爆炸。HTTP 请求可按模板路径统计，例如 `/api/orders/{id}`，不要按每个具体 ID 建立一条时序。

应重点观察：请求量、错误率、P95/P99 延迟、线程池队列、数据库连接池 active/idle/wait、缓存命中率、消息积压和 JVM 内存/GC。告警要有对应的排查入口，避免只报一个“服务异常”。

## 链路追踪的边界

链路追踪适合定位“请求在哪一跳变慢”，但采样和数据脱敏必须受控：

```text
浏览器 requestId
  -> Gateway span
  -> Controller span
  -> Service span
  -> SQL/Redis/Feign client span
  -> 下游服务 span
```

如果前端传入 requestId，服务端应校验长度和字符集并生成内部 traceId；不能允许调用方伪造任意敏感字段。跨服务调用要传播 trace header，但不要传播完整 Token 到日志或业务消息。

## 慢请求和错误排查顺序

1. 浏览器 Network：URL、状态码、总耗时、requestId。
2. Gateway：路由、限流、重试、上游耗时。
3. 应用日志：Controller 进入/退出、traceId、业务错误码。
4. 指标：HTTP 延迟分位数、线程池、连接池和 GC。
5. 链路：数据库、Redis、Feign、消息系统各 span 的耗时。
6. 数据库：慢 SQL、执行计划、锁等待和连接数。

不要只把超时时间调大。超时可能是连接池排队、数据库锁、下游重试风暴或线程池饥饿；调大超时会让更多请求长期占用资源。

## 安全与暴露边界

- Actuator 端点单独鉴权，生产只暴露需要的端点。
- `/actuator/env`、`/configprops` 等端点可能包含敏感配置，除非明确保护，不要暴露给公网。
- Prometheus 指标不应包含用户数据、Token 或订单号。
- 日志平台、Trace 后端和错误上报服务本身也需要权限、保留期和脱敏策略。
- 错误响应给前端稳定 `code/message`，详细堆栈只留服务端并关联 traceId。

## 前端开发者的联调清单

1. Axios 在错误提示中显示 `requestId/traceId`，让用户可以把它提供给支持人员。
2. Network 面板记录状态码和耗时，区分 401、403、409、429、5xx 和网络超时。
3. 前端重试只针对明确可重试的 GET 或幂等请求，避免把 POST 重复提交。
4. SSE/WebSocket/文件下载等长连接要单独监控连接数、断开原因和心跳。
5. 页面显示“请求超时”时，不要直接判断数据库失败；先根据 trace 定位实际超时层。

## 练习

1. 给一个 Controller 增加 requestId 日志，验证它能贯穿 Service 和 Mapper 日志。
2. 为数据库连接池、HTTP P95 和错误率配置三条指标，并写出各自的排查动作。
3. 将健康检查拆成 liveness/readiness，模拟数据库不可用时观察实例是否应该重启。
4. 找一个日志调用，检查是否打印了 Token、Cookie、完整请求体或高基数标签。

下一步：[Spring 集成能力](./integration.md)、[Spring 测试](./testing.md)、[Spring Boot 配置](./boot.md)。
