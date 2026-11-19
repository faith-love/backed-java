# Spring MVC 与 Controller：把 HTTP 契约落到 Java 方法

Spring MVC 解决的是：**如何把 HTTP 方法、路径、请求头、Query、JSON、文件和异常，可靠地映射到 Java 方法及响应**。Controller 是 HTTP 边界，不是把所有业务、SQL、线程和文件操作都塞进去的“大方法”。

前端的 `router + fetch/axios + response interceptor` 可以帮助理解，但 Spring MVC 还要额外处理 Java 类型转换、校验、Servlet 线程、消息转换器、异常解析和资源关闭。

## 知识点地图：把一个 HTTP 请求拆成可验证的阶段

| 知识点 | 解决的问题 | 前端对应理解 | 排错证据 |
|---|---|---|---|
| `DispatcherServlet` | 统一接收请求并分发到 Handler | 应用级路由入口 | 请求是否进入应用日志 |
| `HandlerMapping` | 根据路径、HTTP 方法和条件找到 Handler | Router 匹配 | 404、405、映射日志 |
| `HandlerAdapter` | 用统一方式调用不同类型的 Handler | 调用适配器 | 参数解析异常、方法未执行 |
| 参数解析器 | 把 Path/Query/Header/Body 转成 Java 参数 | 从 route/query/body 取值 | 400、类型转换错误 |
| `HttpMessageConverter` | JSON、文本、字节与 Java 对象互转 | `JSON.parse/stringify` 和 response body | `Content-Type`、序列化字段 |
| Bean Validation | 拦截格式、长度、范围等输入错误 | 表单 schema 校验的服务端版本 | `MethodArgumentNotValidException` |
| `HandlerExceptionResolver` | 把异常转换成 HTTP 响应 | 全局 error handler | HTTP status、错误码、traceId |
| 返回值处理器 | 将对象、`ResponseEntity` 或资源写回客户端 | 统一响应/文件下载 | 响应头、状态码、body 是否已提交 |

### 先分清四类校验

1. **传输格式校验：** `Content-Type`、JSON 语法、字段类型、文件大小；失败通常在 Controller 方法执行前发生。
2. **字段约束校验：** `@NotBlank`、`@Size`、`@Email` 等，解决“数据形状不符合契约”。
3. **业务规则校验：** 邮箱是否重复、订单是否仍可支付、库存是否足够，应在 Service 和事务边界中执行。
4. **授权校验：** 当前身份能否操作当前资源，不能用请求体中的 `userId`、前端按钮或 URL 猜测替代。

把四类校验混在一个 `if` 中，会导致错误码不稳定、权限绕过和重复逻辑。前端可以提前校验提升体验，但后端必须重新执行前两类以及后两类安全规则。

## 1. 一次请求的真实生命周期

~~~text
浏览器/客户端
  -> CDN/WAF/Nginx/Gateway
  -> Filter（请求 ID、认证上下文、CORS、原始请求体边界）
  -> DispatcherServlet
  -> HandlerMapping：按 HTTP 方法与路径找到 Controller 方法
  -> HandlerInterceptor：前置检查
  -> HandlerAdapter：解析参数并调用方法
  -> 参数解析器：Path/Query/Header/Body/Multipart
  -> Bean Validation：格式与字段约束
  -> Controller：只编排 HTTP 边界
  -> Service：业务规则、事务与并发控制
  -> 返回值处理器 + HttpMessageConverter：对象转 JSON/文件流
  -> 异常解析器或 @RestControllerAdvice
  -> Interceptor/Filter 后置逻辑
  -> HTTP 响应
~~~

这里不是每个项目都具备同样的 Gateway 或拦截器。要从依赖和配置确认当前服务实际有哪些层；网关外部路径也可能经过重写，不能直接当作 Controller 路径。

## 重难点一：映射成功、参数绑定成功、业务成功是三件事

```text
404：没有找到匹配的 Handler，方法通常还没有执行
405：找到了路径，但 HTTP 方法不匹配
400：找到 Handler，但参数转换/JSON/校验失败
401/403：身份或权限链拒绝
409：进入业务后发现状态/版本/幂等冲突
2xx：只代表 HTTP 处理结果，仍要看业务 code 和数据状态
```

**解决的问题：** 通过分层状态快速定位错误阶段。**使用时机：** 联调时先在浏览器 Network 记录方法、URL、Query、Body、`Content-Type` 和响应头，再逐层看 Gateway/Controller 日志。**常见误判：** 把 404 都归因于后端没有接口，把 400 当成 Service 抛错，把 HTTP 200 当成业务成功。

## 重难点二：`HandlerMapping` 和参数解析器如何决定方法能否执行

类级 `@RequestMapping` 与方法级 `@GetMapping`/`@PostMapping` 组合成最终路径；同一路径还可能按 HTTP 方法、`consumes`、`produces`、请求头条件继续筛选。匹配完成后，`HandlerAdapter` 为每个参数选择解析器：`@PathVariable` 读取路径变量，`@RequestParam` 读取 query/form，`@RequestBody` 交给消息转换器，`MultipartFile` 由 multipart 解析器提供。

```java
// Spring Boot 2.7 集成片段：展示“位置必须和注解匹配”，不是可单文件运行程序。
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @GetMapping(value = "/{orderNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderView detail(
            @PathVariable("orderNo") String orderNo,
            @RequestHeader("X-Request-Id") String requestId) {
        // orderNo 来自 URL；requestId 来自请求头，不能从 JSON body 猜测。
        return orderService.findVisible(orderNo, requestId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderView> create(
            @Valid @RequestBody CreateOrderRequest request) {
        // JSON 解析和字段校验通过后，才进入 Service 的业务规则。
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request));
    }
}
```

**排错方法：** 先确认浏览器的 HTTP 方法和 `Content-Type`，再检查 Controller 的 `consumes/produces`、参数注解与字段类型；不要在 Service 中补偿一个本应在 HTTP 边界修正的绑定错误。

## 重难点三：消息转换器不是“任意对象都能变 JSON”

Jackson 等 `HttpMessageConverter` 会根据请求/响应的媒体类型和 Java 类型选择转换器。`Content-Type: application/json` 只说明请求体格式，不保证字段满足业务；响应 `Content-Type` 也决定浏览器是否按 JSON、文本、文件或事件流处理。

| 响应类型 | 典型头 | 前端处理 | 不能做什么 |
|---|---|---|---|
| JSON DTO | `application/json` | `response.json()`/Axios 默认解析 | 直接当文件 Blob |
| 文件流 | `application/octet-stream` 或真实类型 | `blob`/下载导航 | 再套 JSON 错误对象 |
| SSE | `text/event-stream` | `EventSource`/流解析 | 等待整个响应后再 JSON.parse |
| HTML | `text/html` | 页面导航 | 直接插入 `v-html` |

响应已经写出部分字节后，不能再追加 JSON 错误；流式接口要在开始写响应前完成权限和关键参数检查，写出后只能关闭/记录连接失败。

## 重难点四：同步 Servlet、异步响应与非阻塞不是同义词

Spring MVC 的普通方法占用 Servlet 工作线程直到返回。`Callable`/`DeferredResult` 可以把等待交给异步执行器，但任务仍可能阻塞某个线程；它不是 WebFlux 事件循环，也不会自动取消数据库或远程调用。大文件读写、外部 HTTP、压缩和数据库查询都应设置超时、并发上限和取消策略。

```java
// MVC 异步响应集成片段：需要配置可控的 AsyncTaskExecutor 和超时。
@GetMapping("/reports/{id}/export")
public DeferredResult<ResponseEntity<Resource>> export(@PathVariable String id) {
    DeferredResult<ResponseEntity<Resource>> result = new DeferredResult<>(30_000L);
    // executor 必须是有界线程池；不能在请求里 new Thread 或创建无界队列。
    exportExecutor.execute(() -> {
        try {
            Resource resource = reportService.buildExport(id); // 仍是可能阻塞的业务。
            result.setResult(ResponseEntity.ok(resource));
        } catch (Exception failure) {
            // 取消/超时后 setResult 可能返回 false；记录任务状态并清理临时文件。
            result.setErrorResult(failure);
        }
    });
    result.onTimeout(() -> reportService.markExportTimeout(id));
    return result;
}
```

**使用时机：** 只有当请求需要释放 Servlet 线程等待慢任务、且任务有可靠状态和取消边界时才使用；用户不必等待的导出/转码应提交持久任务并返回任务 ID。真正的非阻塞网络链路需看 WebFlux/Reactor Netty，参见 [网络库](../java/network-libraries.md)。

## 2. 参数位置决定 Java 注解

| HTTP 位置 | Java 常见注解 | Axios/fetch 形状 | 适合场景 | 常见错误 |
|---|---|---|---|---|
| Path | `@PathVariable` | `/users/42` | 资源唯一标识 | 把可选筛选条件写进 path |
| Query | `@RequestParam`、`@ModelAttribute` | `params: { pageNum: 1 }` | 分页、筛选、排序 | 前端把 JSON 放 body，后端却读 query |
| Header | `@RequestHeader` | `headers` | Trace ID、幂等键、内容协商 | 直接相信客户端传来的用户身份 |
| JSON body | `@RequestBody` DTO | `data: { ... }` | 创建/修改/复杂查询 | body 已被其他过滤器读完、Content-Type 错误 |
| Form field | `@RequestParam` | `FormData.append` | 文件附带字段 | 手动设置 multipart boundary |
| Multipart file | `MultipartFile` | `FormData.append('file', file)` | 小/中型附件 | 误以为 multipart 就是分片上传 |
| Cookie | `@CookieValue`/请求对象 | 浏览器自动携带 | 同源会话 | 跨域 credentials/CORS 不匹配 |

### 前端调用对照

```ts
// Path + Query：参数会进入 URL，不是 JSON body。
await axios.get(`/api/users/${userId}`, {
  params: { pageNum: 1, pageSize: 20 },
})

// JSON body：Content-Type 通常由请求库设置为 application/json。
await axios.post('/api/users', { name: 'Lin', email: 'lin@example.com' })

// Multipart：让浏览器/请求库生成 boundary，不手写 Content-Type。
const form = new FormData()
form.append('file', file)
form.append('description', '头像')
await axios.post('/api/files', form)
```

`TypeScript interface` 只帮助前端编译期检查，不能替代后端校验；攻击者可以绕过页面直接构造 HTTP 请求。

## 3. Controller 只负责边界，Service 负责业务

下面是 Spring Boot 2.7 / Java 11 风格的集成片段。它使用普通 Java Bean，避免把 Java 16 的 `record` 误写成 Java 11 代码。

```java
// 请求 DTO：只表达客户端允许提交的字段和格式约束。
public final class CreateUserRequest {
    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 80, message = "姓名长度必须为 2 到 80")
    private String name;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

// 返回 VO：只暴露页面需要的字段，不把数据库 Entity 直接返回给客户端。
public final class UserView {
    private final Long id;
    private final String name;
    private final String email;

    public UserView(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}

@RestController
@RequestMapping("/api/users")
public final class UserController {
    private final UserService userService;

    // 构造器注入使依赖明确，也方便单元测试提供替身。
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public UserView getById(@PathVariable("id") Long id) {
        // Service 负责不存在时的领域异常、权限和数据读取规则。
        return userService.getById(id);
    }

    @PostMapping
    public ResponseEntity<UserView> create(
            @Valid @RequestBody CreateUserRequest request) {
        UserView created = userService.create(request);
        // 创建成功返回 201；不要所有结果都无脑返回 200。
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
```

`@Valid` 主要做字段格式和结构校验；“邮箱是否已注册”“当前用户能不能创建该资源”“库存是否足够”是业务规则，应在 Service/领域层再次验证。校验通过也不代表授权通过。

## 4. 响应：HTTP 状态、业务 code 和数据形状分开看

一个项目可能返回 `UserView`、`Result<UserView>`、`R<T>` 或分页包装。排查前端“拿不到数据”时分三层：

1. **HTTP status**：网络/协议层是否成功，例如 201、400、401、403、404、409、413、500。
2. **JSON 业务 code**：项目是否把业务失败放在 HTTP 200 的 `code` 中。
3. **前端拦截器**：Axios 是否已经取出 `response.data`，页面拿到的是 `data` 还是完整响应。

统一响应并不是所有接口都适合。文件下载、SSE、WebSocket 和流式响应通常需要专门的 Content-Type 与生命周期，不能套一个 JSON 包装。

## 5. 全局异常处理：把失败转换在 HTTP 边界

Controller 不应在每个方法都复制 `try/catch`。`@RestControllerAdvice` 可以把已知领域异常、参数校验异常和未知异常映射成统一响应；但要保留日志上下文，不把 SQL、文件绝对路径和密钥返回给浏览器。

```java
// Spring Boot 2.7 使用 javax.validation；Boot 3 需要改为 jakarta.validation。
@RestControllerAdvice
public final class ApiExceptionHandler {
    private final TraceIdProvider traceIds;

    public ApiExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(
                new ApiError("INVALID_ARGUMENT", message, traceIds.current()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError("NOT_FOUND", "资源不存在", traceIds.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        // 内部记录完整堆栈和 traceId；客户端只收到稳定、脱敏的提示。
        log.error("未处理的接口异常 traceId={}", traceIds.current(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError("INTERNAL_ERROR", "服务暂时不可用", traceIds.current()));
    }
}
```

异常处理器本身也有匹配顺序和响应契约；不要用一个 `Exception` 处理器把认证失败、参数错误、业务冲突都变成 500。

## 6. 文件与流式响应：Controller 仍要负责边界

`MultipartFile.getInputStream()` 能让服务层逐块读取，但 multipart 解析器可能已经使用了内存或临时磁盘；Controller 代码看起来流式，不代表整个链路没有缓冲。大文件分片上传请看[上传专题](../../examples/large-file-upload.md)。

```java
@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public FileView upload(@RequestParam("file") MultipartFile file,
                       Principal principal) throws IOException {
    if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024) {
        throw new IllegalArgumentException("文件为空或超过单文件上限");
    }
    // 存储路径由服务端生成，原始文件名只作为受限展示元数据。
    String fileId = fileService.saveStream(principal.getName(), file.getOriginalFilename(),
            file.getInputStream());
    return fileService.view(fileId);
}

@GetMapping("/{fileId}/content")
public ResponseEntity<InputStreamResource> download(
        @PathVariable String fileId, Principal principal) throws IOException {
    StoredFile stored = fileService.openAuthorized(principal.getName(), fileId);
    // Resource 由框架在响应生命周期内读取；服务层必须定义谁负责关闭底层流。
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(stored.contentType()))
            .contentLength(stored.length())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(stored.safeDownloadName(), StandardCharsets.UTF_8)
                            .build().toString())
            .body(new InputStreamResource(stored.stream()));
}
```

实际工程还要配置代理大小限制、multipart 临时目录、请求读取超时、文件归属和下载授权。不要让用户传入的绝对路径进入 `Path`，不要把文件内容一次性读成 `byte[]`；IO 专题见[流式处理](../java/io.md)。

## 7. 当前项目的请求链路

以当前 `datacenter-cloud` checkout 为例，先从前端 Network 记录真实请求，再沿下面路径核对：

### 仪表盘列表

~~~text
前端页面 -> /dashboard/dashboard/design/page
  -> DashboardPageController
  -> DashboardSearchDTO 参数绑定/分页校验
  -> IDashboardPageService.getByCategory
  -> DashboardPageServiceImpl
  -> IDashboardPageDao / MyBatis-Plus
  -> MixinsResp<PageVO<PageEntity>>
  -> Axios 响应拦截器 -> 页面表格
~~~

### 图表数据

~~~text
前端 POST -> /dashboard/dashboard/chart/data/list
  -> ChartDataController
  -> ChartDataSearchDTO
  -> BaseChartDataService
  -> 页面配置、chartCode、数据集查询
  -> R<ChartDataVO>
~~~

Gateway 的服务前缀、Nacos 路由和路径改写可能在应用外部发生；不要把浏览器完整 URL 直接复制成 Controller 注解。依赖存在也不等于某个调用一定使用响应式或非阻塞模型，要沿实际调用点确认。

## 8. 见到 400/401/403/404/409/413/500 怎么排

| 现象 | 先查什么 | 常见根因 |
|---|---|---|
| 400 | 请求方法、Content-Type、字段类型、校验错误 | Query/body 位置不一致，JSON 格式错误 |
| 401 | Token/Cookie 是否携带、过期、网关认证 | 前端请求层未注入认证 |
| 403 | 用户身份之后的权限规则 | 只登录但无资源/操作权限 |
| 404 | Gateway 路径、Controller 映射、服务实例 | 把外部前缀当内部路径 |
| 409 | 业务状态与幂等键 | 重复创建、版本冲突、同名资源 |
| 413 | CDN/Nginx/Gateway/Servlet 限制 | 只提高应用配置仍被前置层挡住 |
| 500 | 服务日志中的 traceId、异常链、事务回滚 | 未知异常、数据库/下游失败 |

调用链排查顺序：浏览器 Request/Response → Gateway 日志 → Controller 参数 → Service 入参/异常 → Mapper/Feign → 数据库或下游响应。每一步记录脱敏后的请求 ID、状态和耗时，不要把 Token、密码和完整大文件写日志。

## 9. 练习与参考

1. 对一个页面请求列出 HTTP 方法、外部 URL、Gateway 改写后的内部路径、Controller 映射和参数来源。
2. 将一个 `@RequestParam` 错改成 `@RequestBody`，观察浏览器请求和 400 错误的差异，再恢复。
3. 找一个 DTO，分别标出格式校验、授权校验、状态校验和数据库约束。
4. 找一个文件接口，确认代理、Spring multipart、应用流式读取和存储四层的大小/超时限制。
5. 找一个全局异常处理器，确认参数错误、权限错误、业务冲突和未知异常是否能被前端区分。

- [Spring MVC Reference](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/web.html#mvc)
- [Spring MVC 参数校验](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/web.html#mvc-ann-validation)
- [Spring `@ControllerAdvice`](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/web.html#mvc-ann-exceptionhandler)
- [Spring Boot 2.7 Web 文档](https://docs.spring.io/spring-boot/docs/2.7.18/reference/htmlsingle/#web)

下一步：[Spring Boot 配置](./boot.md)、[认证与授权](./security.md)或[请求调用链示例](../../examples/request-walkthrough.md)。
