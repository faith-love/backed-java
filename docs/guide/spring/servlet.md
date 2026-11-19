# Servlet：容器如何接收 HTTP 并执行 Java Web 代码

Servlet 是 Java Web 的基础 API。它规定服务器怎样把 HTTP 请求交给 Java 组件、怎样通过 request/response 读取请求和写回响应，以及 Filter、Listener 和 Session 怎样参与 Web 应用生命周期。Spring MVC 的 `DispatcherServlet` 本身就是一个 Servlet；理解 Servlet 后，Controller、Filter、Session 和请求线程就不再像框架魔法。

本章以项目常见的 **Spring Boot 2.7 / Servlet 4 / `javax.servlet.*`** 为例。Spring Boot 3 及更新的 Jakarta EE 版本使用 `jakarta.servlet.*` 包名，依赖和代码必须与服务器版本匹配，不能只把 import 名字随意替换后就认为兼容。

## Servlet 解决什么问题

浏览器通过 HTTP 发送字节和 Header，Java 业务代码希望面对结构化的请求信息，并以统一方式写出状态码、Header 和响应体。Servlet API 定义这层标准接口；Servlet 容器（例如 Tomcat）负责网络连接、请求解析、对象创建、线程调度、组件生命周期和资源释放。

```text
浏览器 HTTP 请求
  -> Tomcat Connector 接收字节并解析 HTTP
  -> 创建/填充 HttpServletRequest 与 HttpServletResponse
  -> 按应用映射执行 Filter Chain
  -> 调用目标 Servlet（Spring MVC 中通常是 DispatcherServlet）
  -> Spring 找 Handler/Controller 并执行
  -> 写入响应并返回给浏览器
```

前端可以把 Servlet API 理解成“浏览器请求到 Java Web 程序之间的运行时契约”；它不像 Vue Router 负责前端页面组件，而是运行在服务端、处理真实网络请求并管理服务端资源。

## Servlet API、Servlet 容器和 Spring MVC 的关系

| 名称 | 职责 | 例子 | 不负责什么 |
|---|---|---|---|
| Servlet API | 定义 Java Web 组件和请求/响应的标准接口 | `Servlet`、`Filter`、`HttpServletRequest` | 不监听 TCP 端口、不解析具体 HTTP 网络包 |
| Servlet 容器 | 实现 API、接收 HTTP、调用组件、管理线程和生命周期 | Tomcat、Jetty、Undertow | 不自动理解所有业务规则 |
| Servlet | 处理映射到自身的请求 | `HttpServlet` 子类、Spring `DispatcherServlet` | 不等于整个 Spring 容器 |
| Filter | 在目标 Servlet 前后拦截请求/响应 | 编码、审计、安全过滤器 | 不天然知道 Spring MVC 里最终选中了哪个 Handler |
| Spring MVC | 在 `DispatcherServlet` 之上做 Handler 路由、参数绑定、校验、转换和异常处理 | `@RestController`、`HandlerInterceptor` | 不替代底层 Servlet 容器 |

Spring Boot 内嵌 Tomcat 时，应用进程启动会创建容器并监听端口；部署成 WAR 到外部 Tomcat 时，容器由外部启动。两种方式都遵循 Servlet 请求模型，但打包方式和容器生命周期入口不同。

## Servlet 生命周期：谁创建、谁调用、谁关闭

Servlet 容器通常创建 Servlet 实例并调用其生命周期方法：

1. **加载和实例化**：容器根据映射创建 Servlet 实例。何时创建可由启动配置和容器策略决定。
2. **初始化 `init()`**：每个实例初始化时调用一次，用于读取初始化参数、创建资源或准备不可变配置。
3. **处理请求 `service()`**：每个请求进入时调用；`HttpServlet.service()` 根据 HTTP 方法分派到 `doGet`、`doPost`、`doPut` 等方法。
4. **销毁 `destroy()`**：应用卸载或容器关闭时调用，用于停止自建线程、关闭组件自己拥有的资源。

传统 Servlet 映射通常由一个实例并发服务多个请求线程。因此 Servlet 的成员字段不能当成“当前请求变量”。请求输入应放在方法局部变量、request attribute 或独立请求对象里；共享成员应不可变或自行线程安全。

下面是 Servlet API 片段，需要 Servlet 依赖和容器才能部署运行；它不是单独的普通 Java `main` 程序。

```java
// Spring Boot 2.7 / Servlet 4 集成示例；部署到 Tomcat 后由容器创建和调用。
public final class PingServlet extends HttpServlet {

    // 只保存不可变服务名；不要在 Servlet 单例字段里保存当前用户或请求参数。
    private String serviceName;

    // 容器初始化 Servlet 实例时调用一次。
    @Override
    public void init() throws ServletException {
        // 读取部署时配置的初始化参数；没有配置时使用非敏感默认值。
        this.serviceName = getServletConfig().getInitParameter("serviceName");
        // 若初始化失败应抛 ServletException，让容器报告应用启动/组件失败。
        if (serviceName == null || serviceName.isBlank()) {
            throw new ServletException("缺少 serviceName 初始化参数");
        }
    }

    // 每个 GET 请求都会调用；多个请求可能同时进入此方法。
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // 请求特有的数据保存在局部变量中，不写入 Servlet 共享成员字段。
        String requestId = request.getHeader("X-Request-Id");

        // 设置 HTTP 状态和响应类型，字符集要和实际写出的字节编码一致。
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain;charset=UTF-8");

        // Writer 由 Servlet 容器提供；本方法只写数据，不关闭容器拥有的响应资源。
        PrintWriter writer = response.getWriter();
        // 写入字符内容；容器在请求生命周期结束时负责完成响应。
        writer.println("service=" + serviceName);
        writer.println("requestId=" + (requestId == null ? "未提供" : requestId));
    }

    // 容器停止或卸载该 Servlet 时调用；资源要按创建者所有权关闭。
    @Override
    public void destroy() {
        // 本例没有自建连接或线程，因此无需额外关闭；真实资源在这里或专用 Bean 中释放。
    }
}
```

真实 Spring MVC 项目通常不手写上述业务 Servlet，而是让 Spring 注册 `DispatcherServlet` 并用 Controller 处理业务；这个示例用于看懂生命周期和并发边界。

## 请求线程：为什么没写 `new Thread` 也会并发

Tomcat Connector 接收请求后，会把请求任务交给容器管理的工作线程。通常同步 Servlet 方法返回后，容器线程才继续处理下一个请求；不同请求可同时由不同线程处理同一个 Servlet/Controller/默认单例 Service。

```text
请求 A -> tomcat-worker-1 -> 同一个 Controller Bean -> 当前用户 A
请求 B -> tomcat-worker-2 -> 同一个 Controller Bean -> 当前用户 B
```

因此在 Controller 或 Service 中写一个可变成员 `private String currentUser;` 会导致请求互相覆盖。请求内临时数据放局部变量；需要跨请求共享的数据放数据库/缓存并定义并发规则；线程模型与开线程细节见[并发与线程安全](../java/concurrency.md)。

普通 Servlet 是阻塞式调用：线程等待 JDBC、文件或 HTTP 结果期间仍被占用。Servlet Async API 可以让请求生命周期暂时脱离原工作线程，但任务执行仍需要有界执行器；它不等于 NIO 非阻塞，也不会自动取消数据库操作。Spring MVC 的 `Callable`/`DeferredResult` 封装异步请求处理，WebFlux 使用另一套响应式模型。

## Filter、Servlet、Interceptor 与 Listener 分别在哪一层

| 机制 | 所在层 | 典型作用 | 顺序/生命周期 |
|---|---|---|---|
| `Filter` | Servlet 容器层 | 编码、通用 Header、访问日志、部分安全或包装请求体 | 可按 URL/Dispatcher Type 配置链顺序，包围整个 Servlet 调用 |
| `DispatcherServlet` / 自定义 Servlet | Servlet 层 | 接收实际 HTTP 请求并执行业务路由入口 | Servlet 映射决定哪些请求到它 |
| `HandlerInterceptor` | Spring MVC Handler 层 | 在已选中的 Controller 前后执行权限辅助、审计或耗时统计 | 只有 Spring MVC 找到 Handler 后才按 MVC 顺序执行 |
| `ServletContextListener` | Web 应用生命周期 | 应用初始化或销毁时做生命周期通知 | 随 Web 应用创建/关闭，不是每个请求执行 |
| `HttpSessionListener` | Session 生命周期 | 统计或清理 Session 相关资源 | Session 创建/销毁时触发，集群环境需考虑会话复制/存储方式 |

Filter 常用于跨整个 Servlet 应用的请求处理；Interceptor 可以访问 Spring MVC 已匹配的 Handler 和路由信息。两者不是同一条链。Spring Security 通常以一组 Servlet Filter 构成安全链，因此鉴权失败时 Controller 可能尚未执行。

Filter 的核心动作是决定是否继续调用下一个过滤器/目标 Servlet。只记录 `chain.doFilter()` 前的时间，得到的是“下游还没处理”的耗时；要统计整段耗时，应在调用前后记录，并在 finally 中清理请求上下文。

```java
// Servlet Filter 集成示例；需由 Web 容器/Spring Boot 注册后才会运行。
public final class RequestTimingFilter implements Filter {

    // Filter 实例通常也会被多个请求线程共享，所以不保存每个请求的时间戳为成员变量。
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 使用局部变量记录当前请求开始时间，避免与并发请求共享。
        long startedAt = System.nanoTime();
        try {
            // 继续 Filter 链；如果不调用，后面的过滤器和目标 Servlet 都不会执行。
            chain.doFilter(request, response);
        } finally {
            // 无论下游正常返回还是抛异常，都记录整段容器链耗时。
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            // 示例输出仅用于说明；生产环境应使用统一日志和脱敏字段。
            System.out.println("servlet-chain-ms=" + elapsedMillis);
        }
    }
}
```

该代码依赖 Servlet API，但不依赖 Spring；要在 Spring Boot 自动注册，需要 `FilterRegistrationBean` 或适用版本的 `@Component`/`@WebFilter` 扫描配置。注册方式、URL pattern 和顺序不同会影响它是否真的进入请求链。

## HttpServletRequest：读取路径、参数、Header 和请求体

| API | 数据来自 | 常见用途 | 边界 |
|---|---|---|---|
| `getRequestURI()` | 请求路径 | 日志、路由定位 | 一般不包含 Query String；反向代理可能已改写 |
| `getQueryString()` / `getParameter()` | URL 查询参数或表单字段 | 分页、简单筛选 | 参数是字符串；复杂 JSON body 不会由 `getParameter` 自动解析 |
| `getHeader(name)` | HTTP Header | Trace ID、Accept、幂等键 | 客户端可提交的身份类 Header 必须由可信认证链校验 |
| `getInputStream()` | 原始请求体字节 | 二进制或自定义协议 | 读取后通常不能再从头读；受容器和请求包装器管理 |
| `getReader()` | 请求体字符内容 | 文本体 | 一般不能与 `getInputStream()` 混用；设置字符编码要在读取前 |
| `getAttribute()` | 服务端请求属性 | Filter/Servlet/MVC 之间传递请求内对象 | 只活在本次请求，不是 Session 或跨请求存储 |
| `getSession()` | HTTP Session | Cookie 会话状态 | 默认可能创建新 Session；不需要创建时使用 `getSession(false)` |

请求 Body 是一条有位置状态的流。若 Filter 先读完 JSON 以记录内容，后面的 Spring `@RequestBody` 可能读到 EOF；若确实需要重复读取，必须使用有大小上限、正确处理编码且经过安全评估的请求包装器，不能无界缓存文件上传体。

参数解码依赖请求编码。POST 表单和 JSON 的编码/解析路径不同；Spring MVC 通常由消息转换器和配置的字符集处理 JSON。遇到乱码或参数为空时，检查 `Content-Type`、body 实际内容、参数位置和读取顺序。

## HttpServletResponse：状态、Header 与响应体

响应由状态码、Header 和 Body 构成。`setStatus()`、`setHeader()` 等应尽量在响应提交前调用；一旦写出的字节越过容器缓冲或 flush，响应可能已经 committed，此后不能可靠地改状态码或把半份文件替换成 JSON 错误。

Servlet response 的 `getWriter()` 与 `getOutputStream()` 通常互斥：文本 JSON/HTML 使用 Writer，二进制文件使用 OutputStream。先设置 Content-Type/字符集，再写内容；写完后由容器完成响应和连接处理，不应随意关闭容器拥有的底层 Socket。

```java
// Servlet API 响应片段；Controller 中通常交给 Spring HttpMessageConverter 完成。
static void writeJson(HttpServletResponse response) throws IOException {
    // 在获得 Writer 之前指定媒体类型与 UTF-8 字符集。
    response.setContentType("application/json;charset=UTF-8");
    // 明确设置状态码，供浏览器和代理按 HTTP 语义处理。
    response.setStatus(HttpServletResponse.SC_OK);
    // 从容器提供的字符 Writer 写响应体，不关闭容器底层连接。
    response.getWriter().write("{\"ok\":true}");
}
```

真实业务不要通过字符串拼接任意用户输入构造 JSON；使用 Jackson 等序列化器。下载文件时设置经过校验的 `Content-Disposition`，不能把用户文件名直接拼进响应 Header。

## Cookie、Session 与无状态 Token

HTTP 本身按请求工作，不会自动记住前一次请求的登录状态。Cookie 是浏览器保存并按域/路径/安全规则发送的小型键值；Session 是服务端按 Session ID 关联的一份状态。常见流程是服务端创建 Session，将其 ID 写入 Cookie，后续请求由浏览器携带 Cookie。

| 做法 | 状态存在哪里 | 适用与取舍 |
|---|---|---|
| Cookie + 服务端 Session | 浏览器有 ID，服务端有会话状态 | 服务端可撤销会话；多实例需共享 Session 或做粘性路由 |
| Bearer Token | 客户端持有 Token，服务端验证签名/状态 | API 常见；Token 泄露风险、过期和撤销策略需设计 |
| Servlet HttpSession | Servlet 标准 API 的服务端会话抽象 | 本地单实例易用；容器重启、集群共享与安全配置要考虑 |

Cookie 的 `HttpOnly` 阻止前端脚本直接读取 Cookie，`Secure` 限制 HTTPS 发送，`SameSite` 控制跨站请求携带行为。跨域 Cookie 还受 CORS credentials 与浏览器第三方 Cookie 策略限制。Session ID 必须足够随机并避免在 URL 中传递；登录状态变化后要防 Session Fixation。

不要把密码、完整个人信息或可无限期使用的高权限 Token 放在普通 Cookie/Session 中。Session 存储和 Token 选择要结合部署拓扑、撤销需求、CSRF/XSS 风险以及前端认证方式。

```java
// Servlet API 片段：保存短期 Session 状态，并演示普通 Cookie 的安全属性。
static void saveTemporaryState(HttpServletRequest request, HttpServletResponse response) {
    // 只有确实需要登录态时才创建 Session；匿名静态请求可用 getSession(false) 检查现有会话。
    HttpSession session = request.getSession(true);
    // 保存短期业务标识，不保存密码或完整支付数据。
    session.setAttribute("currentOrderId", "ORD-1001");

    // 这是界面偏好 Cookie，不是登录凭证；不要自行复制 Session ID 到另一个 Cookie。
    Cookie cookie = new Cookie("ui-preference", "zh-CN");
    // 限定浏览器发送此 Cookie 的路径。
    cookie.setPath("/");
    // 禁止普通前端脚本读取，降低部分 XSS 场景下直接窃取 Cookie 的风险。
    cookie.setHttpOnly(true);
    // 生产 HTTPS 使用 Secure；本地 HTTP 环境需单独配置。
    cookie.setSecure(true);
    // 设置合理的有效期；Session Cookie 的具体生命周期由服务端策略共同决定。
    cookie.setMaxAge(15 * 60);
    // 让容器在响应中写入 Set-Cookie Header。
    response.addCookie(cookie);
}
```

上例的自定义 Cookie 是教学片段。Servlet 4 的 `Cookie` API 对 `SameSite` 便利支持因容器实现而异；生产 Session Cookie 应优先通过容器或 Spring Security 配置 `Secure`、`HttpOnly`、`SameSite` 和合理过期策略，而不是手工复制 Session ID 到第二个 Cookie。

## 转发、重定向与请求属性

| 操作 | 谁发起下一步 | 浏览器地址栏 | 请求体/方法 | 常见用途 |
|---|---|---|---|---|
| `RequestDispatcher.forward()` | 服务器内部转发 | 通常不变 | 在当前服务器请求上下文内继续处理 | Servlet 内部交给另一个资源渲染 |
| `HttpServletResponse.sendRedirect()` | 浏览器收到 3xx 后再次请求 | 改为新 URL | 新请求；方法是否保留依状态码与客户端规则 | 登录后跳转、跳到外部地址 |
| Spring MVC 内部 Handler 调用 | 应用代码直接调用 Service/组件 | 不适用 | 不产生新的 HTTP 请求 | 业务层编排；通常不是 Servlet forward |

Forward 与 Redirect 不能混为一谈。Redirect 会重新走浏览器、网关和认证链；敏感信息不应放在跳转 URL。Servlet request attribute 在 forward 的同一请求内可见，Redirect 后的新请求不会自动保留；要跨请求保存需使用 Session、一次性状态或持久化业务记录。

```java
// Servlet API 片段：在服务器内部把同一个请求交给另一个资源。
static void forwardToView(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
    // request attribute 在当前请求及其内部 forward 中可被目标资源读取。
    request.setAttribute("pageTitle", "订单详情");
    // ServletContext 返回容器管理的 RequestDispatcher。
    RequestDispatcher dispatcher = request.getRequestDispatcher("/WEB-INF/views/order.jsp");
    // 浏览器地址栏不变；目标资源继续使用相同的 request/response。
    dispatcher.forward(request, response);
}
```

## Multipart：表单文件上传不等于分片上传

`multipart/form-data` 是一种 HTTP 请求体编码：请求由多个边界分隔的 part 组成，每个 part 可以是文本字段或文件。Servlet 3.0+ 的 `Part` 和 Spring 的 `MultipartFile` 提供解析后的访问方式。容器/框架可能把文件 part 缓冲在内存或临时磁盘，因此“Controller 用 InputStream”不等于代理、容器和框架整条链都没缓冲。

普通上传链路：

```text
浏览器 FormData
  -> Nginx/Gateway 请求体限制
  -> Servlet 容器接收 multipart 并按配置暂存
  -> Spring MultipartResolver 解析 part
  -> Controller 接收 MultipartFile
  -> Service 流式写私有临时位置并校验
  -> 成功后发布文件记录/对象
```

安全与资源边界：

- 限制请求总大小、单文件大小、part 数量、文件名长度、临时盘空间和并发上传数。
- 不信任客户端扩展名和 Content-Type；按业务检查真实格式、内容和恶意文件扫描要求。
- 服务端生成存储 key，避免路径遍历和覆盖其他租户文件。
- 失败时删除自己创建的临时文件；成功后再提交数据库元数据或发布正式对象。
- 不把容器管理的临时文件引用交给后台线程长期读取；异步任务应接管到自己管理的持久输入。
- 分片上传还需要 uploadId、分片号、校验和、幂等、过期清理、并发合并控制和断点续传协议。

## Servlet Async、Spring MVC 异步和 WebFlux 的区别

| 方式 | 请求线程如何变化 | 谁执行耗时任务 | 适用判断 |
|---|---|---|---|
| 同步 Servlet/MVC | 请求工作线程一直到响应结束 | 当前 Servlet/Tomcat 工作线程 | 普通短请求、同步 JDBC 查询 |
| Servlet Async API | 可以释放最初请求线程，稍后再完成响应 | 应用指定/容器管理的异步执行资源 | 长轮询或可控的异步响应，但仍需管理执行器、超时和取消 |
| Spring MVC `Callable`/`DeferredResult` | MVC 暂停原始派发，等待异步结果再继续完成响应 | 配置的 MVC `AsyncTaskExecutor` 或提交结果的外部流程 | 与 Spring MVC 接口集成；下游仍可能是阻塞调用 |
| WebFlux/Reactor | 面向事件循环和 Reactive Streams | 非阻塞驱动上的少量事件循环线程 | I/O 客户端和数据访问也非阻塞、并发连接多的场景 |

把同步 JDBC 包进 `Callable` 只是换线程执行阻塞 JDBC；它可以释放原 Tomcat 请求线程，但会消耗异步池中的线程和数据库连接。线程池必须有上限和拒绝策略；超时/客户端断开后还需定义是否取消任务、清理文件和报告最终状态。

## Spring MVC 如何建立在 Servlet 上

一次常见 Spring MVC 请求的内部顺序如下：

```text
Tomcat 接收和解析 HTTP
  -> Servlet Filter Chain（含 Spring Security FilterChainProxy）
  -> DispatcherServlet
  -> HandlerMapping 选择 Controller 方法
  -> HandlerInterceptor.preHandle
  -> HandlerAdapter 解析参数、校验并调用 Controller
  -> Service / Mapper / 下游调用
  -> HttpMessageConverter 序列化响应 DTO
  -> HandlerInterceptor.afterCompletion
  -> Filter 后置逻辑
  -> Tomcat 写出 HTTP 响应
```

异常可能在 Filter、参数解析、Controller、Service 或响应序列化阶段发生；异常解析器和 `@RestControllerAdvice` 通常处于 Spring MVC Handler 执行边界，不会自动捕获进入 `DispatcherServlet` 之前的所有 Filter 错误。定位 Controller 断点没命中时，先看 DNS/Nginx/Gateway、Filter/Security 和 HandlerMapping，而不是直接认定 IDE 断点失效。

Spring MVC 的 Controller 不是额外的一种 Servlet；通常是 `DispatcherServlet` 接收所有匹配路径，再由 Spring MVC 将每个路径分派到不同 Controller 方法。修改 Controller 映射时要同时核对 Nginx/Gateway 路径重写和 Servlet mapping。

## 常见问题定位

| 现象 | 优先核对 | 常见根因 |
|---|---|---|
| 请求到服务但 Controller 不进 | Filter/Security 日志、DispatcherServlet 映射、HandlerMapping | 鉴权提前拒绝、Servlet mapping 或 HTTP 方法不匹配 |
| 同一用户并发请求互相覆盖数据 | Controller/Service 是否共享可变成员字段 | 单例 Bean/Servlet 把请求状态放在实例字段 |
| JSON body 在 Controller 里为空或 EOF | Filter 是否提前读取 InputStream、Body 是否被包装 | 请求体流已消费、Content-Type 错误或请求发送方式不符 |
| Query 有值而 `@RequestParam` 为空 | 最终 URL、参数名、编码、代理改写 | 前端放在 body，服务端却期待 query；字段名不一致 |
| Session 登录后偶发掉线 | Cookie 属性、Session 存储、粘性路由和重启 | 多实例未共享 Session、SameSite/Secure/CORS 不匹配 |
| Filter 设置 401 但浏览器得到 200/HTML | 是否已提交响应、网关错误包装、重定向 | 响应已 committed、登录重定向或统一响应层改写 |
| 大文件占满磁盘或上传失败 | Nginx/Gateway/Tomcat/Spring 限制、multipart 临时目录 | 某一层体积上限过小、临时盘无空间或并发无上限 |
| 请求结束后后台任务读不到上传文件 | MultipartFile 临时文件所有权与任务提交时序 | Controller 返回后容器清理临时 part |

## 练习：把一次 API 请求分层画出来

1. 用浏览器 Network 记录 Request URL、Method、Cookie/Header、Body 和响应状态。
2. 画出 Nginx、Gateway、Tomcat、Filter、DispatcherServlet、Controller、Service 的顺序。
3. 找一个 Filter 和一个 `HandlerInterceptor`，比较它们分别能看到哪些请求信息。
4. 在本地测试环境验证 `getSession()` 与 `getSession(false)` 的差异，并观察浏览器 Cookie。
5. 检查一个上传接口的限制位于 Nginx、Gateway、Servlet 容器、Spring 解析器和存储服务中的哪几层。
6. 查明项目使用 `javax.servlet` 还是 `jakarta.servlet`，再解释与 Spring Boot/Tomcat 版本的对应关系。

继续学习：[Spring MVC 与 Controller](./mvc.md)、[网络基础](../engineering/network.md)、[I/O 流专题](../java/io.md)、[线程与并发](../java/concurrency.md)。
