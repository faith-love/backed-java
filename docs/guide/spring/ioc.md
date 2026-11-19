# IoC 与依赖注入：谁创建对象，谁负责替换

IoC（Inversion of Control，控制反转）把“对象由谁创建、依赖怎样连接、生命周期何时结束”的控制权从业务类交给容器。依赖注入（Dependency Injection，DI）是实现 IoC 的常见方式：类通过构造器、方法或字段声明需要什么，容器在创建它时提供对象。

它解决的核心问题不是少写几行 `new`，而是让**业务代码依赖稳定契约，创建和替换集中在组合根**。这样测试可以传入替身实现，正式环境可以替换数据库/HTTP 客户端，生命周期和关闭责任也更清晰。

## 知识点地图：从“谁创建”追到“谁销毁”

| 知识点 | 要回答的问题 | 观察位置 |
|---|---|---|
| IoC 与 DI | 控制权转移了什么？依赖如何进入对象？ | 构造器、`@Bean`、容器配置 |
| BeanDefinition | 容器准备创建哪个类型、名称、作用域和条件？ | 组件扫描、配置类、自动配置 |
| Bean 实例化 | 构造器和依赖按什么顺序执行？ | 启动日志、构造器、依赖图 |
| Bean 生命周期 | 初始化、代理、销毁分别发生什么时候？ | `@PostConstruct`、BeanPostProcessor、`@PreDestroy` |
| 作用域 | 一个对象被多少请求共享？ | `singleton`、`prototype`、request/session |
| 注入选择 | 同一接口有多个实现时谁被传入？ | `@Qualifier`、`@Primary`、名称 |
| 运行时边界 | 这个对象是否被代理，资源是否由容器关闭？ | `ApplicationContext`、代理类型、关闭回调 |

先记住：**容器管理的是对象的创建和生命周期，不会自动替你设计业务状态的一致性。** 一个 singleton Bean 可以安全地持有线程安全的 `HttpClient`，但不能因此把当前用户写入字段；一个 `@Bean` 可以注册连接池，但连接池容量和超时仍需明确配置。

## 先看手工创建为什么会变复杂

```java
// 反例：Controller 直接绑定具体实现，依赖继续向下扩散。
public final class UserController {
    private final UserService service = new UserServiceImpl(
            new MySqlUserRepository(new DataSourceFromEnvironment()));
}
```

这种写法把四个问题塞进了 Controller：

- 具体实现固定，切换内存仓库、远程仓库或测试替身要改业务类。
- 数据库连接池、HTTP 客户端等资源的创建和关闭责任分散。
- 复杂依赖会形成很长的 `new` 链，参数顺序和环境配置容易错。
- 单元测试不能直接传入假 Service，只能启动真实基础设施或使用反射替换字段。

## 用一个可运行的纯 Java 例子理解 DI

下面的程序不依赖 Spring，故意把“容器做的事情”写出来：`GreetingController` 只依赖接口，`main` 作为组合根选择实现。Spring 启动时会用 Bean 定义和扫描结果完成类似的装配。

<a href="/examples/spring/IocDependencyDemo.java" download>下载 IocDependencyDemo.java</a>

::: details 展开完整中文注释源码
<<< ../../public/examples/spring/IocDependencyDemo.java{java}
:::

```java
// 生产/测试都依赖这个行为契约，而不是依赖某个具体类。
interface GreetingService {
    String greet(String name);
}

// 正式实现可以访问数据库或其他服务；本例只保留纯逻辑。
final class DefaultGreetingService implements GreetingService {
    @Override
    public String greet(String name) {
        return "你好，" + (name == null || name.isBlank() ? "朋友" : name.trim());
    }
}

// Controller 不创建 Service；依赖通过构造器传入，并保存为不可变字段。
final class GreetingController {
    private final GreetingService service;

    GreetingController(GreetingService service) {
        this.service = service;
    }

    String getGreeting(String name) {
        return service.greet(name);
    }
}
```

测试时可以传入 `GreetingService` 的匿名替身，验证 Controller 的边界，而不启动数据库：

```java
GreetingService fake = name -> "测试结果";
GreetingController controller = new GreetingController(fake);
if (!"测试结果".equals(controller.getGreeting("任意名字"))) {
    throw new AssertionError("Controller 没有调用注入的服务");
}
```

这个例子解决的是依赖方向；它没有提供 Spring 的扫描、事务、HTTP 和配置能力。生产代码仍需选择合适的 Bean 生命周期和错误边界。

## Spring 如何找到并创建 Bean

### 注册来源

| 方式 | 解决的问题 | 常用场景 |
|---|---|---|
| `@Component` | 注册通用组件 | 工具、策略、适配器 |
| `@Service` | 表达业务服务 Bean | Service/Use Case |
| `@Repository` | 表达持久层组件并参与异常转换 | DAO、Repository |
| `@Controller` / `@RestController` | 注册 Web 处理器 | MVC API |
| `@Configuration` + `@Bean` | 注册第三方类或需要显式构造的对象 | DataSource、HttpClient、SDK |
| `@Import` / 自动配置 | 引入一组受控定义 | starter、模块化配置 |

组件扫描只会发现扫描范围内、满足条件的类。`@Mapper`、Feign Client、配置中心 Bean 还可能由各自框架的注册器创建；不能把所有对象都理解为普通 `@Component`。

### 从启动到请求的大致生命周期

```text
SpringApplication.run()
  -> 创建 ApplicationContext
  -> 读取配置、注册 BeanDefinition
  -> 执行组件扫描与自动配置条件
  -> 创建单例 Bean、解析构造器依赖
  -> 创建必要的代理（事务、AOP、安全等）
  -> 发布容器就绪事件
  -> 请求到达时复用已创建的 Bean
  -> 容器关闭时调用销毁回调/关闭资源
```

默认 `singleton` 是**每个 ApplicationContext 一个实例**，不是整个集群一个实例，也不表示字段可以安全保存每个请求的状态。Controller/Service 通常应该无状态；当前用户、分页参数和临时计算结果放在方法参数或局部变量中。

### 重难点一：BeanDefinition 先于 Bean 实例

组件扫描并不是立刻 `new` 每一个类，而是先把候选类转换成 BeanDefinition。定义里包含 Bean 名称、实现类型、作用域、是否懒加载、依赖关系和条件信息；容器随后根据这些定义决定何时创建实例。于是“类文件存在”与“运行时有 Bean”之间还有几道门：

1. 类是否在扫描范围或被 `@Import` 引入。
2. 依赖模块和条件类是否存在于当前 classpath。
3. `@Profile`、`@ConditionalOnProperty` 等条件是否满足。
4. 同名 Bean 是否覆盖、冲突或被配置排除。
5. 创建构造器参数时，它们的依赖是否已经可用。

因此排查注入失败不能只看类上有没有 `@Service`，还要看启动类的包边界、当前 Profile 和自动配置条件。

### 重难点二：生命周期和代理不是一回事

一个简化的顺序是：实例化 → 注入依赖 → `BeanPostProcessor` 前置处理 → `@PostConstruct`/初始化回调 → 后置处理 → 可能包装成代理 → 放入单例缓存。容器关闭时再执行销毁回调。不同扩展点、懒加载和代理创建会改变细节，不能把这个简化顺序当成所有场景的源码级保证。

```java
// 集成示意：对象初始化时只做本地校验；不要在构造器里偷偷执行不可控远程副作用。
@Component
public final class ConfiguredClient {
    private final GatewayProperties properties;

    public ConfiguredClient(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        // 让错误在启动阶段暴露，而不是第一次请求才发现空地址。
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("网关地址不能为空");
        }
    }

    @PreDestroy
    public void close() {
        // 只有这个 Bean 真正拥有的资源才由它关闭；共享客户端要遵循所有者约定。
    }
}
```

这里的注解片段需要 Spring 依赖，不能单独用 `javac` 编译。实际资源是否需要关闭，要看客户端库是否实现 `AutoCloseable`、是否由连接池统一管理；不要看到 `@PreDestroy` 就无条件关闭外部共享对象。

### 重难点三：构造器注入失败往往是依赖图问题

把 Bean 看作有向图：`Controller -> Service -> Repository -> DataSource`。启动失败可能来自缺少节点、多个候选节点、循环依赖或节点初始化时访问了不可用外部资源。构造器注入会尽早暴露这些问题，这是优点；不要为了让应用启动而把所有依赖改成可空字段。

```text
DashboardPageController
  -> IDashboardPageService
  -> DashboardPageServiceImpl
  -> DashboardPageDao
  -> MyBatis SqlSessionFactory / DataSource
```

在当前项目中，`@SpringBootApplication` 的组件扫描与 `@MapperScan` 的 Mapper 注册是两条不同的发现路径。Controller 找得到但 Mapper 找不到，不能只扩大普通组件扫描；反过来也一样。

## 构造器注入、`@Qualifier` 与 `@Primary`

下面的注解代码是 Spring Boot 集成片段，不是可单文件编译程序；正式项目需要匹配的 Spring 依赖、import、Bean 定义和配置。可直接编译运行的 IoC 边界示例见上面的 `IocDependencyDemo.java`。

### 推荐：构造器注入

```java
@Service
public class ReportService {
    private final ReportRepository repository;
    private final Clock clock;

    // Spring 会把匹配的 Bean 传入；必需依赖一眼可见。
    public ReportService(ReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }
}
```

单个构造器在现代 Spring 中通常无需额外写 `@Autowired`。构造器注入的优点是：对象创建后依赖完整、字段可以 `final`、测试可以直接 `new ReportService(fakeRepository, fixedClock)`。

### 多个实现必须明确选择

```java
public interface PaymentClient {
    PaymentResult pay(PaymentRequest request);
}

@Component("sandboxPaymentClient")
class SandboxPaymentClient implements PaymentClient {
    // 本地模拟实现；真实实现应放在独立适配器中。
    public PaymentResult pay(PaymentRequest request) {
        return PaymentResult.success();
    }
}

@Service
class PaymentService {
    private final PaymentClient client;

    // 按名称选择，避免两个 PaymentClient 都能匹配时启动失败。
    PaymentService(@Qualifier("sandboxPaymentClient") PaymentClient client) {
        this.client = client;
    }
}
```

`@Primary` 适合有一个默认实现、少数地方才切换的情况；`@Qualifier` 更适合按支付渠道、租户或场景明确选择。不要让实现顺序、类名排序或扫描偶然顺序决定业务行为。

### `@Primary`、`@Qualifier` 与 `ObjectProvider` 怎么选

| 需求 | 选择 | 原因与边界 |
|---|---|---|
| 全局只有一个默认实现 | `@Primary` | 调用方简单；新增实现时仍要审查默认值是否改变 |
| 一个类明确选择某个渠道 | `@Qualifier` | 业务意图写在注入点，不依赖扫描顺序 |
| 运行时按名称/条件选择 | `ObjectProvider<T>` 或策略 Map | 需要处理不存在、多个候选和生命周期；不要把 Bean 名称当用户输入直接查找 |
| 需要全部实现 | `List<T>`/`Map<String,T>` | 适合策略注册表；排序和重复实现要有明确规则 |

`ObjectProvider` 解决的是“何时取/是否存在”的问题，不是线程安全或事务问题。把 Provider 存在 singleton 中时，仍需确认取出的对象是否线程安全、是否被代理以及何时销毁。

## 作用域与状态边界

| 作用域 | 生命周期 | 适合什么 | 风险 |
|---|---|---|---|
| `singleton`（默认） | 容器内共享 | 无状态 Service、线程安全客户端、配置 | 可变字段被并发请求共享 |
| `prototype` | 每次获取新对象 | 有明确短生命周期的有状态对象 | 注入后由容器不一定继续管理销毁 |
| `request` | 一次 HTTP 请求 | 请求级上下文（谨慎使用） | 异步线程、非 HTTP 任务不能假定存在 |
| `session` | 用户会话 | 少量会话状态 | 集群粘性、内存和隐私边界复杂 |

不要把 `prototype` 当作解决并发的万能开关；如果它被注入到 singleton，注入动作仍可能只发生一次，需要 `ObjectProvider` 等按需获取方式。异步任务也不要直接持有已结束请求的对象或 Servlet 状态。

### 重难点四：作用域与并发状态

下面的写法会让两个并发请求互相覆盖 `lastUser`，即使字段声明为 `private` 也没有帮助：

```java
// 反例：默认 singleton Bean 被所有请求共享。
@Service
public class UnsafeReportService {
    private String lastUser;

    public ReportView load(String userId, String reportId) {
        lastUser = userId; // 请求 A 写入后，请求 B 可能立即覆盖。
        return query(reportId, lastUser);
    }
}
```

优先改成方法局部变量或不可变参数：

```java
@Service
public class SafeReportService {
    public ReportView load(String userId, String reportId) {
        // 每次调用有自己的栈帧；权限条件也应进入查询或领域校验。
        return queryForUser(reportId, userId);
    }
}
```

加 `synchronized` 只能让单个 JVM 的调用排队，不能让多实例共享状态，也不能替代数据库的资源归属条件。并发边界详见[锁与线程安全](../java/locks.md)和[并发总览](../java/concurrency.md)。

## `@Bean`：第三方对象和资源配置

```java
@Configuration
public class HttpClientConfig {
    @Bean
    public HttpClient httpClient() {
        // 第三方类没有你的 @Component，使用 @Bean 集中配置并复用。
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
```

客户端是否可复用、是否需要显式关闭、连接池怎样设置，要以具体库版本为准。`@Bean` 方法返回对象不等于已经配置了超时、TLS、重试和容量；这些仍是工程决策。

## 常见启动失败与排查

| 现象 | 常见原因 | 排查顺序 |
|---|---|---|
| `NoSuchBeanDefinitionException` | 没扫描到、条件不满足、模块依赖缺失 | 看启动类包、组件注解、profile、自动配置条件 |
| `NoUniqueBeanDefinitionException` | 同接口多个实现 | 用 `@Qualifier`/`@Primary`，检查是否意外重复注册 |
| 循环依赖错误 | A 构造器依赖 B，B 又依赖 A | 拆分职责、提取接口或改事件/编排边界；不要先打开循环引用开关 |
| Bean 创建时连接外部服务失败 | 在构造器里做了网络/数据库副作用 | 延迟到明确业务方法，或用启动检查并提供可诊断错误 |
| 注入的单例互相污染 | 在 Bean 字段保存请求数据 | 改成方法参数/局部变量，或明确请求作用域 |

### 典型错误的证据与修复

| 错误 | 先收集什么证据 | 正确修复方向 |
|---|---|---|
| `@Service` 存在但无法注入 | 启动类扫描根、模块依赖、Profile、条件日志 | 修正扫描/注册边界，不要直接复制 `new` 到 Controller |
| 同接口注入歧义 | 候选 Bean 名称和定义来源 | `@Qualifier`/`@Primary` 或拆分接口职责 |
| 循环依赖 | A→B→A 的完整构造器链 | 拆分职责、提取第三个协调对象或改事件；不要首先放宽循环引用 |
| 测试想替换真实下游 | 依赖是否通过接口/构造器进入 | 注入 Fake/Stub，避免反射改私有字段 |
| 资源连接泄漏 | Bean 创建/关闭日志、客户端生命周期、线程/连接池指标 | 让资源有明确所有者，使用容器销毁回调或客户端管理器 |
| 请求数据串到下一次请求 | 共享 Bean 字段、缓存键和线程上下文 | 方法参数/局部变量；需要跨线程时显式传递并清理上下文 |

在 `DashboardApplication` 中分别观察 `@SpringBootApplication(scanBasePackages=...)` 与 `@MapperScan`：前者负责组件发现，后者负责 MyBatis Mapper 注册。看到 `IDashboardPageService` 时，沿接口实现和注解继续核对，不要只凭字段类型推断实现。

## 什么时候使用 IoC，什么时候不要引入容器

- 多个模块共享数据库、HTTP 客户端、缓存、策略实现时，用容器管理组合和生命周期。
- 需要通过配置切换实现、在测试中替换依赖、统一添加拦截/事务时，IoC 的收益明显。
- 一个纯函数、简单值对象或一次性局部计算不需要注册成 Bean；直接构造更清楚。
- 不要为了“看起来 Spring”把所有 DTO、枚举和工具方法都注册成 singleton。

## 分级练习

### 基础

1. 写出一个 Controller 的必需依赖，并把字段注入改成构造器注入。
2. 运行 `IocDependencyDemo.java`，把默认 Service 替换成假 Service，并解释为什么不需要数据库。

### 进阶

3. 找到一个接口的全部实现，说明应使用 `@Primary`、`@Qualifier` 还是 `ObjectProvider`，以及为什么。
4. 画出 `Controller -> Service -> Mapper -> DataSource` 的 Bean 依赖图，标出哪些对象应该复用、哪些数据必须是调用局部状态。

### 排错

5. 从启动日志找一个 Bean 创建失败，按“扫描、条件、候选、依赖、外部资源”顺序记录证据。
6. 找一个 Service 字段，模拟两个并发请求，说明它是否会互相污染；再把修复方案与数据库/分布式场景的边界写出来。

下一步：[AOP 与代理](./aop.md)。
