# Web 与 Spring：从一次 HTTP 请求读懂服务端

Spring 不是“替你写业务”的魔法库。它主要解决三类工程问题：**对象怎样创建和组装、横切能力怎样复用、HTTP 请求怎样稳定地进入业务边界**。Spring Boot 在 Spring 容器之上提供依赖组合、自动配置和启动约定。

这组章节面向已经熟悉 Vue、Axios 和 TypeScript 的前端开发者。你可以把浏览器的一次请求当作主线：先看谁接收请求，再看对象从哪里来、事务/日志在哪里插入、配置从哪里生效、用户身份怎样被确认。

## 知识点地图：先知道每一章在解决什么

不要把 Spring 只当成注解清单。阅读一段后端代码时，可以按下面的四层问题逐层定位：

| 层次 | 需要掌握的知识点 | 解决的问题 | 常见证据 |
|---|---|---|---|
| 对象层 | `Bean`、组件扫描、构造器注入、作用域、生命周期 | 谁创建对象、依赖从哪里来、资源什么时候关闭 | `@Component`、`@Bean`、构造器、启动日志 |
| 调用层 | 代理、切点、事务、过滤器、拦截器 | 为什么方法前后会自动执行逻辑，哪些调用会绕过增强 | `@Transactional`、`@Aspect`、Filter、`SecurityFilterChain` |
| Web 层 | 路径映射、参数绑定、校验、消息转换、异常处理 | HTTP 请求如何变成 Java 参数，失败如何变成响应 | `@GetMapping`、`@RequestBody`、`@Valid`、`@RestControllerAdvice` |
| Servlet 层 | Servlet API、Servlet 容器、Filter 链、请求线程与 Session | 同步 MVC 请求怎样进入 Spring，容器怎样分配线程和关闭资源 | Tomcat、`DispatcherServlet`、`HttpServletRequest`、Filter、Session |
| 运行层 | Boot 启动、配置来源、Profile、健康检查、依赖版本 | 为什么本地和部署环境行为不同，服务到底是否可用 | `application.yml`、环境变量、启动日志、Actuator |
| 数据层 | 数据源、Mapper、事务、分页、缓存和并发更新 | 请求为什么读错/写错、事务为什么没回滚、慢查询在哪里 | SQL、连接池、事务日志、`@Transactional`、缓存 key |
| 工程层 | 测试、异步、事件、远程调用、指标和优雅停机 | 如何验证修改、处理后台工作、定位生产故障 | `@WebMvcTest`、线程池、Outbox、Micrometer、readiness |
| 扩展层 | WebFlux 响应式编程和 Spring Cloud 微服务治理 | 何时使用非阻塞模型，如何处理路由、服务发现、熔断和跨服务调用 | `Mono`、`Flux`、Gateway、Nacos、Feign、Resilience4j |

这四层有先后关系：先确认对象确实由容器创建，再判断代理是否存在；先确认请求绑定成功，再讨论 Service 业务；先确认最终配置值，再判断连接池或下游地址。跳过前一层，后面的结论很容易变成猜测。

## 重难点分析：最容易“看起来懂了但实际会错”的地方

### 1. IoC、DI、Bean 和容器不是同一个词

IoC 是控制权发生转移的设计思想；DI 是把依赖传入对象的一种实现方式；Bean 是由 Spring 容器创建、组装和管理的对象；`ApplicationContext` 是承载这些定义和生命周期的运行时容器。构造器里写 `new` 仍然可以是普通 Java 的依赖注入，但它不自动拥有 Spring 的生命周期、代理和配置能力。

### 2. 注解出现不等于功能一定生效

`@Transactional`、权限注解和自定义切面通常依赖代理。必须继续确认：对象是不是 Spring Bean、调用方拿到的是不是代理、切点是否匹配、调用是否发生在同类 `this.method()` 内、异常是否按规则传播。把注解复制到一个自己 `new` 的对象上，可能完全没有增强。

### 3. Controller、Service、Mapper 的职责边界会影响排错

Controller 负责 HTTP 边界，Service 负责业务规则与事务编排，Mapper/Repository 负责数据访问。参数绑定失败应看 MVC 和 DTO；权限不足应看安全链和资源授权；事务回滚应看代理和数据库；SQL 慢应看持久层和连接池。看到异常时先判断它属于哪一层，再选择日志和代码入口。

### 4. 默认单例和前端模块级变量很像，但并发风险更高

默认 singleton Bean 会被多个请求线程共享。它适合保存不可变配置和线程安全客户端，不适合保存当前用户、当前分页条件、上一次查询结果或临时缓冲。前端开发中模块级变量已经可能引起页面互相污染；后端 singleton 的影响范围还会扩大到所有用户和所有请求。

### 5. 配置问题经常伪装成代码问题

同一个键可能同时来自本地配置、Profile 文件、环境变量、JVM 参数和配置中心。代码里看到的默认值不一定是进程的最终值。排查“连错数据库、端口不对、Bean 条件不成立”时，应先记录启动命令、Active Profile、配置中心命名空间和最终非敏感配置，再修改代码。

## 一条可复用的源码阅读方法

在深入 Spring MVC 前，建议先读 [Servlet 与 Servlet 容器](./servlet.md)，建立 Servlet API、Tomcat、Filter 和请求线程的基础。Spring MVC 的 `DispatcherServlet` 本身就是注册到 Servlet 容器中的 Servlet；理解这层关系后，再看参数绑定、拦截器和异步请求会更容易。

拿到一个前端接口时，按下面顺序做笔记，每一步都留下具体文件或日志证据：

1. 在浏览器 Network 记录最终 URL、HTTP 方法、Query、Body、请求头、状态码和响应形状。
2. 从应用或 Gateway 中确认外部路径是否被添加前缀、重写或转发到其他服务。
3. 找 Controller 的类级与方法级映射，确认参数注解和返回类型。
4. 找 Controller 的依赖，沿接口跳到 Service 实现；记录它是否是 Bean、是否有事务或权限代理。
5. 继续追 Mapper、Feign 或网络客户端，区分本地数据库错误、远程超时和业务错误。
6. 回到配置文件、环境变量和启动日志，验证端口、服务名、数据源和下游地址的实际来源。
7. 将“代码明确写了什么”和“当前环境可能发生什么”分开记录，避免把静态推断写成运行结论。

## 重难点排错矩阵

| 现象 | 第一证据 | 深一层检查 | 常见修复方向 |
|---|---|---|---|
| `NoSuchBeanDefinitionException` | 启动异常根因 | 扫描包、条件注解、模块依赖、Profile | 修正扫描/注册边界或补正确依赖 |
| `NoUniqueBeanDefinitionException` | 同接口候选 Bean 列表 | 是否意外引入多个实现、`@Primary`/`@Qualifier` | 明确默认实现或按场景命名选择 |
| 注解不生效 | 运行时调用对象类型 | 是否代理、是否自调用、切点是否匹配 | 调整 Bean 边界和调用路径 |
| 400 | Network 的请求体和响应错误 | 参数位置、Content-Type、DTO 校验 | 修正前后端契约或校验提示 |
| 401/403 | Security/Gateway 日志与响应 | 凭证提取、权限码、资源归属、数据范围 | 修正认证链或服务端授权条件 |
| 500 | traceId 对应的完整异常链 | Service、事务、数据库、下游超时 | 修复根因并保留稳定脱敏响应 |
| 本地正常、部署异常 | 启动参数和最终配置 | Profile、配置中心、网络/DNS、权限 | 修正环境配置，不先硬编码生产值 |

## 分阶段学习目标

### 基础阶段

- 能从 Controller 找到 Service 和 Mapper，并说出每一层的职责。
- 能解释构造器注入与 `new` 的区别，指出一个 Bean 是否可能被多个请求共享。
- 能根据 HTTP 方法和参数位置判断 `@PathVariable`、`@RequestParam`、`@RequestBody` 或 `MultipartFile`。

### 进阶阶段

- 能画出一次请求经过 Gateway、Filter、Security、MVC、Service 和数据访问的顺序。
- 能解释 `@Transactional` 为什么依赖代理，指出自调用和异步线程的边界。
- 能从配置来源和启动日志推导一个属性的最终值，并区分 readiness 与 liveness。

### 排错阶段

- 对 400、401、403、404、409、413、500 分别找到第一证据和下一层证据。
- 对“注解写了但没生效”“请求到了但数据为空”“本地与部署行为不同”写出可复现的验证步骤。
- 对文件上传、支付通知和远程调用分别标出资源关闭、幂等、超时和权限边界。
- 能为一个接口选择单元测试、MVC 切片测试或完整集成测试，并说明测试没有覆盖什么。
- 能解释异步任务为什么需要任务状态/重试，缓存为什么需要 key、TTL 和一致性策略。

## 你会解决哪些问题

| 你在前端看到的现象 | 后端要追的问题 | 入口章节 |
|---|---|---|
| 请求 URL 有了，但找不到 Java 方法 | Gateway 前缀、Controller 映射、HTTP 方法和参数绑定是否一致 | [Spring MVC 与 Controller](./mvc.md) |
| Controller 里不知道 Service 从哪里来 | Bean 是否被扫描、依赖是构造器注入还是字段注入、是否有多个实现 | [IoC 与依赖注入](./ioc.md) |
| 一段方法前后自动出现日志/事务/权限 | 调用是否经过 Spring 代理，注解放在了哪个 Bean 边界 | [AOP 与代理](./aop.md) |
| 本地能启动，换环境连错数据库 | profile、环境变量、配置中心和属性优先级 | [Spring Boot 配置](./boot.md) |
| 401、403、登录后仍无权限 | 身份认证、会话/Token 解析和资源授权分别在哪一层 | [认证与授权](./security.md) |
| 接口成功但数据没落库或被覆盖 | Mapper、事务边界、隔离级别、乐观锁和幂等条件 | [数据访问与事务](./data-access.md) |
| 改了代码却不知道是否破坏接口 | 单元、MVC 切片、集成测试和真实数据库验证边界 | [测试：单元、切片与集成](./testing.md) |
| 异步任务、缓存或远程调用不稳定 | 线程池、事件提交时机、缓存一致性、超时重试 | [异步、事件、缓存与服务集成](./integration.md) |
| 线上偶发慢、503 或无法定位 | traceId、指标、健康检查、线程/连接池和优雅停机 | [可观测性与生产运行](./observability.md) |
| 长连接或高并发 I/O 处理不稳 | 事件循环、背压、阻塞调用和取消信号 | [WebFlux 与响应式编程](./webflux.md) |
| 多服务路由、发现或熔断异常 | Gateway、Nacos、Feign、负载均衡和服务间鉴权 | [Spring Cloud 微服务](./cloud.md) |

## 一次请求的真实边界

```text
浏览器 Axios/fetch
  -> Gateway：路由、跨域、入口限流（如果部署了网关）
  -> Servlet 容器线程 / WebFlux 事件循环
  -> Spring Security：读取凭证并建立当前身份
  -> DispatcherServlet：匹配 Controller 方法
  -> 参数解析与 Bean Validation
  -> Controller：只处理 HTTP 边界
  -> Service：执行业务规则、事务边界和流程编排
  -> Mapper / Repository / Feign：访问数据库或其他服务
  -> Jackson 等消息转换器：Java 对象 -> JSON
  -> 浏览器响应拦截器
```

每一跳的成功含义不同：路由成功不代表业务成功，Controller 被调用不代表数据库提交，HTTP 200 也不代表 JSON 中的业务 `code` 一定成功。调试时记录 URL、HTTP 方法、状态码、请求 ID、业务码和异常边界，不只看页面提示。

## 用前端概念建立映射

| Vue/TypeScript 概念 | Spring 中更接近的理解 | 关键差异 |
|---|---|---|
| `provide/inject` | IoC 容器按类型/名称提供 Bean | Spring Bean 由容器管理生命周期，作用域默认是共享单例 |
| composable/service | Service Bean | Service 可能被多个请求线程同时调用，不能保存请求级可变字段 |
| Axios interceptor | Filter、Interceptor、AOP、Security 链 | 不同扩展点所处阶段不同，不能混为一谈 |
| Vue Router route | Controller 的 URL + HTTP 方法映射 | 后端还要绑定 body、校验、鉴权和序列化 |
| TypeScript interface | Java interface/DTO | TS 类型主要在编译期，后端仍必须运行时校验输入 |
| `.env` | `application.yml`、环境变量、配置中心 | 后端配置会影响连接池、线程池和安全边界，不能随意热改 |

## 学习顺序

如果你想先建立注解的整体地图，可以先看 [注解大全：Java、Spring 与常用框架](../java/annotations.md)，再回到下面的 Spring 专题。

1. [IoC 与依赖注入](./ioc.md)：理解 Bean 来源、构造器注入、作用域和生命周期。
2. [Servlet 与 Servlet 容器](./servlet.md)：先认识 HTTP 请求如何进入 Servlet、Filter 和请求线程。
3. [Spring MVC 与 Controller](./mvc.md)：再把 URL、Query、Path、JSON 和文件映射到 Java 参数。
4. [Java 对象与 JSON 契约](./json-contracts.md)：检查字段名、ID 精度、金额、时间和枚举怎样变成浏览器可见的 JSON。
5. [Spring Boot 配置](./boot.md)：确认 starter、自动配置、profile 与外部配置的来源。
6. [AOP 与代理](./aop.md)：理解事务、日志和权限为何依赖代理调用。
7. [认证与授权](./security.md)：区分“是谁”和“能做什么”。
8. [数据访问与事务](./data-access.md)：理解数据源、MyBatis、分页、事务传播、隔离和并发更新。
   - 先看 [数据层与持久层入口](../data/)，区分 Java 持久层、数据库和 Redis 的职责。
9. [测试：单元、切片与集成](./testing.md)：用合适测试范围验证 Controller、Service、数据库和配置。
10. [异步、事件、缓存与服务集成](./integration.md)：处理后台任务、提交后事件、缓存、Feign、重试和幂等。
11. [可观测性与生产运行](./observability.md)：用日志、指标、trace、健康检查和优雅停机定位线上问题。
12. [WebFlux 与响应式编程](./webflux.md)：理解 `Mono`、`Flux`、背压、取消和阻塞代码隔离。
13. [Spring Cloud 微服务](./cloud.md)：理解 Gateway、Nacos、Feign、负载均衡、熔断和跨服务一致性。

每章都按“解决的问题 → 核心机制 → 代码 → 什么时候用 → 排错与练习”阅读。数据访问、测试、异步、响应式和生产运行章节会把前端 Network 现象连接到数据库、线程池、缓存、事件循环和部署证据。代码片段里如果出现注解但没有完整 Maven 工程，会明确标注为集成片段；不要把片段直接当成已联调的生产代码。

## 在 datacenter-cloud 中建立证据链

本知识库以当前 checkout 的类名帮助你练习静态阅读，配置中心、数据库和 Nacos 是否可用仍要看运行环境：

1. `gykj-modules/gykj-dashboard/.../DashboardApplication` 是启动入口，包含 `@SpringBootApplication`、`@EnableFeignClients` 和 `@MapperScan`。
2. `.../manage/controller/DashboardPageController` 把仪表盘页面接口暴露为 HTTP 方法。
3. `.../manage/service/IDashboardPageService` 是业务契约，`DashboardPageServiceImpl` 是实现。
4. `.../manage/dao/DashboardPageDao` 等 Mapper 由 MyBatis 扫描注册，不是普通的 `new`。
5. `.../chart/controller/ChartDataController` 继续调用页面服务和图表数据服务。

建议在 IDE 中从 Controller 的构造器/字段跳到接口，再用 `implements` 和 Bean 注解找到实现；最后核对启动类扫描范围。包名看起来合理不等于运行时一定被扫描。

## 排错顺序

```text
页面 Network：URL、方法、状态码、请求体、响应体
  -> Gateway：是否路由到正确服务、是否改写路径
  -> 启动日志：profile、端口、Bean 创建、配置中心连接
  -> Controller：映射与参数绑定是否进入方法
  -> Service：业务分支、事务代理、远程调用是否返回
  -> 数据库/下游：SQL、连接池、超时、权限与数据状态
```

常见误判包括：把 Gateway 前缀当成 Controller 路径、把 HTTP 200 当成业务成功、把找不到 Bean 当成“代码没有执行”、把 `@Transactional` 写上就认为事务一定生效、把登录成功当成拥有某个资源权限。

## 练习

1. 任选一个页面请求，记录浏览器最终 URL 与服务内部 Controller 路径，标出可能的 Gateway 改写。
2. 画出 `Controller -> Service -> Mapper/Feign` 的依赖图，并写出每个边界的输入/输出。
3. 找一个单例 Service，检查是否把当前用户、当前请求参数或临时结果保存在实例字段。
4. 找一个配置项，列出它可能来自本地文件、环境变量、命令行还是配置中心，并说明如何验证最终值。
5. 找一个鉴权失败响应，区分认证失败、授权失败、业务资源不存在和网关拒绝。

继续阅读：[IoC 与依赖注入](./ioc.md)、[数据访问与事务](./data-access.md)、[测试：单元、切片与集成](./testing.md) 或 [Spring Cloud 微服务](./cloud.md)。
