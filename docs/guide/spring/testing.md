# Spring 测试：从单元测试到接口集成测试

Spring 测试的目标不是“把所有测试都启动完整应用”，而是用合适的边界验证不同问题：纯 Java 逻辑用快速单元测试，Controller 参数绑定用 MVC 切片测试，数据库/配置/安全链路用集成测试。测试层次选错，会让反馈很慢，或者测到了错误的东西。

## 前端开发者需要先建立的测试映射

| 前端测试概念 | Spring 中相近的做法 | 主要验证 |
|---|---|---|
| 纯函数单测 | JUnit + Mockito，不启动 Spring | 分支、计算、异常和边界 |
| Vue 组件测试 | `@WebMvcTest` + MockMvc | 路由、参数绑定、校验和 HTTP 响应 |
| MSW/mock server | `@MockBean`、WireMock 或 Testcontainers | 隔离外部服务，验证调用契约 |
| E2E | `@SpringBootTest` +真实数据库/容器 | Bean 组合、配置、事务和跨层链路 |
| Cypress 网络断言 | MockMvc/WebTestClient | 状态码、Header、JSON 结构和错误响应 |

测试要回答的是“哪条契约被证明了”。测试名字应描述用户/业务行为，例如“订单已支付时重复回调保持幂等”，而不是只写“调用方法成功”。

## 测试层次与取舍

| 层次 | Spring 启动范围 | 速度 | 适合发现的问题 | 常见误区 |
|---|---|---:|---|---|
| 纯单元测试 | 0 个 Spring Bean | 快 | 业务分支、算法、异常 | Mock 太多导致测试与实现细节绑定 |
| MVC 切片 | MVC 相关 Bean | 中 | 路由、序列化、参数校验、异常处理 | 误以为已经验证数据库和完整安全链 |
| 数据切片 | JPA/JDBC 等数据访问 Bean | 中 | SQL 映射、事务、查询结果 | 使用 H2 与生产数据库语法差异 |
| 应用集成测试 | 大部分应用上下文 | 慢 | Bean 组合、配置、Filter、事务 | 每个测试都启动一次完整应用 |
| 真实环境集成 | 应用 + 容器化依赖 | 最慢 | 数据库、Redis、消息、外部契约 | 测试环境不稳定、数据未隔离 |

不要为了追求覆盖率给每个 getter 写测试。优先覆盖状态迁移、权限边界、异常转换、幂等、事务回滚和前端真正依赖的响应契约。

## 纯 Java 单元测试：先测试业务规则

下面是一个不启动 Spring 的示意。构造器注入让依赖容易替换，测试只关注 Service 的规则。

```java
// 集成片段：需要 JUnit 5 和 Mockito 依赖；示例展示测试边界，不依赖真实数据库。
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    // 用 Mock 替代数据库仓储，避免测试依赖外部状态。
    @Mock
    private PaymentRepository paymentRepository;

    // 被测对象通过构造器接收 Mock，测试依赖关系清晰。
    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("待支付订单收到重复成功通知时只更新一次")
    void duplicateNotificationIsIdempotent() {
        // 第一次调用返回“状态已从待支付变为已支付”。
        when(paymentRepository.markPaidIfPending("order-1")).thenReturn(1);

        // 第一次处理应该成功。
        paymentService.confirm("order-1");

        // 验证仓储按幂等方法调用，而不是先查再写造成竞态。
        verify(paymentRepository).markPaidIfPending("order-1");
    }
}
```

单元测试中的 Mock 只能证明当前调用协作关系，不代表真实 SQL、事务和 JSON 序列化一定正确。凡是测试替身改变了异常类型、返回结构或时序，都要补一条更高层测试。

## `@WebMvcTest`：只验证 HTTP 边界

`@WebMvcTest(OrderController.class)` 通常只加载 MVC 基础设施和指定 Controller，不会自动创建真实 Service、Mapper 和数据库连接。用 `@MockBean` 提供 Controller 的直接依赖。

```java
// 集成片段：需要 spring-boot-starter-test。
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // 用 MockBean 替换容器中的真实 Service，测试只关注 Controller 边界。
    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("创建订单成功返回 201 和订单编号")
    void createOrderReturnsCreated() throws Exception {
        // 固定 Service 返回值，避免测试依赖数据库。
        given(orderService.create(any(CreateOrderRequest.class)))
                .willReturn(new OrderView("order-1", "CREATED"));

        // 模拟前端发送的 JSON body，并断言 HTTP 状态和响应字段。
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"user-1\",\"items\":[{\"sku\":\"sku-1\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("order-1"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    @DisplayName("缺少必填字段返回 400")
    void invalidBodyReturnsBadRequest() throws Exception {
        // items 为空，Bean Validation 应在进入 Service 前拒绝请求。
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"user-1\",\"items\":[]}"))
                .andExpect(status().isBadRequest());

        // Service 不应被调用，因为请求在 MVC 校验阶段已经失败。
        verifyNoInteractions(orderService);
    }
}
```

这类测试能发现：路径映射写错、HTTP 方法错误、`Content-Type` 不匹配、DTO 校验遗漏、Jackson 字段名变化、全局异常处理器格式变化。它不能证明事务提交、权限数据库查询或真实 SQL 正确。

### MVC 测试的重点断言

建议至少覆盖：

1. 正常响应的状态码、Content-Type、关键字段和分页结构。
2. 路径参数、Query 参数、Header、JSON body 和 multipart 文件分别来自正确位置。
3. 缺字段、类型错误、超长文本和非法枚举得到 400。
4. 未登录得到 401，无功能权限得到 403，资源不存在得到 404，状态冲突得到 409。
5. Service 抛出业务异常时，全局 `@RestControllerAdvice` 返回稳定错误码，不暴露堆栈。

## `@SpringBootTest`：验证 Bean 组合和真实配置

```java
// 集成片段：会创建较完整的 Spring 容器，适合验证 Bean、配置和过滤器链组合。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderApplicationIntegrationTest {

    // 随机端口避免和本机开发服务冲突；测试客户端通过 @LocalServerPort 取得端口。
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("应用启动后健康检查可访问")
    void healthEndpointIsAvailable() {
        // 只断言测试环境真正需要的健康契约，不把全部 actuator 暴露给业务测试。
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

`@SpringBootTest` 启动失败时，优先查看根因而不是最后一行“context failed”。常见原因包括：测试 Profile 缺数据源、配置中心仍被访问、Bean 条件不满足、端口冲突和数据库迁移失败。测试配置要明确关闭或替换真实外部服务，不能把生产 Token 写进 `application-test.yml`。

## 测试切片注解怎么选

| 注解 | 加载范围 | 适合验证 |
|---|---|---|
| `@WebMvcTest` | MVC | Controller、JSON、校验、异常、Security 过滤器部分行为 |
| `@WebFluxTest` | WebFlux | Router/Controller、WebTestClient、响应式序列化 |
| `@DataJpaTest` | JPA 数据访问 | Repository、实体映射、SQL；通常带回滚事务 |
| `@JdbcTest` | JDBC 数据访问 | JdbcTemplate、RowMapper、SQL |
| `@JsonTest` | Jackson | 序列化、反序列化和字段兼容 |
| `@RestClientTest` | Boot 2.7：RestTemplate 等客户端；Spring 6.1+/Boot 3.2+ 才有 `RestClient` | 外部 HTTP 客户端和请求契约 |
| `@SpringBootTest` | 完整应用上下文 | 跨层集成和启动配置 |

MyBatis 项目没有一个覆盖所有场景的“自动切片”。可以用 `@SpringBootTest` 加测试数据库，也可以通过 `@ContextConfiguration` 只注册 Mapper、数据源和事务管理器；关键是让测试范围与要证明的问题一致。

## Mock、Spy、MockBean 的边界

- `@Mock`：Mockito 创建的纯替身，不会进入 Spring 容器。
- `@Spy`：包装真实对象，未 stub 的方法仍会执行真实代码；容易意外访问数据库或时间。
- `@MockBean`：在 Spring 测试上下文中用 Mock 替换同类型 Bean，Controller/Service 注入到的是这个替身。
- `@Import(TestConfig.class)`：向测试上下文加入明确的测试 Bean，适合固定时钟、ID 生成器或假客户端。
- `@TestConfiguration`：只在测试中提供配置，避免污染主应用配置。

不要把所有依赖都 Mock 掉。Mock 是为了隔离不属于当前测试边界的系统；如果一个测试 Mock 了 Controller、Service、Repository 和数据库，它只是在验证 Mock 的配置。

## 数据库测试：H2、真实数据库与 Testcontainers

| 方案 | 优点 | 风险/适用边界 |
|---|---|---|
| H2 内存库 | 快、配置简单 | SQL 方言、索引、事务隔离与生产库可能不同 |
| 共享测试库 | 接近生产 | 数据互相污染，测试顺序和环境稳定性差 |
| Testcontainers | 使用真实数据库镜像、隔离性好 | 启动较慢，需要 Docker 和镜像缓存 |
| Repository Mock | 最快 | 完全无法验证 SQL 和映射 |

如果生产使用 MySQL/PostgreSQL 的特有语法、JSON 类型、锁或索引，优先使用同版本 Testcontainers。测试数据应通过迁移脚本或固定 fixture 创建，测试结束后清理；不要依赖本机已有数据库状态。

```java
// 集成片段：需要 Testcontainers、JUnit 5 和对应数据库驱动。
@Testcontainers
@SpringBootTest
class OrderRepositoryIntegrationTest {

    // 使用与生产一致的数据库镜像，测试完成后自动停止容器。
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orders_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // 把随机映射出的 JDBC 地址注入 Spring，测试不写死端口。
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
```

Testcontainers 只解决依赖环境，不会自动保证数据隔离、迁移正确或测试并发安全。并行测试时要使用独立 schema、事务回滚或唯一数据前缀。

## 测试事务与回滚

测试方法上的 `@Transactional` 常用于在测试结束后回滚数据库修改，但不要把“测试回滚”误认为业务事务已经正确：

- 业务代码可能在另一个线程执行，异步任务不在测试线程事务中。
- 外部服务和消息发送不会因为数据库回滚而撤回。
- 使用不同事务管理器时，回滚的资源可能不是你以为的数据库。
- 提交前触发的事件与提交后的事件语义不同。

涉及支付、订单状态和 outbox 时，至少补一条真正提交事务的集成测试，验证事件/消息只在提交成功后发布。

## 前端联调契约测试

前端最关心的不是 Java 方法名，而是 HTTP 契约：

```ts
// 前端只依赖稳定的响应字段和错误码，不依赖后端内部类名。
const response = await api.get<Page<Order>>('/orders', {
  params: { pageNum: 1, pageSize: 20 },
})

if (response.data.code !== 0) {
  throw new Error(response.data.message)
}

// 测试或运行时都要明确 rows/total 的数据类型和空列表语义。
const rows = response.data.data?.rows ?? []
```

后端测试应固定 `code`、`message`、`data.rows`、`data.total` 的结构，避免 Controller 随意把 `List` 改成对象，或错误时仍返回 200 但缺少错误码。OpenAPI/契约测试可以继续验证字段类型、枚举和向后兼容。

## OpenAPI：把前后端约定变成可检查的接口描述

OpenAPI 是描述 HTTP API 的机器可读规范。它把路径、方法、参数、请求体、响应状态、数据模型和认证要求写成契约，解决“前端靠口头问字段、后端改完才发现类型不兼容”的问题。它与测试互补：OpenAPI 说明接口应该是什么样，契约测试验证实际响应是否符合说明。

常见有两种维护方式：**代码优先**从 Controller、DTO 和注解生成文档；**契约优先**先维护 YAML/JSON，再生成服务端接口、校验或前端类型。无论用哪种方式，都要规定唯一事实来源，并在 CI 中检查文档是否随接口变更更新。

下面的 OpenAPI 3 片段描述一个前端常见的分页接口。它是独立规范示例，不要求当前项目把 Swagger 2 配置直接替换成 OpenAPI 3。

```yaml
openapi: 3.0.3
info:
  title: 订单 API
  version: 1.0.0
paths:
  /api/orders:
    get:
      summary: 分页查询当前用户可见的订单
      security:
        - bearerAuth: [] # 说明该接口需要 Bearer Token；不代表文档会替后端完成鉴权。
      parameters:
        - name: pageNum
          in: query
          required: false
          schema:
            type: integer
            minimum: 1
            default: 1
        - name: pageSize
          in: query
          required: false
          schema:
            type: integer
            minimum: 1
            maximum: 200
            default: 20
      responses:
        '200':
          description: 查询成功；data.rows 为空数组表示当前页没有记录。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiResponsePageOrder'
        '401':
          description: 缺少或无效的登录凭证。
        '403':
          description: 身份有效，但没有查看这些订单的权限。
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
  schemas:
    ApiResponsePageOrder:
      type: object
      required: [code, message, data]
      properties:
        code:
          type: integer
          description: 业务码；与 HTTP 状态码的职责应在项目契约中明确。
        message:
          type: string
        data:
          type: object
          required: [rows, total]
          properties:
            rows:
              type: array
              items:
                $ref: '#/components/schemas/Order'
            total:
              type: integer
              format: int64
    Order:
      type: object
      required: [id, status]
      properties:
        id:
          type: string
          description: 大整数 ID 用字符串，避免 JavaScript Number 精度丢失。
        status:
          type: string
          enum: [PENDING, PAID, CANCELLED]
```

维护契约时特别检查：

- DTO 的必填、长度、数值范围和枚举是否与运行时校验一致；`@Schema`/Swagger 注解只描述文档，不会代替 Bean Validation。
- 分页响应是否固定 `rows` 与 `total` 的类型，空列表和超大 ID 是否明确。
- 每种错误的 HTTP 状态与业务错误码是否分别描述，401/403/404/409/422 等语义不要混成“都返回 200”。
- 文件上传写清 `multipart/form-data`、字段名、文件大小限制；下载写清二进制响应、Content-Disposition 和错误响应如何区分。
- 新增可选字段通常更易兼容；删除字段、改变类型、改变含义或收窄枚举都可能让旧前端失败。枚举新增也要确认 TypeScript 消费方是否允许未知值。

当前 `datacenter-cloud` checkout 采用 Springfox/Swagger 2：公共配置使用 `@EnableSwagger2` 与 `DocumentationType.SWAGGER_2`，Controller/DTO 可见 `@ApiOperation`、`@ApiModelProperty`。这是项目现状的静态代码证据，不代表每个环境都开放了文档端点。Boot 2.7 项目应先确认现有依赖和兼容性；若新项目选择 OpenAPI 3，再按目标 Spring Boot 版本选择兼容的 springdoc 版本，避免把两套扫描器随意混装。

本知识库的[接口参考](../../api/)是供前端阅读的人工整理材料；生成的 Swagger/OpenAPI 页面适合探索实时接口，但仍要通过 CI 检查文档、后端实际响应和前端类型之间的差异。

## 常见测试失败排查

| 现象 | 先看什么 | 常见原因 |
|---|---|---|
| `ApplicationContext` 启动失败 | 第一个 `Caused by` | Profile 配置缺失、条件 Bean 不满足、外部依赖未替换 |
| MockMvc 返回 404 | Controller 映射与测试路径 | 类级前缀、HTTP 方法或测试切片未加载目标 Controller |
| MockMvc 返回 415 | 请求 `Content-Type` | JSON/multipart/form-urlencoded 与参数注解不匹配 |
| `@MockBean` 没有生效 | 容器中实际 Bean 类型 | 注入的是另一个实现、Qualifier 名称不一致 |
| 事务测试数据未回滚 | 线程、事务管理器、数据库 | 异步线程、手工提交、多个数据源 |
| 测试偶现失败 | 测试顺序和共享状态 | 静态缓存、共享数据库、时间/随机数未固定 |
| 只在生产 SQL 失败 | 生产数据库方言 | H2 与 MySQL/PostgreSQL 语法、索引和约束不同 |

## 练习路线

1. 为一个订单 Controller 写 `@WebMvcTest`，覆盖成功、参数校验失败、无权限和业务冲突四条路径。
2. 将 Service 的依赖从字段注入改成构造器注入，再用纯 Mockito 单元测试覆盖重复支付。
3. 用 Testcontainers 启动与生产同版本数据库，验证分页、唯一索引和乐观锁冲突。
4. 写一条 `@SpringBootTest`，确认测试 Profile 不会连接生产配置中心，并能访问健康检查。
5. 将前端 Network 中的一次真实响应保存为 fixture，测试后端错误响应是否仍保持字段兼容。

下一步：[数据访问与事务](./data-access.md)、[异步、事件、缓存与集成](./integration.md)、[可观测性与生产运行](./observability.md)。
