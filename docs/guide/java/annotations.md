# 注解大全：从 Java 元数据到 Spring 框架行为

注解（Annotation）是附着在类、方法、字段、参数等程序元素上的**元数据**。它本身通常不直接执行代码；编译器、注解处理器、框架扫描器或运行时反射读取注解后，才可能把它转换成检查、注册、代理、参数绑定、校验或其他行为。

前端可以先用三个类比建立直觉：

| 前端概念 | Java 注解中相近的理解 | 关键差异 |
|---|---|---|
| TypeScript decorator | 给类/方法附加元数据或包装行为 | Java 注解声明和注解处理通常是分开的，写上注解不等于已经执行逻辑 |
| Vue 组件 options | 用声明描述组件能力、配置和生命周期 | Spring 还要通过容器扫描、条件装配和代理决定是否生效 |
| 路由表元数据 | `@GetMapping` 描述 HTTP 方法与路径 | 真正的请求处理还依赖 DispatcherServlet、参数解析器和消息转换器 |
| ESLint/编译器标记 | `@Override`、`@Deprecated` 让工具检查源码 | 有些注解只在编译期存在，运行时反射看不到 |

## 先回答：注解到底解决什么问题

如果没有注解，框架也可以依靠 XML、配置文件、命名约定或手工注册完成工作，但配置和代码容易分离。注解把“这个类是什么角色”“这个方法接收什么请求”“这个字段需要什么校验”放在声明附近，减少重复配置。

注解主要解决四类问题：

1. **让工具检查代码**：`@Override` 防止方法签名写错，`@FunctionalInterface` 检查接口是否仍是单抽象方法接口。
2. **描述框架角色**：`@Service` 表明类是业务组件，`@RestController` 表明类提供 HTTP 接口。
3. **声明运行时约束**：`@NotBlank`、`@Transactional`、`@PreAuthorize` 分别描述输入校验、事务边界和访问权限。
4. **生成或改变代码结构**：Lombok 在编译期生成 getter、构造器和日志字段；MyBatis 根据 Mapper 注解注册 SQL 映射。

需要记住四个不同步骤：

```text
声明注解 -> 注解被保存在源码/字节码/运行时 -> 某个工具或框架读取它 -> 读取者决定具体行为
```

例如，给普通 Java 对象写上 `@Transactional` 不会自动开启事务；对象必须由 Spring 创建，调用还要经过事务代理，事务管理器也必须配置正确。注解存在只是“声明”，不是“执行完成”的证明。

## 注解的基本语法

### 使用注解

```java
// 类级注解：描述这个类由 Spring 作为业务组件管理。
@Service
public class OrderService {

    // 方法级注解：声明这个方法接收 GET /orders/{id} 请求。
    @GetMapping("/orders/{id}")
    public OrderView find(@PathVariable("id") Long id) {
        // 方法体仍然需要实现真正的业务逻辑。
        return queryOrder(id);
    }
}
```

注解可以没有属性（`@Override`），也可以设置属性（`@RequestMapping(path = "/orders", method = RequestMethod.GET)`）。当注解只有一个名为 `value` 的属性时，可以省略属性名，例如 `@RequestParam("status")`；为了减少误读，复杂注解建议明确写出属性名。

### 声明自定义注解

```java
// @interface 是声明注解类型的语法，不是声明普通接口。
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    // 没有默认值的属性必须在使用时提供。
    String action();

    // 有默认值的属性可以省略。
    String operator() default "system";
}
```

注解属性的类型只能是有限集合：基本类型、`String`、`Class`、枚举、注解，或这些类型的一维数组。不能把 `List`、任意对象或动态计算结果直接作为注解属性。

## 元注解：决定注解能放哪、保留多久

元注解是“用来描述注解的注解”。最常用的是 `@Target`、`@Retention`、`@Documented`、`@Inherited` 和 `@Repeatable`。

### `@Target`：限制使用位置

| `ElementType` | 允许放置的位置 | 示例 |
|---|---|---|
| `TYPE` | 类、接口、枚举、注解 | `@Service`、`@Entity` |
| `METHOD` | 方法 | `@Transactional`、`@Test` |
| `FIELD` | 字段 | `@Autowired`、`@Value` |
| `PARAMETER` | 方法参数 | `@RequestParam`、`@PathVariable` |
| `CONSTRUCTOR` | 构造器 | `@Autowired` |
| `ANNOTATION_TYPE` | 其他注解类型 | Spring 的组合注解 |
| `TYPE_USE` | 类型使用位置 | `List<@NonNull String>` |
| `PACKAGE` / `MODULE` | 包或模块声明 | 少见的包级元数据 |

`@Target` 越精确，误用越早暴露。自定义权限注解如果只适合方法，就不要允许放到字段上，否则阅读者会误以为字段也会产生权限检查。

### `@Retention`：决定生命周期

| 策略 | 保留到哪里 | 能否通过运行时反射读取 | 常见用途 |
|---|---|---:|---|
| `SOURCE` | 只在源码和编译阶段 | 否 | 编译器提示、代码生成前的标记 |
| `CLASS` | 写入 `.class` 字节码 | 通常不能 | 字节码工具、某些离线处理 |
| `RUNTIME` | 运行时仍可见 | 是 | Spring、JUnit、Bean Validation、反射框架 |

需要运行时扫描的自定义注解通常必须写 `@Retention(RetentionPolicy.RUNTIME)`。漏写时默认策略是 `CLASS`，页面代码看起来没有问题，但 `method.getAnnotation(...)` 会返回 `null`。

### `@Documented`、`@Inherited`、`@Repeatable`

- `@Documented`：让 Javadoc 文档默认展示该注解。它不会让注解自动执行。
- `@Inherited`：允许**类级别**注解被子类继承。它对方法和字段无效；子类重写方法后，不要假设父类方法上的注解仍自动可见。
- `@Repeatable`：允许同一个注解在同一位置重复使用，例如给一个接口声明多个限流规则。读取时要使用 `getAnnotationsByType`，不能只调用单值的 `getAnnotation`。

```java
// 类级权限注解可以被子类继承；方法级注解不会因为这个元注解而自动继承。
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Feature {
    String value();
}
```

### `@FunctionalInterface`

`@FunctionalInterface` 是 Java 标准注解，要求接口最多只有一个抽象方法。它让 Lambda 的目标接口更安全；默认方法和 `Object` 的公共方法不计入抽象方法数量。

```java
// 编译器会检查该接口是否仍然能作为 Lambda 的目标类型。
@FunctionalInterface
interface OrderIdGenerator {
    // 唯一抽象方法可以被 Lambda 实现。
    String generate();
}
```

## Java 标准库常用注解

| 注解 | 解决的问题 | 什么时候用 | 常见坑 |
|---|---|---|---|
| `@Override` | 检查是否真的重写父类/接口方法 | 每次重写实例方法时 | 签名写错时不加它会悄悄变成重载 |
| `@Deprecated` | 标记不建议继续使用的 API | 需要迁移到新 API 时 | 只标记不写迁移说明，调用方不知道替代方案 |
| `@SuppressWarnings` | 局部关闭已知编译器警告 | 警告经过审查且无法消除时 | 放在整个类上会掩盖真正的问题 |
| `@SafeVarargs` | 说明泛型可变参数实现不会产生堆污染 | `final`、`static` 或 `private` 方法 | 不能用来掩盖不安全的数组写入 |
| `@FunctionalInterface` | 保证接口适合 Lambda | 定义回调、策略和函数接口时 | 添加第二个抽象方法会导致编译失败 |
| `@Native` | 标记常量可能被本地代码使用 | JNI/标准库常量场景 | 普通业务代码几乎不需要 |
| `@Serial` | 检查序列化相关成员 | Java 14+ | Java 11 项目不能把它当作基础示例依赖 |

### `@Override`：让重写错误尽早失败

```java
// 父接口规定方法名为 export，参数为空。
interface Exporter {
    String export();
}

final class CsvExporter implements Exporter {

    // 如果误写成 exports，编译器会立即报错，而不是运行时才发现接口调用不对。
    @Override
    public String export() {
        return "id,name";
    }
}
```

### `@Deprecated`：给 API 演进留下迁移路径

```java
final class UserClient {

    /**
     * 旧方法仍暂时保留，调用编译器会给出弃用警告。
     * @deprecated 请使用带超时参数的方法，避免网络请求无限等待。
     */
    @Deprecated
    public String loadUser(String id) {
        return loadUser(id, 3000);
    }

    // 新方法明确提供超时，调用者能看到新的契约。
    public String loadUser(String id, long timeoutMillis) {
        return "user=" + id + ", timeout=" + timeoutMillis;
    }
}
```

### `@SuppressWarnings`：只压制已理解的局部问题

```java
final class LegacyAdapter {

    // 这里明确知道旧库返回原始类型，警告范围只限制在这个方法。
    @SuppressWarnings("unchecked")
    public static java.util.List<String> readLegacy(java.util.List value) {
        // 调用前应确认旧库真实返回的元素类型，否则只是把风险藏起来。
        return (java.util.List<String>) value;
    }
}
```

## Spring IoC：注解如何让对象进入容器

Spring IoC 注解主要回答“谁创建对象、谁注入依赖、对象什么时候初始化”。这些注解只有在 Spring `ApplicationContext` 扫描或显式注册它们时才生效。

| 注解 | 解决的问题 | 使用场景 | 注意点 |
|---|---|---|---|
| `@Component` | 注册通用组件 | 工具、适配器、策略类 | 必须位于组件扫描边界内 |
| `@Service` | 表达业务服务角色 | Service 实现类 | 本质上是语义化的 `@Component` |
| `@Repository` | 表达持久层角色并支持异常转换 | DAO/Repository | 不等同于 MyBatis 的 `@Mapper` |
| `@Controller` | 注册 MVC 控制器 | 返回视图或配合 `@ResponseBody` | 纯 JSON 接口通常用 `@RestController` |
| `@RestController` | Controller 方法默认返回响应体 | REST API | 等价于 `@Controller` + `@ResponseBody` |
| `@Configuration` | 声明配置类 | 第三方客户端、基础设施 Bean | `@Bean` 方法由容器管理 |
| `@Bean` | 显式注册一个对象 | 第三方类或复杂初始化 | 方法返回对象才会进入容器 |
| `@ComponentScan` | 指定扫描范围 | 多模块或非默认包结构 | 扫描过窄会导致 Bean 不存在 |
| `@Autowired` | 自动注入依赖 | 构造器/字段/方法注入 | 多个候选实现时仍会歧义 |
| `@Qualifier` | 按名称或限定符选择实现 | 多实现策略 | 名称修改会影响注入 |
| `@Primary` | 指定默认实现 | 多实现中有明确默认项 | 只解决默认选择，不能替代场景语义 |
| `@Lazy` | 延迟创建 Bean | 启动慢、可选依赖 | 把启动失败延后到第一次使用 |
| `@Scope` | 改变 Bean 作用域 | request、session、prototype | 作用域代理和线程边界要一起看 |
| `@Profile` | 按环境启用 Bean | dev/test/prod 差异 | 还要确认 Active Profile |
| `@Value` | 注入单个配置值 | 小量简单配置 | 类型、默认值和敏感信息边界要清楚 |
| `@ConfigurationProperties` | 批量绑定结构化配置 | 数据源、客户端、业务配置 | 适合类型安全和校验 |
| `@PostConstruct` / `@PreDestroy` | 生命周期初始化/清理 | 建立缓存、关闭客户端 | Boot 2.7 使用 `javax.annotation` |

### 构造器注入优先

```java
@Service
public class OrderService {

    // final 表明依赖在构造时确定，测试时可以直接传入替身实现。
    private final OrderRepository orderRepository;

    // 单构造器在 Spring 中可以省略 @Autowired；显式写出也便于初学者理解。
    public OrderService(OrderRepository orderRepository) {
        // 依赖缺失会在创建 Bean 时失败，而不是第一次调用业务时才失败。
        this.orderRepository = orderRepository;
    }
}
```

构造器注入解决了字段注入的几个问题：依赖不可隐藏、对象可以保持有效状态、单元测试不需要反射设置字段、循环依赖更早暴露。字段注入并非“不能运行”，但在大型项目中不利于看清依赖图。

### `@Component`、`@Service`、`@Repository`、`@Controller` 的区别

它们都属于组件扫描体系，主要差异是**角色语义**和少量框架附加行为：

```java
// 通用组件：例如价格计算策略。
@Component
public class PriceCalculator {
}

// 业务服务：表达订单流程和事务边界通常放在这里。
@Service
public class OrderApplicationService {
}

// 持久层组件：Spring 对部分持久化异常提供统一转换。
@Repository
public class OrderRepository {
}

// REST 控制器：方法返回值默认写入 HTTP 响应体。
@RestController
public class OrderController {
}
```

不要仅根据注解名称推断调用链。还要检查包是否被扫描、Bean 是否被条件注解排除、是否存在多个实现，以及当前调用拿到的是普通对象还是代理对象。

### `@Bean`：适合第三方对象和显式初始化

```java
@Configuration
public class HttpClientConfig {

    // 第三方 HttpClient 类不能修改源码添加 @Component，因此用 @Bean 注册。
    @Bean
    public java.net.http.HttpClient httpClient() {
        // 对象由 Spring 管理，其他 Bean 可以通过构造器注入它。
        return java.net.http.HttpClient.newBuilder()
                // 设置连接超时时间，避免下游故障长期占用线程。
                .connectTimeout(java.time.Duration.ofSeconds(3))
                // 构建不可变客户端。
                .build();
    }
}
```

`@Bean` 方法可以表达对象组装、命名、条件和生命周期。不要把复杂业务流程都塞进配置类；配置类负责装配，业务规则应放到 Service。

### `@Qualifier` 与 `@Primary`：解决多个实现

```java
public interface NotificationSender {
    void send(String userId, String message);
}

@Service("emailSender")
class EmailSender implements NotificationSender {
    @Override
    public void send(String userId, String message) {
        // 省略邮件服务调用。
    }
}

@Service("smsSender")
class SmsSender implements NotificationSender {
    @Override
    public void send(String userId, String message) {
        // 省略短信服务调用。
    }
}

@Service
class NotificationService {

    private final NotificationSender sender;

    // 场景明确要求短信时按限定名选择，不依赖某个 Bean 恰好是 Primary。
    public NotificationService(@Qualifier("smsSender") NotificationSender sender) {
        this.sender = sender;
    }
}
```

`@Primary` 适合有稳定默认实现的情况；`@Qualifier` 更适合业务场景明确的选择。看到 `NoUniqueBeanDefinitionException` 时，先列出候选 Bean，再决定是加限定符、指定默认实现，还是删除错误注册。

### 配置绑定：`@Value` 与 `@ConfigurationProperties`

```java
// application.yml：
// order:
//   client:
//     base-url: https://api.example.com
//     connect-timeout: 3s

@ConfigurationProperties(prefix = "order.client")
public class OrderClientProperties {

    // 配置绑定到结构化字段，便于统一校验和传递。
    private java.net.URI baseUrl;

    // Duration 能保留时间单位，比裸 long 更不容易误解。
    private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(3);

    // getter/setter 供 Boot 2.7 默认绑定器使用。
    public java.net.URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(java.net.URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public java.time.Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
```

Boot 2.7 中可以在启动类或配置类上使用 `@EnableConfigurationProperties(OrderClientProperties.class)`，也可以使用配置处理器和扫描方式注册。`@Value("${order.client.base-url}")` 适合一个简单值；配置项成组出现时优先使用 `@ConfigurationProperties`。

### 生命周期注解

```java
@Component
public class LocalCache {

    // Boot 2.7 / Spring 5 常用 javax.annotation 包；Boot 3 改为 jakarta.annotation。
    @javax.annotation.PostConstruct
    public void load() {
        // Bean 依赖注入完成后执行，例如加载只读字典。
    }

    @javax.annotation.PreDestroy
    public void close() {
        // 容器关闭前释放线程池、连接或文件句柄。
    }
}
```

不要在 `@PostConstruct` 中调用尚未准备好的外部服务，也不要把耗时初始化悄悄放进启动关键路径。若初始化失败，应让启动失败还是降级运行，要按业务可用性明确设计。

## Spring Boot 与基础设施注解

Spring Boot 的注解更多是在“开启一组基础设施能力”或“决定自动配置是否参与”。它们通常不是业务注解，排查时要结合启动类、依赖 starter、Profile 和条件配置一起看。

| 注解 | 解决的问题 | 适用场景 | 常见坑 |
|---|---|---|---|
| `@SpringBootApplication` | 启动应用并组合常用配置 | Boot 应用入口 | 扫描根包不正确会漏 Bean |
| `@EnableAutoConfiguration` | 按 classpath 和条件加载自动配置 | 自定义启动方式 | starter/条件不满足时不会装配 |
| `@ConfigurationPropertiesScan` | 扫描配置属性类 | 多模块配置绑定 | 包范围过窄导致属性 Bean 不存在 |
| `@EnableFeignClients` | 扫描并注册 Feign 客户端 | 微服务 HTTP 调用 | 客户端包未扫描、服务名/地址配置错误 |
| `@EnableScheduling` | 启用定时任务调度 | 周期刷新、清理任务 | 多实例会重复执行，需分布式协调 |
| `@Scheduled` | 声明定时任务表达式 | 固定延迟、Cron | 任务重入、时区和异常处理容易被忽略 |
| `@EnableAsync` | 启用 `@Async` 代理 | 后台异步工作 | 未配置线程池时可能使用不合适的默认执行器 |
| `@EnableCaching` | 启用缓存注解 | `@Cacheable` 等 | 缓存 key、失效和一致性仍需设计 |
| `@ConditionalOnProperty` | 按配置开关装配 Bean | 灰度、可选集成 | 配置名和默认值写错会导致功能不见 |
| `@ConditionalOnMissingBean` | 只有没有候选 Bean 时才装配 | 提供可覆盖默认实现 | 误判容器中已有 Bean 的创建时机 |
| `@Profile` | 按环境选择 Bean/配置 | dev、test、prod | Active Profile 与文件名必须对应 |

### `@SpringBootApplication` 是三个注解的组合

```java
// 通常放在应用的根包，使默认组件扫描覆盖 controller/service/mapper 配置。
@org.springframework.boot.autoconfigure.SpringBootApplication
public class DashboardApplication {

    public static void main(String[] args) {
        // 启动 SpringApplication，创建容器并应用自动配置。
        org.springframework.boot.SpringApplication.run(DashboardApplication.class, args);
    }
}
```

它大致组合了：

- `@SpringBootConfiguration`：标记这是 Boot 配置入口。
- `@EnableAutoConfiguration`：根据 classpath、配置和条件装配框架能力。
- `@ComponentScan`：从启动类所在包向下扫描组件。

启动类放在过深的业务子包里，可能导致旁边模块的 `@Service`、`@Controller` 或配置类没有被扫描。与其在各处随意补扫描路径，不如先确认应用包结构和模块边界。

### `@EnableFeignClients`：声明远程客户端扫描

```java
@org.springframework.cloud.openfeign.EnableFeignClients(
        basePackages = "com.example.dashboard.client")
@org.springframework.boot.autoconfigure.SpringBootApplication
public class DashboardApplication {
    // Feign 接口由框架生成代理；调用方注入的是代理，不是手工 new 的实现。
}

@org.springframework.cloud.openfeign.FeignClient(
        name = "user-service",
        path = "/api/users")
interface UserClient {

    // Feign 根据映射发起 HTTP 请求；超时、重试、认证和错误解码要单独配置。
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    UserView find(@org.springframework.web.bind.annotation.PathVariable("id") String id);
}
```

看到 `@FeignClient` 时，要继续检查服务发现/URL 配置、连接与读取超时、重试策略、错误解码以及下游权限。接口声明看起来像本地方法，但真正的失败可能来自 DNS、连接池、网关或远程服务。

### `@Scheduled`：定时任务不是可靠消息

```java
@org.springframework.stereotype.Component
public class ExpiredOrderCleaner {

    // 启动类或配置类需要启用 @EnableScheduling。
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${order.cleaner.delay:60000}")
    public void clean() {
        // 每次执行前要考虑幂等、超时、异常日志和任务是否可重入。
    }
}
```

单机定时任务适合本机刷新或清理；服务部署多个副本时，每个副本都可能执行一次。涉及扣款、发货、结算等业务，应使用分布式锁、任务调度平台或消息队列，并记录任务执行状态。Cron 表达式还要确认时区、夏令时和应用暂停后的补偿语义。

### 条件装配注解：为什么 Bean 在某个环境消失

```java
@org.springframework.context.annotation.Configuration
public class TraceConfig {

    // 只有 app.trace.enabled=true 时，才注册这个 Bean。
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.trace.enabled",
            havingValue = "true",
            matchIfMissing = false)
    @org.springframework.context.annotation.Bean
    public TraceExporter traceExporter() {
        // 返回真正交给容器管理的实现。
        return new TraceExporter();
    }
}
```

排查 `NoSuchBeanDefinitionException` 时，除了看 `@ComponentScan`，还要看 `@Profile`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 和 starter 是否存在。自动配置“不生效”往往是条件没有满足，而不是注解语法错误。

## Spring MVC：注解怎样把 HTTP 变成 Java 参数

MVC 注解解决的是“URL、Query、Header、Cookie、JSON 和文件如何绑定到方法参数，以及返回值如何转换成 HTTP 响应”。前端调试时，先记录最终请求方法、URL、Query、请求头、`Content-Type`、Body 和响应状态码。

| 注解 | 绑定位置/作用 | 前端请求对应 |
|---|---|---|
| `@RequestMapping` | 类/方法通用映射 | 路由前缀和方法组合 |
| `@GetMapping` | GET 映射 | `axios.get` |
| `@PostMapping` | POST 映射 | `axios.post` |
| `@PutMapping` | PUT 映射 | `axios.put` |
| `@DeleteMapping` | DELETE 映射 | `axios.delete` |
| `@PatchMapping` | PATCH 映射 | `axios.patch` |
| `@PathVariable` | 路径占位符 | `/orders/1001` 中的 `1001` |
| `@RequestParam` | Query 参数 | `/orders?status=PAID` |
| `@RequestHeader` | 请求头 | `Authorization`、`X-Request-Id` |
| `@CookieValue` | Cookie | 浏览器自动发送的会话 Cookie |
| `@RequestBody` | JSON/XML 等请求体 | `data: { ... }` |
| `@RequestPart` | multipart 的某一部分 | 文件或表单字段 |
| `@ModelAttribute` | 表单/Query 绑定对象 | `application/x-www-form-urlencoded` |
| `@ResponseBody` | 返回值写响应体 | JSON、文本或文件流 |
| `@ResponseStatus` | 固定响应状态 | 创建成功返回 201 等 |
| `@CrossOrigin` | 配置跨域响应头 | 浏览器 CORS 校验 |
| `@RestControllerAdvice` | 全局异常/响应处理 | 统一错误响应 |
| `@ExceptionHandler` | 处理指定异常 | 400/409/500 转换 |

### 一个同时展示多种参数来源的 Controller

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    // GET /api/orders/1001?includeItems=true
    @GetMapping("/{id}")
    public OrderView get(
            // 从路径中的 {id} 读取订单编号。
            @PathVariable("id") Long id,
            // 从 Query 读取可选开关，并提供默认值。
            @RequestParam(value = "includeItems", defaultValue = "false") boolean includeItems,
            // 从 Header 读取链路 ID；required=false 表示允许调用方不提供。
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        // 业务方法收到的是已经完成基础类型转换的 Java 参数。
        return new OrderView(id, includeItems, requestId);
    }

    // POST /api/orders，Content-Type: application/json
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public OrderView create(
            // Jackson 将 JSON body 反序列化为 CreateOrderRequest。
            @RequestBody CreateOrderRequest request) {
        // 实际项目应在 Service 中保存并返回资源。
        return new OrderView(1001L, true, "created");
    }
}
```

前端调用可以写成：

```ts
// 路径参数和 Query 参数放在 URL/config 中。
await axios.get('/api/orders/1001', {
  params: { includeItems: true },
  headers: { 'X-Request-Id': crypto.randomUUID() },
})

// JSON body 放在 data 中，Axios 默认会设置 application/json。
await axios.post('/api/orders', {
  customerId: 'u-100',
  items: [{ sku: 'sku-1', quantity: 2 }],
})
```

常见错误是把 Query 参数放进 JSON body，或者给 `@RequestBody` 请求发送了错误的 `Content-Type`。这类问题通常在进入 Controller 前就返回 400；先比较 Network 面板中的请求与 Java 参数注解。

### 文件与表单：`@RequestPart`、`@ModelAttribute`

```java
@PostMapping(value = "/imports", consumes = "multipart/form-data")
public ImportResult importFile(
        // name 必须和前端 FormData.append 的字段名一致。
        @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
        // 普通表单字段可以绑定到 DTO。
        @ModelAttribute ImportOptions options) {
    // 先校验文件名、大小和内容类型，再保存到受控目录或对象存储。
    return new ImportResult(file.getOriginalFilename(), options.getMode());
}
```

```ts
// multipart 请求不要手工拼 boundary，让浏览器/axios 设置。
const form = new FormData()
form.append('file', file)
form.append('mode', 'replace')
await axios.post('/api/orders/imports', form)
```

`@RequestPart` 适合文件和 multipart 内的 JSON 部分；`@RequestParam` 适合简单 Query/form 字段。大文件上传还需要限制大小、校验内容、断点续传和合并幂等，不能只依赖参数注解。

### 全局异常：`@RestControllerAdvice` 与 `@ExceptionHandler`

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    // 捕获 Bean Validation 或参数绑定失败，返回稳定的 400 结构。
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @org.springframework.http.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException exception) {
        // 实际项目可以把字段错误整理成前端表单可消费的 map。
        return new ErrorResponse("INVALID_ARGUMENT", "请求参数不合法");
    }

    // 业务冲突应与系统故障区分，前端才能决定提示或刷新。
    @ExceptionHandler(OrderAlreadyPaidException.class)
    @org.springframework.http.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(OrderAlreadyPaidException exception) {
        return new ErrorResponse("ORDER_ALREADY_PAID", exception.getMessage());
    }
}
```

全局处理器不要把所有异常都转换成 200。HTTP 状态码应保留边界语义：400 输入错误、401 未认证、403 无权限、404 资源不存在、409 状态冲突、413 请求过大、500 服务故障。统一响应包装可以保留，但不能抹掉状态码和日志上下文。

## Bean Validation：输入格式、业务规则和权限不是一回事

Bean Validation 注解描述字段或方法参数的约束。它适合做格式和基础边界检查；库存是否足够、订单是否属于当前用户、优惠券是否可用，仍然是业务和授权逻辑。

| 注解 | 检查内容 | 典型场景 |
|---|---|---|
| `@NotNull` | 不能为 `null` | ID、对象、时间 |
| `@NotBlank` | 字符串不能为 null/空白 | 姓名、标题、Token |
| `@NotEmpty` | 集合/数组/字符串不能空 | 批量 ID 列表 |
| `@Size` | 长度/元素数量 | 密码长度、批量上限 |
| `@Min` / `@Max` | 数值上下限 | 页码、年龄 |
| `@Positive` / `@PositiveOrZero` | 正数/非负数 | 金额、数量 |
| `@Email` | 邮箱格式 | 联系邮箱 |
| `@Pattern` | 正则格式 | 手机号、编码 |
| `@Past` / `@Future` | 时间过去/未来 | 出生日期、预约时间 |
| `@Valid` | 触发嵌套对象校验 | DTO 内的 Address |
| `@Validated` | 分组和方法级校验 | 创建/更新使用不同规则 |

### DTO 校验示例

```java
public class CreateOrderRequest {

    // null 不允许；包装类型 Long 才能区分“未填写”和 0。
    @javax.validation.constraints.NotNull(message = "客户不能为空")
    private Long customerId;

    // NotBlank 同时拒绝 null、空字符串和全空格字符串。
    @javax.validation.constraints.NotBlank(message = "备注不能为空")
    @javax.validation.constraints.Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;

    // 集合本身和集合元素都要校验；@Valid 负责继续进入元素对象。
    @javax.validation.constraints.NotEmpty(message = "订单项不能为空")
    @javax.validation.Valid
    private java.util.List<OrderItemRequest> items;

    // getter/setter 省略；Jackson/Validation 需要按项目配置访问字段或方法。
}

public class OrderItemRequest {

    // SKU 必须有实际内容。
    @javax.validation.constraints.NotBlank
    private String sku;

    // 数量至少为 1，防止负数或 0 穿透到库存逻辑。
    @javax.validation.constraints.Positive
    private Integer quantity;
}
```

Controller 上用 `@Valid` 触发 DTO 校验：

```java
@PostMapping
public OrderView create(
        // 校验失败时通常抛 MethodArgumentNotValidException。
        @javax.validation.Valid @RequestBody CreateOrderRequest request) {
    // 通过格式校验后仍需在 Service 中检查价格、库存和权限。
    return orderService.create(request);
}
```

`@Valid` 主要触发嵌套校验；`@Validated` 是 Spring 扩展，支持校验分组和方法级校验。常见误区包括：

- 把 `@NotNull` 放在 `int` 上：基本类型不能为 null，应改用 `Integer` 或只检查范围。
- 只校验字符串格式，不校验业务语义；例如手机号格式正确不代表用户有权限操作订单。
- 只校验外层 DTO，不加 `@Valid`，导致嵌套商品项不校验。
- 把错误信息直接拼接内部异常，造成敏感信息泄露。

## AOP、事务、异步与缓存注解

这些注解最容易让初学者产生“写上就执行”的误解。它们大多依赖 Spring 代理：调用者拿到代理对象后，代理在目标方法前后插入事务、日志、权限、异步或缓存逻辑。

| 注解 | 解决的问题 | 常见边界 |
|---|---|---|
| `@Aspect` | 声明切面类 | 需要启用 AOP 且类是 Bean |
| `@Pointcut` | 复用匹配规则 | 切点太宽会误伤基础设施 |
| `@Before` / `@After` | 方法前/后执行逻辑 | `@After` 无论成功失败都可能执行 |
| `@AfterReturning` | 只处理正常返回 | 异常路径不会进入 |
| `@AfterThrowing` | 只处理异常 | 不负责吞掉异常 |
| `@Around` | 完整控制调用 | 忘记 `proceed()` 会直接跳过目标方法 |
| `@Order` | 排列多个切面 | 数字越小通常优先级越高，需以配置为准 |
| `@Transactional` | 声明事务边界 | 代理、自调用、异常类型、数据库引擎都会影响结果 |
| `@Async` | 把任务提交到异步执行器 | 不等于可靠消息队列，不自动继承事务 |
| `@Cacheable` | 缓存方法结果 | Key、空值、失效和一致性要设计 |
| `@CachePut` | 执行方法并更新缓存 | 方法结果必须与缓存语义一致 |
| `@CacheEvict` | 删除缓存 | 更新数据库和删除缓存的顺序要考虑失败 |

### `@Transactional` 的真实边界

```java
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // 由外部 Bean 通过代理调用时，事务通常在方法进入前开启。
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public void markPaid(String orderId) throws Exception {
        // 保存支付状态。
        paymentRepository.markPaid(orderId);

        // 抛出异常时是否回滚，还要看异常类型、事务管理器和数据库引擎。
        callRiskyExternalSystem(orderId);
    }

    private void callRiskyExternalSystem(String orderId) throws Exception {
        // 示例只展示边界；真实外部调用不应长时间占用数据库事务连接。
    }
}
```

需要排查以下情况：

1. 类是否由 Spring 管理，调用方拿到的是不是代理。
2. 是否通过同一个类的 `this.markPaid()` 自调用；自调用可能绕过代理。
3. 异常是否被方法内部 catch 后吞掉，或者不满足默认回滚规则。
4. 数据库表和连接是否真正支持事务，是否存在手工提交。
5. 事务里是否执行了很慢的 HTTP/文件操作，导致连接长期占用。

### `@Async` 与线程边界

```java
@Service
public class ExportService {

    // 需要在配置类上启用 @EnableAsync，并配置有界线程池。
    @org.springframework.scheduling.annotation.Async("exportExecutor")
    public java.util.concurrent.CompletableFuture<String> export(String reportId) {
        // 方法会在异步线程执行；调用方不能假设当前事务和 ThreadLocal 自动传过去。
        return java.util.concurrent.CompletableFuture.completedFuture(
                "exported=" + reportId);
    }
}
```

异步任务适合短暂、可重试、可观测的后台工作，例如生成报表或发送通知；需要可靠投递、持久化、顺序和消费重试时，应考虑消息队列。`@Async` 自身也需要处理线程池拒绝、异常、取消和应用关闭。

### 缓存注解与一致性

```java
@Service
public class ProductService {

    // 首次查询执行方法并按 productId 缓存结果。
    @org.springframework.cache.annotation.Cacheable(
            cacheNames = "product",
            key = "#productId")
    public ProductView find(String productId) {
        return loadFromDatabase(productId);
    }

    // 更新后执行方法，并用返回值刷新同一个缓存 key。
    @org.springframework.cache.annotation.CachePut(
            cacheNames = "product",
            key = "#result.id")
    public ProductView update(ProductView input) {
        return saveToDatabase(input);
    }

    // 删除商品时清理缓存；数据库更新失败时不要提前清理错误状态。
    @org.springframework.cache.annotation.CacheEvict(
            cacheNames = "product",
            key = "#productId")
    public void delete(String productId) {
        deleteFromDatabase(productId);
    }

    private ProductView loadFromDatabase(String id) { return new ProductView(id); }
    private ProductView saveToDatabase(ProductView value) { return value; }
    private void deleteFromDatabase(String id) { }
}
```

缓存不是数据库的替代品。要明确缓存 key、TTL、空值、并发击穿、更新失败、跨实例失效和敏感数据边界。`@Cacheable` 同样依赖代理，同类内部直接调用可能绕过缓存。

## Spring Security：认证、功能权限、资源权限分层

| 注解 | 作用 | 适用场景 |
|---|---|---|
| `@EnableWebSecurity` | 启用 Web Security 配置 | Spring Security 6 以前常见的显式配置 |
| `@EnableGlobalMethodSecurity` | 启用方法级安全注解 | Boot 2.7 / Security 5 |
| `@PreAuthorize` | 方法执行前表达式授权 | 角色、权限码、参数关系 |
| `@PostAuthorize` | 方法返回后授权 | 需要依据返回对象判断 |
| `@Secured` | 简单角色授权 | 规则简单的旧项目 |
| `@RolesAllowed` | JSR-250 角色授权 | 统一使用标准角色注解时 |
| `@AuthenticationPrincipal` | 取当前登录用户 | Controller 获取登录主体 |
| `@WithMockUser` | 测试中构造模拟用户 | Security 测试 |
| 项目自定义 `@ApiPermission` | 统一声明业务权限码 | 当前项目的页面/接口操作权限 |

### 当前项目的阅读路径

在 `datacenter-cloud` 中，可以用以下类名建立证据链（具体包路径随分支变化时用 IDE 搜索类名）：

```text
DashboardPageController
  -> 方法上的 @ApiPermission
  -> ApiPermissionAspect
  -> Permission.Dashboard.VIEW / ADD / UPDATE / DELETE
  -> 当前登录用户与权限服务
```

`@ApiPermission` 是声明，`ApiPermissionAspect` 才是读取声明并执行检查的组件。排查权限问题时，要同时看注解是否贴在正确方法、切面是否启用、调用是否经过代理、权限码是否匹配、当前用户是否已认证。

```java
// 下面是项目自定义权限注解的使用形态示意，具体属性以当前源码为准。
@ApiPermission(Permission.Dashboard.VIEW)
@GetMapping("/{id}")
public DashboardView view(@PathVariable("id") String id) {
    // 通过功能权限后，还需要校验这个 id 是否属于当前用户可访问范围。
    return dashboardService.view(id);
}
```

前端隐藏按钮只是体验优化，不能作为安全边界。服务端至少要区分：

1. **认证**：你是谁，Token/Session 是否有效。
2. **功能权限**：你能否执行“查看/新增/修改/删除”这类动作。
3. **资源权限**：你能否访问具体的 `reportId`、`fileId`、`orderId`。
4. **数据范围**：你能看到哪些组织、部门或租户的数据。

项目中如果存在“权限服务不可用时跳过检查”的兼容分支，必须明确它只适合受控的开发/迁移环境；生产环境默认放行会把注解变成无效装饰。

## MyBatis / MyBatis-Plus：把 Java 方法映射到 SQL 和表

| 注解 | 解决的问题 | 典型使用 |
|---|---|---|
| `@Mapper` | 把接口注册为 MyBatis Mapper | 单个 Mapper 接口 |
| `@MapperScan` | 批量扫描 Mapper | 启动类或配置类 |
| `@Select` / `@Insert` / `@Update` / `@Delete` | 在注解中声明简单 SQL | 小型、稳定、短 SQL |
| `@Param` | 为 SQL 参数命名 | 多参数 Mapper 方法 |
| `@TableName` | 映射实体对应表 | MyBatis-Plus 实体 |
| `@TableId` | 声明主键策略 | 自增、ASSIGN_ID 等 |
| `@TableField` | 映射列名/忽略字段 | 驼峰不一致、计算字段 |
| `@TableLogic` | 声明逻辑删除字段 | 软删除约定 |
| `@Version` | 声明乐观锁版本字段 | 防止并发覆盖更新 |

```java
@org.apache.ibatis.annotations.Mapper
public interface OrderMapper {

    // 多参数 SQL 用 @Param 明确名称，避免依赖 arg0/param1。
    @org.apache.ibatis.annotations.Select(
            "select id, status from orders where customer_id = #{customerId}")
    java.util.List<OrderRow> findByCustomer(
            @org.apache.ibatis.annotations.Param("customerId") Long customerId);
}
```

MyBatis 注解适合短 SQL；复杂动态 SQL、复用片段和大量条件通常更适合 XML Mapper 或项目约定的 SQL 文件。`@Mapper` 和 Spring `@Repository` 不是同一个注册机制：前者让 MyBatis 生成 Mapper 代理，后者表达 Spring 持久层角色和异常转换语义。

MyBatis-Plus 示例：

```java
@com.baomidou.mybatisplus.annotation.TableName("orders")
public class OrderEntity {

    // 主键策略要和数据库及项目分布式 ID 方案一致。
    @com.baomidou.mybatisplus.annotation.TableId
    private Long id;

    // 逻辑删除只是一种查询/更新约定，原始 SQL 仍需单独审查。
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted;

    // 乐观锁更新时会带上旧 version，影响行数为 0 表示可能发生冲突。
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
```

不要把 `@TableLogic` 当成所有 SQL 都自动安全；自定义 SQL、报表查询和跨表语句要检查是否带删除条件。使用 `@Version` 后要判断更新影响行数，并向上层返回可重试或提示冲突的结果。

## Lombok：编译期生成代码的注解

Lombok 不是运行时反射框架，它通过编译器插件或注解处理器生成源码中没有显式写出的 getter、setter、构造器、Builder 和日志字段。阅读代码时要在 IDE 中展开生成方法，或理解它的生成规则。

| 注解 | 生成内容 | 使用建议 |
|---|---|---|
| `@Getter` / `@Setter` | getter/setter | 对 DTO 或明确字段使用 |
| `@Data` | getter、setter、equals、hashCode、toString、RequiredArgsConstructor | 简单 DTO 可用；Entity 要审查生成语义 |
| `@Value` | 不可变类常用方法 | 值对象；要确认框架反序列化支持 |
| `@Builder` | 链式 Builder | 多可选参数对象 |
| `@NoArgsConstructor` | 无参构造器 | ORM/反序列化需要时 |
| `@AllArgsConstructor` | 全字段构造器 | 谨慎使用，字段顺序容易传错 |
| `@RequiredArgsConstructor` | final/@NonNull 字段构造器 | 构造器注入常用 |
| `@Slf4j` | `private static final log` | 统一日志字段 |
| `@EqualsAndHashCode` | equals/hashCode | 明确包含/排除字段 |
| `@ToString` | toString | 排除密码、Token、个人敏感数据 |

```java
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@org.springframework.stereotype.Service
public class ReportService {

    // final 字段会进入 Lombok 生成的构造器，Spring 可以进行构造器注入。
    private final ReportRepository repository;

    public void refresh(String reportId) {
        // @Slf4j 生成的 log 字段不需要手工声明。
        log.info("刷新报表 reportId={}", reportId);
        repository.refresh(reportId);
    }
}
```

`@Data` 可能把所有字段纳入 `equals/hashCode/toString`，这对拥有数据库代理、可变关系和敏感字段的 Entity 可能不合适。`@Builder` 可能和 ORM 需要的无参构造器冲突；需要同时提供 `@NoArgsConstructor` 时，要检查字段默认值和对象有效性。生成日志文本前也要排除密码和 Token。

## JUnit 与 Spring 测试注解

| 注解 | 作用 | 适合验证 |
|---|---|---|
| `@Test` | 标记测试方法 | 单元/集成测试入口 |
| `@BeforeEach` / `@AfterEach` | 每个测试前后执行 | 清理临时状态 |
| `@BeforeAll` / `@AfterAll` | 整个测试类前后执行 | 共享资源；需注意静态约束 |
| `@DisplayName` | 提升测试报告可读性 | 业务场景命名 |
| `@SpringBootTest` | 启动较完整的 Spring 容器 | 集成链路、配置和 Bean 组合 |
| `@WebMvcTest` | MVC 切片测试 | Controller、参数绑定、异常响应 |
| `@MockBean` | 用 Mock 替换容器中的 Bean | 隔离 Controller/Service 依赖 |
| `@ActiveProfiles` | 指定测试配置环境 | test 数据源、开关和外部服务替身 |
| `@Sql` | 执行测试 SQL | 固定数据库准备/清理 |
| `@Transactional` | 测试事务边界 | 需要回滚的数据库测试 |

```java
@org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(OrderController.class)
class OrderControllerTest {

    // 用 MockBean 替换容器中的真实 Service，测试只关注 MVC 层。
    @org.springframework.boot.test.mock.mockito.MockBean
    private OrderService orderService;

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("订单不存在时返回 404")
    void returnsNotFound() throws Exception {
        // 这里应配置 mock 行为，再通过 MockMvc 发起请求并断言状态和响应体。
    }
}
```

`@WebMvcTest` 通常不会加载完整数据库、消息队列和所有 Service；需要验证整个应用启动和真实配置组合时才使用 `@SpringBootTest`。测试中的 `@Transactional` 是否回滚还取决于测试运行器、事务管理器和数据库配置，不能只看注解名称。

## 自定义注解的完整可运行示例

下面的示例不依赖 Spring，专门展示“声明—读取—处理”的闭环。把它保存为 `AnnotationReflectionDemo.java` 可用 Java 11 编译运行。

<a href="/examples/java/AnnotationReflectionDemo.java" download>下载 AnnotationReflectionDemo.java</a>

::: details 展开完整中文注释源码

<<< ../../public/examples/java/AnnotationReflectionDemo.java{java}

:::

运行后应看到类似输出：

```text
AUDIT action=create-order, operator=system
BUSINESS create order=order-1001
NO_AUDIT method=health
BUSINESS health=UP
PASS annotation reflection demo
```

这段代码说明了三个边界：

1. `@Audit` 只保存动作名和操作者配置，不负责打印日志。
2. `invokeWithAudit` 通过反射读取它，并决定是否执行审计逻辑。
3. 真正的业务方法仍然由 `method.invoke` 调用；Spring AOP 会用代理在类似位置做更复杂的拦截。

它与 Spring 的区别是：示例由我们手工调用处理器，Spring 则在容器启动、代理创建和请求调用过程中自动完成扫描与拦截。不要把这个纯 JDK 示例误认为已经具备事务、权限或线程安全能力。

## 注解排错：从“看见注解”到“证明生效”

| 现象 | 第一证据 | 继续检查 | 常见原因 |
|---|---|---|---|
| 注解完全没效果 | 运行时对象类型和调用栈 | Bean 扫描、代理、切点 | 对象是 `new` 出来的，或包不在扫描范围 |
| 反射读不到自定义注解 | `getAnnotation` 返回 null | `@Retention`、目标位置、方法重载 | 忘记 `RUNTIME`，或读取了父类/接口错误方法 |
| `@Transactional` 不回滚 | 数据库状态和异常类型 | 自调用、代理、事务管理器 | 异常被吞、异常类型不触发默认回滚 |
| `@Async` 仍同步执行 | 线程名和执行器日志 | `@EnableAsync`、代理、线程池 | 同类调用、未启用异步、执行器配置错误 |
| Controller 返回 400 | Network 的 Content-Type/body | 参数注解、DTO、校验异常 | Query/body 放错位置，字段类型或格式不匹配 |
| 权限注解被绕过 | Security 与切面日志 | 认证、功能码、资源归属 | 只做前端隐藏、切面未启用、兼容分支放行 |
| Mapper 没注册 | 启动异常和 Bean 列表 | `@Mapper`、`@MapperScan`、包路径 | 扫描范围错误或依赖未引入 |

建议用下面的顺序验证：

```text
1. 注解是否写在正确的元素上？
2. 注解保留策略是否允许当前读取方式？
3. 读取者/处理器是否真的启用？
4. 目标对象是否由对应框架管理？
5. 调用是否经过代理或拦截器？
6. 业务条件、异常规则、配置和外部依赖是否满足？
```

## 常见误区清单

- 认为注解本身就是业务逻辑；实际行为由编译器、处理器、容器或反射代码产生。
- 看到 `@Component` 就认为 Bean 一定存在；还要确认扫描边界、Profile、条件装配和依赖。
- 看到 `@Transactional` 就认为所有异常都会回滚；异常类型、代理和数据库都影响结果。
- 以为 `@Inherited` 能让方法注解自动传给子类；它只对类级别注解有效。
- 用 `@SuppressWarnings` 盖住未经审查的类型转换。
- 把 `@Valid` 当成权限校验；格式正确和有权操作是两个问题。
- 把 `@Async` 当成可靠消息队列；任务持久化、重试和投递语义仍需额外设计。
- 把 `@TableLogic` 当成所有自定义 SQL 都自动带删除条件。
- 给 Entity 直接加 `@Data`，却没有审查 equals/hashCode/toString 的字段语义。
- 只在前端隐藏按钮，未在后端校验功能权限、资源归属和数据范围。

## 学习与练习路线

### 基础练习

1. 在 Java 示例中把 `RetentionPolicy.RUNTIME` 改成 `CLASS`，重新运行并解释为什么读不到注解。
2. 给 `AnnotationReflectionDemo` 增加一个 `@Audit` 属性 `category`，让处理器打印分类。
3. 找一个项目 Controller，列出类级和方法级注解，并标记每个参数来自 Path、Query、Header、Body 还是 multipart。
4. 找一个 `@Service`，画出构造器依赖图，说明每个依赖由哪个注解或扫描规则注册。

### 进阶练习

1. 为一个订单接口增加 `@Valid`、全局异常处理和 409 冲突响应，分别验证 400 与 409。
2. 找一处 `@Transactional`，检查调用方是否经过代理，模拟同类 `this` 调用并观察差异。
3. 为一个有两个实现的接口分别使用 `@Primary` 和 `@Qualifier`，记录它们解决的问题边界。
4. 为 MyBatis-Plus 实体增加 `@Version`，模拟两个请求更新同一行并检查影响行数。

### 排错练习

1. 制造一个扫描包错误，使用启动日志和 Bean 列表证明 `@Service` 没有注册。
2. 制造一个错误 `Content-Type`，从浏览器 Network、MVC 异常和 Controller 参数三处定位 400。
3. 制造一个自调用的 `@Async` 或 `@Cacheable`，用线程名/日志证明代理没有被经过。
4. 对当前项目的 `@ApiPermission`，分别验证“未登录”“无功能权限”“无资源归属”“有权限成功”四条路径。

下一步：

- [类、对象与类型](./core.md)：理解注解所附着的类、字段、方法和对象边界。
- [IoC 与依赖注入](../spring/ioc.md)：理解 Spring 如何扫描注解、创建 Bean 和注入依赖。
- [AOP 与代理](../spring/aop.md)：理解事务、日志和权限注解为何依赖代理调用。
- [Spring MVC 与 Controller](../spring/mvc.md)：把请求参数注解和真实 HTTP 请求对应起来。
