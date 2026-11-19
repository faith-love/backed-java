# AOP 与代理：把重复的外围流程放到正确边界

AOP（Aspect Oriented Programming，面向切面编程）解决的是：**很多业务方法都需要相同的外围动作，但这些动作不应该散落在每个方法里**。例如事务、权限、审计日志、耗时指标、链路追踪和限流。

对前端来说，它像请求拦截器、中间件和装饰器的组合：页面请求先经过统一拦截，再进入业务处理，结束时还可以记录结果或异常。区别是 Spring AOP 通常把增强放在 Bean 代理上，调用是否经过代理决定增强是否生效。

::: tip 本章先记住一句话
看到注解不等于看到执行逻辑。要从“调用方拿到的对象是谁、调用是否经过代理、切点是否匹配、异常如何传播”四个问题验证 AOP。
:::

## 知识点地图：先知道每个概念解决什么问题

| 知识点 | 解决的问题 | 读代码时要找什么 |
|---|---|---|
| 横切关注点 | 日志、权限、事务等逻辑会重复出现在许多方法 | `@Aspect`、拦截器、过滤器、框架插件 |
| 目标对象 | 真正执行业务的对象需要保持业务代码清晰 | `@Service`、`@Repository`、普通 Bean |
| 代理对象 | 在调用目标前后插入统一行为 | `AopUtils.isAopProxy`、JDK Proxy、CGLIB 类名 |
| 切点表达式 | 选择哪些调用需要增强 | `execution`、`within`、`@annotation`、`bean` |
| 通知类型 | 定义增强发生在调用前、后、异常还是包围整个调用 | `@Before`、`@AfterReturning`、`@AfterThrowing`、`@Around` |
| 代理边界 | 解释为什么外部调用生效、内部 `this` 调用失效 | 调用方引用、同类方法调用、是否由容器创建 |
| 顺序与异常 | 多个切面同时执行时保持权限、事务、日志的正确顺序 | `@Order`、`proceed()`、异常是否重新抛出 |
| 上下文传播 | 请求用户、Trace ID、事务和线程池任务的边界不同 | `SecurityContext`、MDC、事务同步、`@Async` |

### 先分清四种常被叫作“拦截”的机制

| 机制 | 主要阶段 | 适合处理 | 不应该假设 |
|---|---|---|---|
| Servlet Filter | 进入 DispatcherServlet 前后 | 请求 ID、CORS、原始请求体、统一入口限制 | 能自动知道 Service 的业务结果 |
| HandlerInterceptor | MVC 找到 Handler 前后 | 路由级登录检查、请求耗时 | 能覆盖非 MVC 的消息消费或定时任务 |
| Spring AOP | Spring Bean 方法调用边界 | Service 事务、方法权限、审计和指标 | 能拦截 `new` 出来的对象或所有内部调用 |
| MyBatis/数据源插件 | SQL 或连接执行边界 | SQL 观测、分页、数据源规则 | 能替代业务层资源授权 |

把功能放错扩展点会导致“代码执行了但看不到增强”。例如文件上传的大小限制可能要在网关、Servlet multipart 和 Service 三层共同处理；只在 AOP 中校验 Service 参数，无法阻止请求体已经被解析和缓存。

## 重难点一：注解、切点和代理是三件事

### 为什么难

注解只是元数据，切点是筛选规则，代理才是实际调用入口。下面的注解即使写得正确，也可能因为目标对象不是 Spring Bean、包表达式不匹配或调用绕过代理而不执行。

```java
// 这个注解本身不会自动打印日志；还需要一个 Spring 管理的切面匹配它。
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();
}

// 切面只会匹配带 @Audited 的 Spring Bean 方法。
@Aspect
@Component
public final class AuditAspect {
    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint point, Audited audited) throws Throwable {
        // 生产日志只写 action、资源 ID、Trace ID 等脱敏信息，不写密码或完整 Token。
        long start = System.nanoTime();
        try {
            // 只调用一次 proceed；不调用会阻止业务，调用两次可能重复写库。
            return point.proceed();
        } finally {
            long elapsed = System.nanoTime() - start;
            System.out.println("action=" + audited.action() + " elapsed=" + elapsed);
        }
    }
}
```

**使用时机：** 规则适用于一组 Bean 方法，并且需要统一观测或保护时使用 AOP；只有一个方法、需要强类型业务参数或会改变业务状态机的规则，直接写在 Service 更容易读。**排错方法：** 先在调用方打印实际类名，再检查切点包名、注解保留策略、Bean 是否在扫描范围，最后确认异常是否被代理吞掉。

## 重难点二：JDK 代理与 CGLIB 影响“能不能调用”

JDK 动态代理实现接口，调用方应依赖接口；CGLIB 通过子类覆写方法，目标类不能是 `final`，需要增强的方法也不能是 `final/private/static`。代理对象的 `getClass()` 可能与目标类不同，所以把代理强转为实现类、依赖实现类私有方法，都容易在配置切换后失败。

```java
// 推荐让业务代码依赖接口，代理策略改变时调用方仍然稳定。
public interface InventoryService {
    int available(String sku);
}

@Service
public class InventoryServiceImpl implements InventoryService {
    @Override
    public int available(String sku) {
        return 10;
    }
}

@RestController
public class InventoryController {
    private final InventoryService service; // 接口类型可以接收 JDK 或 CGLIB 代理

    public InventoryController(InventoryService service) {
        this.service = service;
    }
}
```

**解决的问题：** 接口依赖降低代理实现变化对业务代码的影响。**什么时候使用 CGLIB：** 只有确实没有接口、且需要代理公开类方法时再使用；不能把 CGLIB 当作修复错误强转的万能开关。**排错证据：** 查看 `proxyTargetClass`、`@EnableAspectJAutoProxy`、Spring Boot 自动配置和异常中的 `$Proxy`/`$$SpringCGLIB$$` 类名。

## 重难点三：自调用为什么会绕过事务、权限和异步

### 调用路径决定增强是否发生

```text
Controller -> 代理对象 -> 目标对象.outer()
                           -> this.inner()      // 直接调用，通常不回代理

Controller -> 代理对象 -> 另一个 Spring Bean.inner() // 再次经过代理
```

```java
@Service
public class ImportService {
    public void importAll(List<String> rows) {
        // this.saveOne(row) 不会重新经过代理，因此 saveOne 上的 @Transactional/@Async
        // 可能不生效；这里只展示调用方向，不建议在循环里启动无限异步任务。
        for (String row : rows) {
            saveOne(row);
        }
    }

    @Transactional
    public void saveOne(String row) {
        // 真实写库逻辑。
    }
}
```

**优先修复：** 把 `saveOne` 放到独立的 `ImportRowService`，由外部 Bean 调用；或者把事务边界提升到 `importAll`，让批量操作拥有清晰的提交策略。**不建议的修复：** 到处注入自身代理或打开循环依赖，这会增加生命周期和测试复杂度。**排错方法：** 给外部调用和内部调用分别加 Trace 日志，观察事务是否创建；不要只看注解是否存在。

## 重难点四：通知顺序和异常传播会改变业务结果

多个切面叠加时，可以把执行顺序画成洋葱模型：外层切面先进入、内层切面再进入，目标方法返回后反向退出。权限通常应在事务和业务之前拒绝；事务要包住所有需要一起提交的数据库操作；日志应在最外层记录最终异常，但不能把异常吞成成功。

```java
@Aspect
@Order(10) // 数字越小通常越外层；以当前 Spring 版本的排序规则为准并用日志验证。
class PermissionAspect {
    @Around("execution(* com.example..service..*(..))")
    Object check(ProceedingJoinPoint point) throws Throwable {
        if (!currentUserCanCall(point)) {
            throw new AccessDeniedException("无权执行");
        }
        return point.proceed();
    }

    private boolean currentUserCanCall(ProceedingJoinPoint point) {
        // 这里只是教学占位；真实权限必须读取已验证身份并核对资源归属。
        return true;
    }
}
```

**易错点：** `@AfterReturning` 不会处理异常返回；`@AfterThrowing` 只能观察异常，除非重新抛出，否则上层可能看不到失败；`@Around` 中 catch 后返回默认值会把数据库失败伪装成成功。涉及支付、库存、文件发布时，必须保留失败状态和可重试事实。

## 重难点五：事务、异步、线程上下文不是自动继承

事务绑定在当前线程和事务管理器；`@Async` 任务切换线程后不会自动加入原事务。MDC、SecurityContext 等上下文是否传播取决于配置的 TaskDecorator，数据库事务不能靠复制 ThreadLocal 伪造。

```java
@Service
public class PaymentApplicationService {
    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final PaidMessagePublisher publisher;

    @Transactional
    public void confirm(String orderNo) {
        payments.markPaidIfPending(orderNo); // 订单事实与 outbox 一起提交
        outbox.insertOnce(orderNo, "ORDER_PAID");
        // 不在这里直接依赖普通内存异步任务作为可靠消息。
    }

    // 由 outbox worker 在独立事务中调用；失败可按 eventId 重试。
    public void publishAfterCommit(String eventId) {
        publisher.sendIdempotently(eventId);
    }
}
```

**使用时机：** 只把已提交事实的慢工作放到线程池或消息系统；请求线程要等待的数据库状态迁移仍同步完成。**排错方法：** 日志中同时记录线程名、Trace ID、事务状态和 eventId，区分“任务提交成功”“事务提交成功”“消息发送成功”三个结果。

## 1. 一次代理调用到底发生什么

~~~text
Spring 创建目标 Bean
  -> BeanPostProcessor 根据切点判断是否需要增强
  -> 需要时返回代理对象（调用方通常拿到代理）
  -> Controller/Service 调用代理方法
  -> 前置通知：权限、日志、事务开始
  -> 目标方法：真正业务逻辑
  -> 返回通知或异常通知
  -> 环绕通知提交/回滚、记录耗时
~~~

| 名词 | 含义 | 前端类比 | 需要继续确认 |
|---|---|---|---|
| 目标对象 | 真正执行业务代码的对象 | 被装饰的 service | 是否被代理包裹 |
| 代理对象 | 调用入口，负责先增强再转发 | middleware/decorator wrapper | 调用方是否持有它 |
| 连接点 | 程序运行中可被增强的位置 | 某个 handler 生命周期节点 | Spring AOP 通常关注方法执行 |
| 切点 | 从连接点中筛选目标方法的规则 | 路由匹配器 | 表达式、注解和代理类型是否匹配 |
| 通知 | 在目标调用前、后、异常或环绕执行的代码 | before/after middleware | 是否改变返回值、异常或事务 |
| 切面 | 切点与通知的组合 | 一组中间件规则 | 是否应该集中成通用机制 |

代理只对**进入代理的调用**负责。目标对象内部的 `this.otherMethod()` 是同一个对象上的直接调用，通常不会重新经过代理；这是后面事务和异步失效的根源。

## 2. JDK 动态代理和 CGLIB 代理

### JDK 动态代理：按接口拦截

JDK 代理要求目标实现接口，代理对象也以接口类型暴露。它适合明确的接口边界，但调用方不能把代理强转成目标实现类。

下面的独立例子不依赖 Spring，帮助你观察“代理调用”和“绕过代理”的区别：

<a href="/examples/spring/JdkProxyDemo.java" download>下载完整注释 JdkProxyDemo.java</a>

~~~powershell
javac -encoding UTF-8 --release 11 JdkProxyDemo.java
java '-Dfile.encoding=UTF-8' JdkProxyDemo
~~~

预期输出包含：

~~~text
PASS proxyBefore=2 targetCalls=2 exceptionObserved=true directTargetBypassedProxy=true
~~~

::: details 展开 JDK 动态代理完整源码
<<< ../../public/examples/spring/JdkProxyDemo.java{java}
:::

### CGLIB/子类代理：按继承关系拦截

Spring Boot 2.x 默认常见 CGLIB 子类代理（具体仍由 `proxyTargetClass` 和配置决定）。它能代理没有接口的普通类，但有明确边界：

- `final` 类不能被继承，`final` 方法不能被覆写增强。
- `private`、`static` 方法不是可覆写的实例方法，不能按普通方法代理。
- 构造器执行、对象内部直接调用、代理对象类型转换都要结合实际实现确认。
- JDK 代理与 CGLIB 的选择不是性能口号；优先看接口契约、框架配置和调用类型。

```java
// Spring Boot 2.7 集成片段：需要 spring-aop、spring-context 等依赖。
// proxyTargetClass=true 表示优先使用基于类的代理；不是所有 final 方法都能被增强。
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class AopConfig {
    // 生产项目还应按团队规范配置代理暴露、切面顺序和日志脱敏。
}
```

不要为了让一个类型强转成功就随意打开 CGLIB；先确认调用方是否应该依赖接口。代理策略改变可能影响测试替身、序列化类型和某些框架集成。

## 3. 一个可读的环绕通知

`@Around` 可以决定是否调用目标、何时调用、返回什么以及异常是否继续传播，因此最强也最容易改变业务语义。通用切面应保持短小、可观测、可取消。

```java
// Spring Boot 2.7 集成片段：需要 spring-aop 与 aspectjweaver。
// 这是统一耗时日志示例，不应把完整请求参数、Token 或支付签名直接写入日志。
@Aspect
@Component
public final class TimingAspect {
    @Around("execution(public * com.example.order..*(..))")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        try {
            // 不调用 proceed 就会短路目标方法；这里明确只执行一次。
            return joinPoint.proceed();
        } catch (Throwable failure) {
            long elapsed = System.nanoTime() - start;
            log.warn("业务调用失败 method={} elapsedNanos={} type={}",
                    joinPoint.getSignature().toShortString(), elapsed,
                    failure.getClass().getName());
            throw failure;
        } finally {
            long elapsed = System.nanoTime() - start;
            metrics.timer("order.service.duration").record(elapsed, TimeUnit.NANOSECONDS);
        }
    }
}
```

同一方法同时被多个切面增强时，执行顺序要用 `@Order` 或配置明确。权限通常应该在业务执行前拒绝，事务需要包住数据库操作，耗时指标则要覆盖你真正希望观察的范围。不要让日志切面吞掉异常，也不要让重试切面包住不可重放的扣款/发货操作。

## 4. 常见切点表达式与边界

| 写法 | 选择对象 | 适合场景 | 风险 |
|---|---|---|---|
| `execution(public * com.example..*(..))` | 方法签名与包 | 对一组服务统一计时 | 包范围太宽会放大开销 |
| `within(@Service *)` | 类上的注解 | 统一服务层增强 | 需要确认注解实际保留策略与代理类型 |
| `@annotation(Traceable)` | 方法上的注解 | 只增强显式标记方法 | 忘记标注就不会生效 |
| `args(java.lang.String,..)` | 参数形状 | 对特定输入做观测 | 重载和桥接方法要实测 |
| `bean(orderService)` | Spring Bean 名称 | 精确控制一个 Bean | 重命名 Bean 会改变行为 |

切点匹配成功还不代表代码一定执行：Bean 可能不是 Spring 创建，调用可能绕过代理，方法可能是 `final/private`，或者当前模块使用的是 Servlet Filter、HandlerInterceptor、MyBatis 插件而不是 AOP。

## 5. `@Transactional`：代理边界比注解更重要

事务通常由代理在方法进入时开启，目标方法正常返回时提交，异常离开时按规则回滚。一个事务方法的正确性至少依赖：

1. 方法所在对象由 Spring 容器管理。
2. 调用方通过代理引用调用，而不是自己 `new` 对象。
3. 方法和异常满足事务切点与回滚规则。
4. 使用的事务管理器、数据源和传播行为符合业务设计。
5. 数据库实际支持你需要的隔离级别和锁语义。

```java
@Service
public class OrderService {
    private final OrderRepository orders;
    private final OutboxRepository outbox;

    public OrderService(OrderRepository orders, OutboxRepository outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPaid(String orderNo) throws Exception {
        int changed = orders.markPaidIfPending(orderNo);
        if (changed == 0) {
            // 可能是重复通知，也可能是状态冲突；读取后按领域规则区分。
            throw new IllegalStateException("订单没有从待支付状态转移");
        }
        // 订单状态与 outbox 在同一个本地事务中提交。
        outbox.insertOrderPaid(orderNo);
    }

    public void calledInsideSameBean(String orderNo) {
        // this.markPaid(orderNo) 是自调用，通常绕过事务代理。
        markPaid(orderNo);
    }
}
```

自调用的改法是让外部 Bean 调用一个独立的事务 Bean，或从设计上调整边界；不要默认注入自身代理后到处调用。先把事务边界设计清楚，再选择技术修复。

### 事务与异步不是一回事

```java
@Async("orderExecutor")
public void sendPaidMessage(String orderNo) {
    // 新线程不会自动继承调用线程的数据库事务；这里必须重新开启自己的事务边界。
    // @Async 也不是可靠消息队列，不能在内存任务刚提交时就向支付平台 ACK。
}
```

支付、库存和文件发布这类场景应先在本地事务写可靠事实，再由 outbox/消息系统异步处理；详见[并发](../java/concurrency.md)、[线程池](../java/thread-pools.md)和[支付示例](../../examples/online-payment.md)。

## 6. 权限、日志和指标应该放在哪里

| 需求 | 常见入口 | 为什么 | 不应做什么 |
|---|---|---|---|
| 全局请求 ID、CORS、原始请求体限制 | Filter | 能覆盖 MVC 前后和静态资源边界 | 把业务授权写成字符串判断 |
| Controller 权限、参数审计 | HandlerInterceptor / AOP | 可拿到路由、用户和方法信息 | 只隐藏前端按钮 |
| Service 事务、业务耗时 | AOP | 贴近业务调用边界 | 把关键金额规则藏在切面 |
| 数据库 SQL 观测 | 数据源代理/MyBatis 插件 | 能看 SQL 和连接耗时 | 打印完整密码/Token/大正文 |
| WebFlux 事件流控制 | 响应式过滤器/链 | 配合取消与背压 | 在事件循环里阻塞 JDBC |

同一能力可能有多种实现；请搜索自定义注解、`@Aspect`、Filter、Interceptor、`BeanPostProcessor` 和异常处理器，确认真正生效的链路。

## 7. 排错清单

| 现象 | 优先检查 |
|---|---|
| 注解加了但没有日志/事务 | Bean 是否由 Spring 创建、调用是否经过代理、切点是否匹配、方法是否 final/private |
| 外部调用有效，内部调用无效 | 是否发生 `this.method()` 自调用 |
| `@Transactional` 没回滚 | 异常是否被吞、是否是 checked exception、事务管理器/数据源是否正确 |
| 异步方法同步执行 | 方法是否通过代理调用、是否在同类内部调用、执行器是否配置 |
| 代理类型强转失败 | JDK 接口代理与 CGLIB 类代理的配置/调用类型不一致 |
| 事务提交但消息丢失 | 是否把内存线程池当队列，是否需要 outbox |
| 日志敏感信息泄漏 | 切面是否记录请求参数、响应体、Authorization 或支付签名 |

排查时打开 Spring 的代理/事务日志要控制范围；生产环境先脱敏并限制日志级别，避免诊断本身制造大量 I/O。

## 8. 练习与官方资料

1. 运行 `JdkProxyDemo`，指出哪一次调用经过代理、哪一次直接调用目标对象。
2. 找项目里的 `@Log` 或 `@ApiPermission`，追到定义、切面/拦截器、用户信息来源和异常返回。
3. 画出一个 `@Transactional` 方法的代理边界，并设计一个自调用导致失效的最小例子。
4. 把日志切面改成只记录方法名、耗时、结果类型，说明为什么不记录完整参数。
5. 对支付回调说明：为什么“异步线程提交成功”不能直接替代数据库事务提交后的 ACK。

- [Spring AOP Reference](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/core.html#aop)
- [Spring 事务管理](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/data-access.html#transaction)
- [Java Proxy API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/reflect/Proxy.html)

下一步：[Spring MVC 与 Controller](./mvc.md) 或 [Spring Boot 配置](./boot.md)。
