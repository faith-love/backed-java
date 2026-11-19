# 网络库：从 Socket 到 HTTP 客户端与长连接

前端的 `fetch()` 隐藏了 DNS、连接建立、TLS、HTTP 编解码和连接复用。Java 后端同样需要这些能力，但它还经常同时作为 **HTTP 服务端和 HTTP 客户端**：浏览器请求你的 Controller，你的 Service 再请求支付平台、对象存储或另一个微服务。读代码时先确认当前是哪一跳，再确定网络库承担什么职责。

建议先看 [I/O 与资源管理](./io.md)、[NIO 的 Buffer/Channel/Selector](./nio.md)。大文件场景继续读 [分片上传实战](../../examples/large-file-upload.md)；连接压力和执行资源则结合 [线程池](./thread-pools.md)。

## 先按抽象层分组，再选库

| 层次/工具 | 解决什么问题 | 典型场景 | 需要自己承担的部分 |
|---|---|---|---|
| `Socket` / `ServerSocket` | 建立 TCP 字节流、接收连接 | 学 TCP、接入既有私有设备协议 | 消息边界、超时、连接数、TLS、协议安全 |
| `DatagramSocket` | 收发 UDP 数据报 | 实验、局域网发现、允许丢包的遥测 | 丢包、乱序、重复、重试与拥塞控制 |
| `SocketChannel` / `Selector` | 用就绪事件管理多条非阻塞连接 | 学事件循环、实现底层连接模型 | 缓冲区状态、半包、写队列、背压、连接生命周期 |
| Netty | 封装事件循环、连接、缓冲区和协议处理链 | 自定义 TCP 协议、网关、通信基础设施 | 业务协议、资源容量、安全、业务可靠性 |
| `HttpURLConnection` | JDK 较早提供的 HTTP 调用接口 | 阅读旧代码、维护已有集成 | 手动配置方法/头/正文，处理错误流、超时与释放 |
| JDK `HttpClient` | Java 11 起内置的 HTTP 客户端 | 通用外部 API、减少第三方依赖 | 状态处理、业务重试、响应上限、客户端配置 |
| Apache HttpClient | HTTP 调用、连接管理与丰富配置 | 需要精细连接池和认证配置的工程 | 选择版本、同步/异步实现、生命周期与超时 |
| OkHttp | 复用连接的 HTTP 客户端、拦截器、同步/异步调用 | 服务端调用 API、已有 OkHttp/存储 SDK 的项目 | 关闭响应体、容量、重试副作用、业务错误 |
| `RestTemplate` | Spring 同步 HTTP 调用与消息转换 | 阅读、维护现有 Spring MVC 工程 | 底层 Client 和连接池配置、同步线程占用 |
| `WebClient` | Spring 响应式 HTTP API，常用 Reactor Netty 作传输层 | SSE 消费、流式响应、响应式链路 | 订阅/取消、背压、缓冲上限、避免阻塞事件循环 |
| Spring Cloud OpenFeign | 用 Java 接口描述 HTTP 服务调用 | 服务间 DTO 调用、统一契约 | 实际传输 Client、发现/负载均衡、超时、幂等 |

它们不是互斥的所有选项。例如 `WebClient → Reactor Netty → Netty → NIO` 是一条可能的实现关系；`OpenFeign → OkHttp/Apache/JDK 等具体 Client` 是另一种关系，实际选哪一个由依赖和配置决定。**声明式接口不等于非阻塞，使用 Netty 也不等于已经有完整 Web 应用框架。**

## Socket：TCP 给你一条字节流，不给你“业务消息”

### Socket 与 ServerSocket 分别做什么

- `ServerSocket.bind`：服务端监听一个地址与端口；`accept()` 等待并返回一条已建立连接的 `Socket`。
- `Socket.connect`：客户端连接远端。后续通过输入流/输出流读取与写入字节。
- 阻塞模式的 `accept`、`read` 和某些 `write` 都可能等待。使用一个线程顺序处理所有连接，会让慢客户端挡住后面的请求。
- `Socket.setSoTimeout` 控制相应阻塞读取的超时；它不是所有连接、写入、业务处理的统一总时限。连接超时通过相应的 `connect` 参数设置。
- `shutdownOutput()` 表示本方向不再写入，和 `close()` 整体关闭的意义不同；协议必须约定什么时候发送、什么时候结束。

**场景：设备每隔几秒发送温度。** 一次 `write()` 发送一条 JSON，不保证接收端一次 `read()` 恰好得到这一条 JSON。一次读取可能只有一半，也可能含有连续多条消息。可以使用“长度头 + 消息体”、有转义规则的分隔符、或固定长度协议；都要限制单条消息最大长度，禁止根据不可信长度头直接分配巨大数组。

TCP 会在连接语义内提供可靠、有序的字节传输，但业务请求可能已执行、响应却丢失。再次连接重发仍可能重复扣款或创建订单；业务幂等不能交给 TCP。

### UDP / DatagramSocket 的不同点

UDP 保留数据报边界，但不保证一定到达、按顺序到达或只到达一次。接收缓冲区过小时可能截断报文；应用不能把超大的业务文件简单塞进一个 UDP 数据报。大数据报还会面临 IP 分片和更高丢失成本。

适合：协议本身允许丢失的最新状态、受控网络中的发现机制，或者由成熟协议实现可靠性/拥塞控制。一般文件上传和支付 API 直接使用成熟的 HTTP/TLS 方案，不建议自行拼一套“可靠 UDP”。QUIC 使用 UDP，但可靠传输、加密、流控制是 QUIC 协议提供的，不是 UDP 自动具备的。

## HttpURLConnection：看懂旧代码的连接与流

老工程中常见 `url.openConnection()` 转成 `HttpURLConnection`，设置请求方法、连接/读取超时和请求头，然后写请求体、读取响应。它是偏同步的 API，不因为类名带 `Connection` 就等于数据库连接，也不能仅靠创建它判断网络请求已经完成。

维护时重点检查：

- `setConnectTimeout` 和 `setReadTimeout` 分别约束相关阶段，不等于整个业务有统一截止时间；不要留下无限等待默认值。
- 先配置方法、请求头与输出模式，再开始写入/读取；写请求体后要正确结束输出，服务端才能知道请求体已经完整。
- 检查 `getResponseCode()`；错误状态读取错误流时要允许 `getErrorStream()` 为 null，不把空错误体误判为连接成功或不存在错误。
- 输入流、输出流要关闭；必要时在 `finally` 调用 `disconnect()` 释放该连接对象关联的资源。连接底层是否复用仍与 JDK 实现、是否正确处理正文等有关，不能把对象生命周期直接当作 TCP 生命周期。
- 流式请求模式、重定向、认证、请求体重放之间存在约束；维护既有行为时按当前 JDK 文档核实。

**场景：旧支付集成或内部工具。** 先补齐状态检查、超时、资源关闭，避免为了换库改变请求签名/编码。Java 11 及以上的新通用 HTTP 代码可优先评估 `HttpClient`，已有成熟客户端配置的工程则沿用团队方案，不必在同一个服务里再引入第四套客户端。

## JDK HttpClient：先学清楚一份请求的生命周期

### 常见对象及职责

| API | 职责 | 使用细节 |
|---|---|---|
| `HttpClient` | 可复用的客户端，保存连接与协议配置 | 通常按信任域、代理等配置共享；每请求创建一个会损失复用收益 |
| `HttpRequest` | URI、方法、请求头、正文发布器、请求超时 | 不把私钥、Token 放 URL；不要用调用方传来的任意 URL 直接请求内网 |
| `send` | 同步等待 HTTP 响应 | 调用线程会等待；HTTP 4xx/5xx 仍是正常收到的响应 |
| `sendAsync` | 返回 `CompletableFuture`，稍后获取响应 | 异步不会自动限制同时发出的请求数；要处理异常与取消 |
| `BodyPublishers.ofString` | 发布小型文本请求 | 字符串已经在内存中，不适合把大型文件先转成字符串 |
| `BodyPublishers.ofFile` | 从文件发布请求体 | 底层文件在传输期间必须存在且保持内容稳定；自动重试需确认能否重放 |
| `BodyHandlers.ofString/ofByteArray` | 聚合响应体后返回 | 正文大小越大，占用内存越多；不能只相信远端声称的大小 |
| `BodyHandlers.ofInputStream` | 交给应用逐块读取正文 | 应用必须读完或关闭；得到响应对象并不表示正文全部到达 |
| `BodyHandlers.ofFile` | 将正文写入文件 | 自己管理临时文件、失败清理、大小限制和完成后的原子发布 |

JDK 11 的 `HttpClient` 没有 `AutoCloseable` 生命周期接口；不要把较新 JDK 的客户端关闭代码直接复制到 Java 11。响应流仍必须按实际 API 关闭。本章的运行示例使用 JDK 17 编译成 Java 11 兼容字节码，所用 API 以 Java 11 为边界。

### 完整本机例：正确处理中文、404 与超大响应

这份程序在 `127.0.0.1` 随机端口启动临时 HTTP 服务，再用一个客户端发出四次请求。它检查两次中文正文、一条 404 和一条超限响应；正文以流读取，最大 1 KiB。所有端口和线程池在程序结束时清理，不需要账户、密钥或互联网。

<a href="/examples/io/HttpClientDemo.java" download>下载 HttpClientDemo.java</a>

~~~powershell
# 在保存源码的练习目录执行；JDK 17 编译，检查仅使用 Java 11 可用 API。
javac -encoding UTF-8 --release 11 HttpClientDemo.java
# 引号避免 PowerShell 错误拆分 JVM 的 -D 参数。
java '-Dfile.encoding=UTF-8' HttpClientDemo
~~~

预期包含 `PASS`、`requests=4 rejected=2`。本次以 JDK 17 的 `--release 11` 编译通过，并在下述本机临时路径调整后运行通过。程序验证了本机请求、响应关闭、状态/实际长度检查；没有抓包验证连接复用，也没有验证生产负载、TLS 或第三方服务。

::: tip Windows 上的本机运行说明
本机默认 TEMP 配置下，JDK 在建立内部 Selector 管道时出现 `Unable to establish loopback connection`，其原因链包含 `UnixDomainSockets.connect` 的 `Invalid argument`，发生在 HTTP 业务处理之前。仅为该进程指定已存在、可写、路径较短的临时目录后，示例运行通过；没有更改全局环境或防火墙。

若遇到相同错误，可在源码所在的简短练习目录执行下面的命令；`$PWD.Path` 表示当前目录。不是所有网络错误都适用这个处理方式，正常运行时无需额外参数。

~~~powershell
# 只调整这次 JVM 进程的 Unix-domain socket 临时文件目录；当前目录需可写且路径较短。
java '-Dfile.encoding=UTF-8' "-Djdk.net.unixdomain.tmpdir=$($PWD.Path)" HttpClientDemo
~~~
:::

::: details 展开完整中文注释源码
<<< ../../public/examples/io/HttpClientDemo.java{java}
:::

其中 `Future.get(2, SECONDS)` 限制正文读取的等待，超时后取消读取并关闭 HTTP 响应流。`cancel(true)` 本身只是中断请求；它不保证任意阻塞 API 都立即停止。本例用独立读取线程解释超时边界，生产高并发服务应按选定客户端提供的读取/调用超时与取消机制设计，不应机械地给每个响应再开一条独立线程。

## Apache HttpClient：需要精细连接池时怎么理解

Apache 同时提供不同代际、不同执行模型的客户端。HttpClient 4.x 常见 `org.apache.http.*` 包；HttpClient 5.x 常见 `org.apache.hc.*` 包。不要把 4.x 的连接管理器、5.x 的配置类和另一套 Spring RequestFactory 混用；项目依赖应固定兼容组合。

重点看四类配置：

1. **连接数量**：全局最大连接数、每目标路由的连接数。HTTP/2 还能在单连接中复用多个流，连接数并不总等于并发请求数。
2. **等待连接的时限**：连接池已满时请求排队多久。这个等待发生在“建立新连接”之前，只设置 connect timeout 不够。
3. **连接、读响应与总预算**：各版本命名和覆盖范围不同，按当前文档核实。
4. **空闲/过期连接回收**：处理连接闲置、服务端提前断开以及资源释放；关闭应用时关闭自己拥有的 Client/连接管理器。

**场景：一个服务同时调用十个供应商 API。** 每个供应商应有合理容量和超时。不能让一个变慢的供应商占满所有连接或工作线程；可按下游隔离客户端/连接池/并发额度。维护同步 Servlet 项目时可使用 Classic 客户端；需要异步流处理时再评估对应 Async 客户端，不能根据库名判断执行模型。

## OkHttp：共享 Client，关闭 Response，明确业务重试

OkHttp 提供连接复用、HTTP/2、拦截器和同步/异步调用。`execute()` 阻塞调用线程；`enqueue()` 通过回调交付结果。回调里的耗时计算、文件处理仍需考虑执行线程，异步请求也要给总并发和每主机并发设置边界。

下面是 **OkHttp 3/4 常见 Java API 的集成片段**，不是本页编译运行的示例。依赖版本、代理和容量需要结合工程配置；`trustedApiUrl` 必须来自服务端允许列表。正文交给受控下载器处理，不能在下载大文件时直接 `.string()` / `.bytes()`。

~~~java
// 常见 OkHttp Java API；需要项目提供 okhttp3 依赖和相应 import。
// 客户端作为受管对象共享，不在每个 Controller 方法中重复创建。
OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS) // 新建连接的等待预算。
        .readTimeout(5, TimeUnit.SECONDS)    // 读取操作相关时限，不等于完整业务截止时间。
        .writeTimeout(5, TimeUnit.SECONDS)   // 请求体写入相关时限。
        .callTimeout(15, TimeUnit.SECONDS)   // 所选版本支持的单次调用总预算。
        .followRedirects(false)              // 如需重定向，逐跳核实目的地址。
        .build();

Request request = new Request.Builder().url(trustedApiUrl).get().build();
try (Response response = client.newCall(request).execute()) {
    // 收到 404/500 不代表网络传输失败，必须单独检查 HTTP 语义。
    if (!response.isSuccessful()) {
        throw new IOException("下游 HTTP 状态异常：" + response.code());
    }
    ResponseBody body = response.body();
    if (body == null) {
        throw new IOException("缺少期望的响应正文");
    }
    // saveWithLimit 是待实现的业务下载器：逐块写临时文件、累计实际字节上限，
    // 完整成功后才发布最终文件；不可直接将整个正文读入 byte[]。
    saveWithLimit(body.byteStream(), temporaryPath, maximumBytes);
} // 无论下载成功还是抛异常，都关闭 response/body，释放相应网络资源。
~~~

连接失败时库可能有连接层恢复行为，但它不了解“创建发票”是否可重复。业务层不能因为使用了成熟客户端就无条件重试所有 POST。具体自动恢复、请求体重放和重试行为必须按所固定版本确认。

## Spring 的三种常见入口：RestTemplate、WebClient、Feign

### RestTemplate：同步业务调用容易理解

`RestTemplate` 把 URL、请求体和返回 DTO 的转换封装成方法调用，适合已有 Spring MVC 代码。它的同步 API 会占住当前调用线程，底层可以选择不同 HTTP 实现。只创建一个 `RestTemplate` 对象不代表已经正确配置了连接池、连接获取等待和读超时。

本项目父 POM 使用 Spring Boot **2.7.18**，对应 Spring Framework 5.3 系列；在这个背景下阅读 `RestTemplate` 很常见，不应套用后续版本的状态说明。Spring Framework 6.1 引入的 `RestClient` 是另一种同步 API，不是 Boot 2.7 默认就能使用的类。新工程选型应按当前实际版本文档判断，不能只凭网上“某类已过时”的一句话迁移。

**场景：请求线程查询第三方地址，再返回一个小 DTO。** 同步调用便于维护，但需限制下游调用时限与入口并发。如果每个请求需要等待十几个独立下游，可考虑受控并发编排；要先量化瓶颈，而不是单纯换名字。

### WebClient：流式响应和响应式组合

`WebClient` 返回 `Mono`（零或一个结果）与 `Flux`（零到多个结果）。常见传输实现是 Reactor Netty，但它支持的连接器和配置应以当前版本为准。链式表达式通常在订阅后执行；Spring WebFlux Controller 返回 Publisher 时，由框架负责订阅，不要再在 Controller 内手工 `subscribe()` 后马上返回“成功”。

下面是 **Spring 5.3 WebClient 集成片段**，未在知识库编译或联调。`trustedBaseUrl` 是固定业务服务地址；连接池、连接建立、响应读取的超时还需配置在实际 Connector 中。

~~~java
// Builder 在应用配置中创建并共享；maxInMemorySize 限制相关 codec 聚合缓冲。
// 它不是所有传输方式、原始 DataBuffer、文件流的通用总字节限制。
WebClient client = WebClient.builder()
        .baseUrl(trustedBaseUrl)
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(256 * 1024))
        .build();

// 不在这里 block()；WebFlux 调用方继续组合此 Mono，由框架订阅和取消。
Mono<OrderView> result = client.get()
        .uri("/orders/{id}", orderId) // 将变量按 URI 模板处理，不手工拼接不可信路径。
        .retrieve()                 // 默认会把常见 4xx/5xx 转换为响应异常。
        .bodyToMono(OrderView.class)
        .timeout(Duration.ofSeconds(5)); // 对这一结果链设等待预算；不是下游事务回滚。
~~~

`block()` 把响应式等待变回阻塞等待，尤其不能放在 Netty 事件循环上。调用 JDBC、阻塞文件流、传统 SDK 时，需要明确转移到合适的有界执行资源，或者保持整条业务使用同步模型。不能认为在 `map()` 中调用 JDBC 会自动变成非阻塞数据库访问。

**场景：消费大模型 SSE 或持续增量结果。** 使用 `Flux` 按事件处理，避免 `collectList()` 把整个流全部攒进内存。背压限制处理需求，但只有链路各环节都尊重它才有效；远端推送、代理缓冲、某些解码器仍可能堆积。自己接触引用计数 `DataBuffer` 时，必须按对应 API 契约释放，避免堆外内存泄漏。

### OpenFeign：接口描述不等于本地调用

以下是 **Spring Cloud OpenFeign 教学片段**，不是已运行接口。调用方仍需网络、服务发现、认证和失败处理。

~~~java
// name 是逻辑服务名；实际目标与负载均衡取决于工程依赖和配置。
@FeignClient(name = "inventory-service", contextId = "inventoryReader")
public interface InventoryClient {
    // 方法参数变成 HTTP 路径；DTO 不要暴露数据库实体的所有内部字段。
    @GetMapping("/inventory/{skuId}")
    InventoryView getInventory(@PathVariable("skuId") String skuId);
}
~~~

你写的是 Java 方法，发生的是一次远程调用：可能超时、返回 404、响应格式变化，也可能请求已执行而响应没到。Spring Cloud 体系里的服务发现和 LoadBalancer 可以参与选择实例；Feign 自身不凭空知道注册中心，实际 HTTP 传输也不是由注解名称决定。

如果配置 fallback，不能让“查库存失败”假装成“库存为零”或“扣减成功”。失败、空结果、明确业务状态应该在契约里区分。重试策略也要按 Spring Cloud/OpenFeign 的实际配置检查，不能假设所有 Feign 都使用相同默认策略。

## Netty：什么时候值得下到这一层

Netty 将字节收发、协议编码解码和业务处理放进 `ChannelPipeline`。`EventLoop` 管理连接事件，一个事件循环通常服务多条连接；`ByteBuf` 管理字节缓冲；Handler 在相应事件到达时处理连接、读取、写入与异常。

| 机制 | 解决的问题 | 常见陷阱 |
|---|---|---|
| `EventLoop` | 用少量线程复用连接等待 | Handler 里阻塞数据库/睡眠/大计算，拖慢同循环的所有连接 |
| Decoder / Encoder | 把字节变成有边界的协议消息 | 不设最大帧长度，半包累计造成内存上涨 |
| `ByteBuf` | 管理堆内/堆外缓冲区，支持引用计数 | 所有权转移不清晰，提前释放或未释放 |
| 写缓冲水位 / `isWritable` | 告知写入侧下游是否积压 | 忽略不可写状态，应用无限排队；水位信号不是硬内存上限 |
| Idle/timeout 处理 | 识别连接长期无读写等情况 | 心跳正常不代表业务请求成功，超时状态仍要设计 |
| Handler 执行资源隔离 | 避免慢业务占用事件循环 | 转线程后仍无队列上限，或破坏同连接消息顺序 |

**适合**：需要自定义二进制协议、设备长连接、复杂网络代理等；团队愿意维护协议与资源生命周期。**通常先用成熟框架**：普通 CRUD HTTP 服务、支付 SDK、标准文件上传。自行写 Netty HTTP 服务并不会自动得到 MVC 参数绑定、鉴权、事务、multipart 限制或上传断点恢复。

## 同步、异步、响应式：分别改变了什么

| 写法/模型 | 当前调用方怎样等 | 仍然存在的工作与资源 | 例子 |
|---|---|---|---|
| 同步 HTTP | 等响应完成再继续 | 等待线程、连接、响应缓冲 | `HttpClient.send`、OkHttp `execute` |
| 异步 HTTP | 先拿 Future/注册回调 | I/O 处理线程、回调线程、未完成请求与缓冲 | `sendAsync`、OkHttp `enqueue` |
| 响应式组合 | 通过 Publisher 传播结果/需求/取消 | 事件循环、调度器、上游缓冲、连接池 | `WebClient` + `Mono/Flux` |
| 把阻塞调用提交线程池 | 当前线程不等，由工作线程等待 | 每个在途阻塞任务仍占一个工作线程 | `supplyAsync(() -> blockingCall(), pool)` |

异步适合多个独立 I/O 等待的组合，但要控制在途任务数。对 10,000 个文件执行 `Promise.all` 或 `CompletableFuture.allOf`，并不自动出现并发限制；实际上请求可能已经全部发出。分片上传应按固定数量的工作者领取任务，详见[上传专题](../../examples/large-file-upload.md)。

## 超时：必须知道到底在等什么

| 阶段 | 可能等待什么 | 配置与排查重点 |
|---|---|---|
| 业务排队 | 入口、工作线程池或限流许可 | 排队长度、排队截止时间、拒绝策略 |
| 连接池获取 | 没有可用连接/HTTP 流额度 | 获取连接时限、每目标并发、连接是否归还 |
| 名称解析 | DNS 迟迟无结果 | 解析器和环境；不要假设 connect timeout 覆盖所有解析时间 |
| TCP 建连 | 网络不可达、远端不接收 | connect timeout、目标地址、代理链路 |
| TLS 握手 | 证书/协议/握手等待 | TLS 配置、握手时限；不要通过关闭证书校验修复 |
| 上传请求体 | 下游接收慢、网络上传慢 | write timeout、总预算、可重放请求体 |
| 首字节/读取正文 | 下游计算慢、持续慢速发送 | response/read timeout 与完整请求截止时间 |
| 业务全流程 | 多次查询、重试、结果转换 | 总 deadline，后续阶段使用剩余预算 |

“5 秒读超时”可能表示两次读取之间最多等 5 秒，并不一定表示整个 5 GB 文件必须在 5 秒内读完。JDK `BodyHandlers.ofInputStream()` 又把正文读取交给应用；请求对象上的时限不能被当成应用读取/处理流的完整截止时间。API 名称相似时也要查看当前库、当前版本的语义。

一条请求重试三次，每次 5 秒，还可能加上三次连接池排队和退避。应从用户/业务能接受的总时间向各环节分配预算，不能让内层每重试一次都重新获得完整的最外层时间。

## 重试与取消：客户端失败不等于服务端没执行

典型过程：后端成功保存上传分片，回包时网络断开，浏览器认为上传失败。如果按同一个 `uploadId + partIndex` 重试，后端应核对长度与摘要后返回原结果；如果每次重试都当作新业务，很容易产生重复文件、重复订单。

- **可重试条件**：明确是临时网络失败、服务端允许的 429/503 等，并有重试次数、退避、抖动与总预算。按响应规则处理 `Retry-After`。
- **不可盲目重试**：认证失败、参数错误、业务冲突、不可重放的请求体，以及没有幂等约束的付款/创建动作。
- **方法名字只是信号**：GET 应没有写入副作用；PUT 的幂等语义也需要服务端正确实现，不能只换 HTTP 方法。
- **取消只是请求**：关闭客户端等待或断开网络，不保证远端停止写库、停止处理或回滚。取消上传会话应有独立服务端状态和清理流程。
- **超时结果未知**：查询原请求的业务结果，再决定下一步。不能把“没有收到成功响应”直接解释成“必定失败”。

## WebSocket、SSE 与普通 HTTP 怎么选

| 方式 | 通信方向 | 解决的问题 | 具体使用场景与代价 |
|---|---|---|---|
| 普通 HTTP / 轮询 | 客户端发起，服务端响应 | 简单请求与可恢复查询 | 文件分片 PUT、查上传状态；频繁轮询会增加请求量 |
| SSE | 一条 HTTP 连接中服务端持续推送文本事件 | 按事件展示进度/增量内容 | 转码/合并进度、大模型文本；需心跳、代理缓冲处理、断线恢复 |
| WebSocket | 双向消息 | 低延迟双向交互 | 聊天、协作、设备控制；需连接认证、心跳、重连与流控 |

SSE 的事件 ID 与重连机制可以帮助恢复，但服务端必须真的支持事件保留和续读；浏览器重连并不保证事件不重不漏。原生 `EventSource` 的自定义请求头能力有限，认证要按同源 Cookie/可用客户端能力设计，不把长期 Token 随意拼进 URL。

WebSocket 消息也不是可靠业务队列。连接断了以后消息是否已处理，需要应用序号、ACK、幂等和恢复设计。文件传输可以走 WebSocket，但大文件通常先选 HTTP 分片或对象存储 multipart：更容易实现独立重试、校验、代理兼容与恢复；SSE/WebSocket 负责显示合并或转码进度即可。

## 大文件上传时，“流式”要检查完整链路

浏览器 `Blob.slice()` → HTTP 客户端/浏览器 → 反向代理 → 网关 → Servlet/WebFlux → 存储 SDK → 磁盘/对象存储，每一层都可能产生缓冲。

例如：Controller 调用 `MultipartFile.getInputStream()` 是流式读法，但 multipart 解析器可能早已把正文缓存到内存或临时磁盘；Nginx 也可能先缓冲完整请求；存储 SDK 还可能为了重试保留一个或多个分片。**某一行没有 `readAllBytes()`，不能证明整条链路的内存和磁盘都有界。**

选型建议：

1. 小附件、低并发：普通 multipart 上传，设定文件/请求大小、临时目录、超时与校验。
2. 大文件、弱网、需要暂停恢复：应用分片协议，统一管理会话、片号、长度、摘要与合并。
3. 对象存储已成熟：后端签发受限的预签名分片上传地址，浏览器直传；后端确认对象信息、完成状态与业务归属。
4. 只需显示后台进度：上传仍用 HTTP，额外使用轮询/SSE；不为显示进度重写整套传输协议。

预签名 URL 是有时效的授权凭证，需限定对象键、操作、有效期及产品支持的相关约束。它不是“没有权限验证”。对象存储的 ETag 也不能一概当作全文件 MD5，尤其是 multipart、加密与不同存储产品；校验算法要按产品契约明确。

## 目标地址、TLS 与日志的边界

- URL 由服务端配置或严格允许列表确定。代理抓取、URL 导入等功能要防止请求回环/内网/云元数据地址；除了初始 URL，还要处理 DNS 解析、重定向逐跳校验和网络出口限制。
- 不接受前端任意指定协议、代理、Host 或认证头去访问内部服务。禁止跟随重定向只是其中一步，不能单独解决全部 SSRF 风险。
- 使用客户端默认 TLS 证书链与主机名验证；证书错误应修复信任链、域名与时间，而不是写“信任所有证书”。本页本机 HTTP 仅为免配置实验。
- 日志记录目标服务、方法、状态、耗时、请求 ID、字节数、重试次数；脱敏认证信息和支付/签名 URL，不直接输出完整响应正文。

## 回到当前项目看哪些位置

以下仅按本次 checkout 的 POM 和 Java 调用点静态核对，不代表已启动服务或验证运行时连接池配置：

| 源码入口 | 已看到的事实 | 阅读时继续回答 |
|---|---|---|
| 根 `pom.xml` | Spring Boot 2.7.18、Spring Cloud 2021.0.8 | 当前依赖管理固定了什么版本？不要直接混入新主版本示例 |
| `gykj-dashboard/pom.xml` | 同时声明 Web、WebFlux 与 OkHttp 依赖 | 依赖存在不证明整个服务用响应式服务端；确认实际启动栈和调用链 |
| `gykj-dashboard/.../dataset/common/utils/HttpUtils.java` | 使用 `okhttp3`，创建 Client 并配置部分超时 | Client 是否复用？调用方是否关闭 Response？正文是否无界聚合？ |
| `gykj-dashboard/.../llm/LlmClient.java` | 使用 `WebClient` | 是否流式消费？错误和取消怎样传播？在哪里发生聚合或阻塞？ |
| `gykj-common-core/pom.xml` 与 `gykj-api/...` | 有 OpenFeign/LoadBalancer 依赖与 `@FeignClient` 接口 | 实际传输实现、重试/fallback 和认证头传递是什么？ |
| `gykj-gateway/pom.xml` | 声明 Spring Cloud Gateway | 网关路径上的过滤器是否阻塞？请求大小与超时怎样传递？ |

这里的缩略路径用于定位模块，可在仓库用类名搜索。阅读不等于缺陷结论：依赖数量、单处对象创建或某段超时设置，都需结合完整生命周期和实际调用方才能判断风险。

## 练习与排错顺序

1. 运行 `HttpClientDemo`，解释为什么 404 与“连不上端口”不是同一种错误。
2. 把响应限制从 1 KiB 改小，观察超限为什么必须明确失败，而不是返回前一半字符串。
3. 找项目里的一个 Feign 调用，标出 HTTP 方法、目标服务、DTO、超时、失败语义和是否允许重试。
4. 对“文件上传到 99% 卡住”，依次确认上传完成、服务端校验、合并、存储发布、数据库状态和前端轮询，不把上传字节完成等同于业务完成。
5. 对“接口偶发很慢”，分别记录排队、连接获取、DNS/连接、远端首字节、正文读取和业务解析，避免所有耗时都归因于网络。

## 参考文档

- [Java 11 HttpClient](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html)、[HttpRequest.Builder](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html)、[BodyHandlers](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html)。
- [Java 11 Socket](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/Socket.html)、[DatagramSocket](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/DatagramSocket.html)。
- [Apache HttpComponents](https://hc.apache.org/)、[OkHttp 文档](https://square.github.io/okhttp/)。
- [Spring Framework 5.3 WebClient](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/web-reactive.html#webflux-client)、[Spring Cloud OpenFeign](https://docs.spring.io/spring-cloud-openfeign/docs/current/reference/html/)。OpenFeign 链接会随文档版本变化，维护本项目时要选择与 Spring Cloud 2021.0.x 匹配的版本。
- [Netty 4.1 用户指南](https://netty.io/wiki/user-guide-for-4.x.html)。

下一步：[大文件上传与分片续传](../../examples/large-file-upload.md)。
