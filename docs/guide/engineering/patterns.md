# 设计模式

设计模式是对反复出现的设计问题的可复用表达。学习模式是为了看懂对象为什么这样协作，而不是给每个类都贴标签。

先从变化点识别问题，再选择模式：同一种职责有多个实现时考虑策略；创建规则复杂时考虑工厂/建造者；接口不兼容时考虑适配器；需要在不改原实现时叠加行为时考虑装饰器；跨边界拦截调用时理解代理；一个流程固定但步骤可变时考虑模板方法；通知多个订阅者时考虑观察者/事件。

下面的 Java 示例是独立类型的核心代码片段；每个 `public` 顶层类型应放在同名 `.java` 文件中，实际项目还需补齐标准库、框架和业务 DTO 的 import。阅读时重点关注对象之间的依赖方向与失败行为。

| 模式 | 主要变化点 | 主要代价 | 优先考虑的简单做法 |
|---|---|---|---|
| Factory | 实现选择或创建规则 | 注册和错误处理要维护 | 少量稳定类型可直接 `switch` |
| Builder | 对象有许多可选参数 | 增加 Builder 类和构造路径 | 字段少时用普通构造器 |
| Adapter | 外部接口与内部契约不匹配 | 要维护字段和语义映射 | 两端相同且稳定时直接调用 |
| Decorator | 需要按组合方式叠加行为 | 包装顺序可能改变语义 | 单一简单行为可由调用方直接执行 |
| Proxy | 调用边界需要控制或拦截 | 真实逻辑可能藏在代理配置中 | 明确调用可以使用普通服务方法 |
| Strategy | 多个可替换算法/规则 | 增加策略注册和选择机制 | 只有简单少量分支时保留条件判断 |
| Template Method | 流程固定、个别步骤不同 | 继承层级和钩子可能变复杂 | 流程差异很大时用组合编排 |
| Observer/Event | 一个事实需要通知多个处理者 | 处理顺序、失败和可靠性需定义 | 少量强依赖动作可直接由应用服务调用 |

## 创建型：控制对象如何产生

### 工厂（Factory）

**解决的问题：** 调用方不应知道不同实现如何创建，也不该在多个位置重复写 if/else 选择。

~~~java
// 定义按报表类型执行查询的共同能力。
public interface ReportQuery {
    // 所有查询策略接受同一类参数并返回行数据。
    List<Map<String, Object>> execute(QueryRequest request);

    // 显式声明该实现负责的业务类型。
    boolean supports(String type);
}

// 工厂集中选择一个能够处理当前类型的实现。
public class ReportQueryFactory {
    // 构造器注入所有策略，具体对象由 Spring 容器创建。
    private final List<ReportQuery> handlers;

    // 保存注入的策略列表，便于增加实现而不改调用方。
    public ReportQueryFactory(List<ReportQuery> handlers) {
        this.handlers = handlers;
    }

    // 根据类型返回策略；未知类型应立即报清楚的错误。
    public ReportQuery get(String type) {
        return handlers.stream()
            // 只保留声明支持当前类型的实现。
            .filter(handler -> handler.supports(type))
            // 返回第一个匹配策略。
            .findFirst()
            // 不支持时失败明确，避免更深层出现空指针。
            .orElseThrow(() -> new IllegalArgumentException("不支持的报表类型"));
    }
}
~~~

真实项目可搜索 ServiceFactory，检查它如何注册和选择不同数据集服务。类名中有 Factory 只能提供线索，仍要读代码确认。

工厂负责创建或选择实现；策略负责封装可互换的业务算法。两者常组合使用，但工厂不应承载具体算法，策略也不一定需要独立工厂。若 `supports()` 可能同时匹配多个实现，应在启动时检测重复注册，避免依赖集合注入顺序偶然选择不同策略；有限固定类型可用 EnumMap 或显式 Map 保证映射唯一。

### 建造者（Builder）

**解决的问题：** 一个对象有许多可选字段时，避免长参数构造器和难以阅读的 null 占位。

适合构造复杂查询条件或不可变配置。如果对象只有一两个字段，直接构造通常更简单。

```java
// 示例：导出任务的格式是必填项，文件名和表头开关是可选项。
public final class ExportRequest {
    // 构建完成后字段不可重新赋值，避免任务运行中配置被意外改写。
    private final String format;
    // 保存导出文件名。
    private final String fileName;
    // 记录是否输出表头。
    private final boolean includeHeader;

    // 只允许通过 Builder 创建，保证 build() 中的校验不会被绕开。
    private ExportRequest(Builder builder) {
        // 保存已校验的导出格式。
        this.format = builder.format;
        // 保存文件名，空值已在 Builder 中处理为默认值。
        this.fileName = builder.fileName;
        // 保存表头选项。
        this.includeHeader = builder.includeHeader;
    }

    // 创建 Builder 时就要求提供必填格式，减少不完整对象。
    public static Builder builder(String format) {
        // 返回可继续设置可选项的构建器。
        return new Builder(format);
    }

    // 提供一个不可变配置对象的构建入口。
    public static final class Builder {
        // 保存必填格式。
        private final String format;
        // 默认文件名，调用方可以覆盖。
        private String fileName = "export.xlsx";
        // 默认包含表头。
        private boolean includeHeader = true;

        // 构造器立即保存必填项。
        private Builder(String format) {
            // 保留格式值；具体支持范围在 build() 里统一校验。
            this.format = format;
        }

        // 设置可选文件名，并返回自身以支持链式调用。
        public Builder fileName(String fileName) {
            // 记录调用方指定的名称。
            this.fileName = fileName;
            // 返回当前 Builder 继续配置其他选项。
            return this;
        }

        // 设置表头选项，并返回自身以支持链式调用。
        public Builder includeHeader(boolean includeHeader) {
            // 记录调用方指定的布尔值。
            this.includeHeader = includeHeader;
            // 返回当前 Builder 继续配置其他选项。
            return this;
        }

        // 检查构造条件后生成最终不可变对象。
        public ExportRequest build() {
            // 拒绝空格式，避免生成后再到导出深处才失败。
            if (format == null || format.trim().isEmpty()) {
                // 用明确异常说明缺少必填字段。
                throw new IllegalArgumentException("导出格式不能为空");
            }
            // 限制当前示例支持的格式。
            if (!"xlsx".equalsIgnoreCase(format) && !"csv".equalsIgnoreCase(format)) {
                // 未支持的格式在边界处失败。
                throw new IllegalArgumentException("不支持的导出格式");
            }
            // 返回完成校验且不可变的请求对象。
            return new ExportRequest(this);
        }
    }
}
```

调用方可以写成 `ExportRequest.builder("xlsx").fileName("orders.xlsx").includeHeader(true).build()`。Builder 让构造意图清楚，但不要给简单 DTO 的每个字段都生成 Builder；它也不能代替业务层对用户权限和导出范围的校验。

## 结构型：组合或适配对象

### 适配器（Adapter）

**解决的问题：** 两个接口不兼容，但希望调用方依赖统一接口。例如把多个文件存储厂商包装成同一个 FileStorage API。

数据中台连接不同数据库时，适配层可以统一元数据读取方法；数据库方言差异仍需显式处理。

适配器隔离供应商 DTO、方法名和错误码，避免第三方 SDK 类型渗入整个业务层。适配器应做语义映射和错误转换；不要只改方法名却丢失幂等键、超时或失败状态。

```java
// 业务层依赖稳定的文件存储能力，不直接知道供应商 SDK 的类型。
public interface FileStorage {
    // 保存文件并返回业务侧统一定义的对象键。
    String upload(String objectName, byte[] content);
}

// 假设这是第三方 SDK，接口和返回值由供应商决定。
public interface VendorBlobClient {
    // 调用供应商上传 API 并返回供应商自己的对象信息。
    VendorObject putObject(String bucket, String key, byte[] bytes);
}

// 适配器把内部 FileStorage 契约转换为供应商 SDK 调用。
public final class VendorFileStorageAdapter implements FileStorage {
    // 保存第三方客户端，由依赖注入容器提供。
    private final VendorBlobClient client;
    // 保存当前业务使用的 bucket 名称。
    private final String bucket;

    // 构造时注入客户端和目标 bucket。
    public VendorFileStorageAdapter(VendorBlobClient client, String bucket) {
        // 保存客户端依赖。
        this.client = client;
        // 保存 bucket 配置。
        this.bucket = bucket;
    }

    // 实现业务侧接口并完成字段/返回值映射。
    @Override
    public String upload(String objectName, byte[] content) {
        // 将内部命名参数映射到供应商 SDK 的 putObject 调用。
        VendorObject result = client.putObject(bucket, objectName, content);
        // 只返回内部契约需要的对象键，不泄露供应商 DTO。
        return result.getKey();
    }
}
```

真实实现还应转换供应商异常、设置连接/读取超时、验证文件大小和类型，并避免把完整凭据或临时签名 URL 写入日志。更换供应商时，应主要替换 Adapter 和配置，而不是改所有业务调用点。

### 装饰器（Decorator）

**解决的问题：** 不修改原对象，就为它组合缓存、重试或审计行为。装饰器实现同一接口并包装另一个实现。

装饰器的顺序会影响行为：缓存包在重试外层与内层，缓存命中、失败和计数语义可能不同。

装饰器与代理都可能包装对象；Decorator 的重点是显式组合多个同接口行为，调用方可以决定组合顺序。它适合日志、指标、缓存等可叠加横切功能；如果依赖注入自动生成、调用方看不出包装链，则要提供清楚的配置和测试。

```java
// 定义一条通知发送能力，真实实现和装饰器都遵循同一接口。
public interface NotificationSender {
    // 向指定用户发送通知文本。
    void send(String userId, String message);
}

// 装饰器在原发送能力外面增加耗时记录，不改变发送接口。
public final class TimingNotificationSender implements NotificationSender {
    // 被包装的真实发送器，可以是邮件、短信或站内信实现。
    private final NotificationSender delegate;

    // 构造时传入需要增强的发送器。
    public TimingNotificationSender(NotificationSender delegate) {
        // 保存被包装对象。
        this.delegate = delegate;
    }

    // 调用前后测量时间，同时保持原始发送行为。
    @Override
    public void send(String userId, String message) {
        // 记录开始时间，供本次调用计算耗时。
        long startedAt = System.nanoTime();
        try {
            // 将原始请求委托给被包装的发送器。
            delegate.send(userId, message);
        } finally {
            // 无论成功还是抛异常，都记录耗时；真实代码应接入指标系统。
            long elapsedNanos = System.nanoTime() - startedAt;
            // 示例只展示结果，生产环境不要在日志中输出敏感消息正文。
            System.out.println("notification elapsed(ns)=" + elapsedNanos);
        }
    }
}
```

### 代理（Proxy）

**解决的问题：** 在目标方法前后插入权限、事务、日志或远程调用逻辑。Spring AOP 与 Feign 等机制会使用代理思想。

代理维持相同接口，但行为隐藏在容器或框架中。调试时要查增强配置、目标 Bean、异常转换和调用时序。

常见代理包括：

- **JDK 动态代理：** 基于接口创建代理；调用方通过接口使用目标对象。
- **类代理：** 通过子类方式代理目标类，具体限制依赖框架和版本。
- **远程代理：** Feign 代理把接口方法翻译成 HTTP 请求；它仍要处理网络错误和序列化。
- **Spring AOP 代理：** 在 Bean 方法边界插入事务、权限、缓存或日志等拦截器。

Spring 声明式事务常见限制是只有经过 Spring 代理的调用才会触发拦截；同一个类内部 `this.someTransactionalMethod()` 的自调用通常绕过代理。手工 `new` 出来的对象不受 Spring 容器增强。遇到事务、缓存或权限注解“不生效”，要检查对象是否由容器创建、调用是否跨代理边界、方法可见性和代理类型，而不是只确认注解写在源码上。

```java
// 独立的写服务由 Spring 管理，事务注解会由容器代理处理。
@Service
public class OrderWriter {
    // 在订单写入方法边界开启事务，具体回滚规则仍需按异常类型验证。
    @Transactional
    public void saveOrder(Order order) {
        // 调用 Mapper 写订单；这是示意代码，Mapper 由 Spring 注入。
        orderMapper.insert(order);
    }
}

// 另一个 Bean 通过代理调用 OrderWriter，而不是在同类里用 this 自调用。
@Service
public class OrderApplicationService {
    // 保存事务服务引用，由 Spring 构造器注入。
    private final OrderWriter orderWriter;

    // 创建应用服务时接收容器管理的 OrderWriter 代理。
    public OrderApplicationService(OrderWriter orderWriter) {
        // 保留代理引用，后续调用经过事务拦截器。
        this.orderWriter = orderWriter;
    }

    // 编排创建流程，并跨 Bean 边界调用事务方法。
    public void create(Order order) {
        // 经过 Spring 注入的代理调用 saveOrder。
        orderWriter.saveOrder(order);
    }
}
```

上例省略 Mapper 字段和 Order DTO 定义，关注代理边界即可。将 `saveOrder()` 改成同一个类里的 private 方法并从 `create()` 调用，通常不会经过 Spring 代理；要用集成测试验证提交/回滚，而不是只看单元测试中的普通对象调用。

## 行为型：决定分派规则或对象协作

### 策略（Strategy）

**解决的问题：** 多种算法或业务规则可以替换，调用方只依赖统一接口。策略常与工厂结合：策略定义行为，工厂选择实现。

与长 if/else 相比，策略便于增加实现；如果只有简单的两个分支，过早抽象会增加文件和跳转成本。

策略和工厂常配合：策略负责“怎么做”，工厂或注册表负责“选哪一个”。选择键必须由服务端白名单映射；不能把前端传入的类名反射实例化。

```java
// 每个折扣规则实现同一个策略契约。
public interface DiscountPolicy {
    // 根据订单原价计算折扣金额。
    BigDecimal discount(BigDecimal originalAmount);
}

// 新客策略给订单提供固定比例的折扣。
public final class NewCustomerDiscount implements DiscountPolicy {
    // 计算并返回折扣金额，BigDecimal 避免金额使用二进制浮点。
    @Override
    public BigDecimal discount(BigDecimal originalAmount) {
        // 乘以比例后按货币常用的两位小数做四舍五入。
        return originalAmount.multiply(new BigDecimal("0.10"))
            .setScale(2, RoundingMode.HALF_UP);
    }
}

// 结算服务只依赖策略接口，不需要知道具体算法类。
public final class CheckoutService {
    // 以服务端定义的等级键索引可用策略。
    private final Map<String, DiscountPolicy> policies;

    // 由构造器注入已经注册的策略表。
    public CheckoutService(Map<String, DiscountPolicy> policies) {
        // 保存白名单策略映射。
        this.policies = policies;
    }

    // 根据业务等级计算折扣，未知等级显式失败。
    public BigDecimal discountFor(String customerLevel, BigDecimal amount) {
        // 只从服务端策略表中取实现，不按客户端文本动态加载类。
        DiscountPolicy policy = policies.get(customerLevel);
        // 未注册的等级不静默回退成错误折扣。
        if (policy == null) {
            // 返回业务可识别的参数错误。
            throw new IllegalArgumentException("不支持的客户等级");
        }
        // 将具体折扣计算委托给匹配的策略。
        return policy.discount(amount);
    }
}
```

策略适用于规则变化相对独立、需要分别测试或按配置选择的情况。策略之间若高度共享状态或选择条件本身就很复杂，应拆出清楚的业务规则；不要让每个简单的 `if` 都变成一个类。

### 模板方法（Template Method）

**解决的问题：** 多个流程共享固定步骤，但个别步骤由子类定制。

例如文件导入流程都要校验、解析、转换和保存，不同格式只替换解析步骤。继承层级过深会让真实执行路径变得难懂。

模板方法把稳定顺序放在父类的 `final` 流程中，把变化步骤声明为抽象方法或受控钩子。它适用于流程顺序确实不变的导入、导出或批处理；若不同流程有很多步骤需要重排，用组合工作流会比大量子类更清楚。

```java
// 定义所有文件导入流程共用的步骤顺序。
public abstract class ImportTemplate<T> {
    // 固定执行校验、解析、行校验和保存，子类不能绕过关键顺序。
    public final int importFile(InputStream input) throws IOException {
        // 在解析前检查输入流是否存在。
        validateInput(input);
        // 调用子类实现，把具体格式解析成统一行对象。
        List<T> rows = parse(input);
        // 所有格式共享的行级业务检查。
        validateRows(rows);
        // 通过抽象步骤保存数据并返回成功行数。
        return save(rows);
    }

    // 让 CSV/Excel 等子类提供各自的解析实现。
    protected abstract List<T> parse(InputStream input) throws IOException;

    // 对空文件做基础校验；子类或业务层可增加更具体的规则。
    protected void validateInput(InputStream input) {
        // 空输入应在解析前给出清楚错误。
        if (input == null) {
            // 拒绝空流，避免底层解析器抛不清楚的空指针异常。
            throw new IllegalArgumentException("导入文件不能为空");
        }
    }

    // 所有格式共用的空结果校验。
    protected void validateRows(List<T> rows) {
        // 没有数据时不继续执行批量写入。
        if (rows == null || rows.isEmpty()) {
            // 用明确异常告诉调用方没有可导入记录。
            throw new IllegalArgumentException("文件中没有可导入记录");
        }
    }

    // 让具体实现决定写入数据库或提交到后续处理队列。
    protected abstract int save(List<T> rows);
}
```

父类应只保留确实稳定的流程；如果校验、解析和存储都要被不同组合反复替换，就将它们拆成可注入组件，让 Service 编排而不是不断增加子类。

### 观察者（Observer）与事件

**解决的问题：** 一个事件发生后通知多个订阅者，减少发布者对消费者的直接依赖。

Spring 进程内事件与消息队列可靠性不同：本地事件通常绑定进程生命周期，不自动持久投递；跨服务可靠通知需要考虑消息代理、重试和幂等。

观察者适合发布者只关心“发生了什么”，订阅者各自处理日志、统计或非关键后续动作。必须约定同步还是异步、监听器异常会不会影响主流程、监听顺序是否重要，以及事件能否丢失。

```java
// 定义订单已创建这一业务事实，不把整个 ORM Entity 暴露给监听者。
public final class OrderCreatedEvent {
    // 保存订单业务 ID，消费者用它读取所需数据或做幂等。
    private final String orderId;

    // 创建只包含必要信息的事件对象。
    public OrderCreatedEvent(String orderId) {
        // 保存订单 ID。
        this.orderId = orderId;
    }

    // 提供不可变事件字段的读取方法。
    public String getOrderId() {
        // 返回订单业务 ID。
        return orderId;
    }
}

// 定义订阅者处理事件的统一接口。
public interface OrderCreatedListener {
    // 接收订单创建事件并执行自己的后续动作。
    void onOrderCreated(OrderCreatedEvent event);
}

// 进程内发布器把事件发给当前进程注册的监听者。
public final class OrderEventPublisher {
    // 保存由容器注入的监听者集合。
    private final List<OrderCreatedListener> listeners;

    // 构造时提供所有监听者。
    public OrderEventPublisher(List<OrderCreatedListener> listeners) {
        // 保存订阅者列表。
        this.listeners = listeners;
    }

    // 同步通知所有监听者，调用会等待它们完成。
    public void publish(OrderCreatedEvent event) {
        // 依次遍历当前进程内的监听者。
        for (OrderCreatedListener listener : listeners) {
            // 同步执行监听逻辑；真实系统需明确异常是否影响主事务。
            listener.onOrderCreated(event);
        }
    }
}
```

如果 `publish()` 在数据库提交前同步运行，监听者可能看到尚未提交的数据；如果提交后进程立刻退出，通知可能没有持久化。需要可靠跨服务通知时，把业务状态和 Outbox 事件写入同一数据库事务，再由 relay 发布；不要只把进程内 Observer 改成异步线程就称为可靠消息。

## 分层不是 GoF 模式

Controller → Service → Mapper 属于分层架构，目的是隔离 HTTP、业务规则和数据访问职责。不要强行把每一层归入经典设计模式。

## SOLID：判断职责和依赖是否容易变化

SOLID 是五个面向对象设计原则的缩写。它们不是必须机械满足的检查表，而是帮助发现“一个改动要改很多无关类”“替换实现就破坏调用方”等维护成本。

| 原则 | 要解决的问题 | 在业务代码中的检查问题 |
|---|---|---|
| 单一职责（SRP） | 一个类因多种无关原因反复修改 | 这个类是否同时处理 HTTP、业务规则、SQL 和文件写入？变化是否来自同一职责？ |
| 开闭原则（OCP） | 新增一种业务类型时改动大量既有分支 | 能否通过新增策略实现扩展？若类型很少，直接分支可能更清楚 |
| 里氏替换（LSP） | 子类或实现虽然满足类型，却改变了调用方依赖的行为约定 | 替换实现后，返回值、异常、状态修改和边界行为是否仍符合接口契约？ |
| 接口隔离（ISP） | 调用方被迫依赖自己不使用的方法 | 能否按实际角色拆小接口，避免实现空方法或抛“不支持”？ |
| 依赖倒置（DIP） | 高层业务直接依赖数据库、支付平台等具体细节 | 业务规则能否依赖稳定接口，再由配置或依赖注入选择基础设施实现？ |

例如“创建订单”依赖 `PaymentProvider`，而不是直接在业务类里分支调用某个支付 SDK；测试时可以注入替身，运行时由配置选择实现。接口应代表真正稳定的业务能力，不要为了原则给每个只有一个实现的类都新建接口。

### 高内聚、低耦合与组合优先

高内聚表示一个模块里的代码共同完成清楚的职责；低耦合表示模块之间通过少量、稳定的契约协作。修改一个支付渠道配置不应迫使无关的报表逻辑一起重构。组合优先于继承常适用于可替换的行为：通过注入校验器、存储器或策略组合流程，通常比建立深层子类树容易测试；对于稳定且明确的“is-a”关系，继承仍有适用场景。

发现接口和类数量很多时，检查抽象是否隔离了真实变化点、调用流程是否更清楚、测试是否更容易。如果只是把简单逻辑拆成大量跳转文件，却没有降低变化影响范围，就可能是过度设计。

## 一个功能中怎样组合多个模式

以“按格式导出订单”为例，可以让 Controller 只接收并校验请求，应用服务负责权限和业务编排，工厂按服务端白名单选择导出策略，策略调用统一的文件存储接口，Adapter 转换到实际存储 SDK，Decorator 记录耗时和指标；Spring Proxy 则可能在应用服务边界处理事务或权限。每个模式都应隔离一类变化，不能把工厂、策略、代理和装饰器全部塞进一个类。

```text
HTTP Controller
  -> Application Service（权限、事务边界、业务编排）
  -> ExportFactory（根据枚举选择策略）
  -> CsvExportStrategy / ExcelExportStrategy（格式算法）
  -> TimingStorageDecorator（记录耗时）
  -> FileStorageAdapter（适配具体供应商 SDK）
```

不要让前端直接提交 Java 类名、Bean 名或可执行表达式来选择实现。接口字段应映射到有限枚举；未知类型要返回明确参数错误。

## 怎样验证模式没有隐藏错误

| 模式 | 关键测试 | 常见遗漏 |
|---|---|---|
| Factory/Strategy | 每种合法类型映射正确；未知类型和重复注册失败清楚 | 依赖注入顺序改变默认实现 |
| Builder | 缺少必填项、非法组合和默认值符合约定 | 可选字段被设置为 null 后绕开默认值 |
| Adapter | 字段、状态码、异常和超时映射正确 | 供应商异常被吞掉或错误码丢失 |
| Decorator | 真实操作只调用一次；成功/异常都记录指标；包装顺序符合预期 | 重试装饰器重复触发非幂等写入 |
| Proxy | 事务提交/回滚、权限拒绝和缓存拦截通过集成测试 | 测试用的对象不是容器创建的代理 |
| Template Method | 步骤顺序固定；某一步失败后不继续执行后续步骤 | 子类钩子跳过必要校验 |
| Observer/Event | 多个监听者的异常、顺序、线程和持久性符合约定 | 一个监听者异常导致后续监听者不执行 |

单元测试适合验证算法和映射；事务代理、AOP、容器注册及消息可靠性需要结合 Spring 容器或真实基础设施的集成测试。一个模式被正确命名，不能证明它的失败路径正确。

## 在项目中阅读模式的步骤

1. 从调用点确认哪个对象决定了执行分支。
2. 找到共同接口，比较各个实现的行为差异。
3. 找到工厂或配置如何注册并选择实现。
4. 检查未知类型、异常和默认值的行为。
5. 评估模式是否让扩展更清楚，还是增加了无必要的抽象。

## 常见误区

- 只按类名推断模式，不读调用关系。
- 为了“可扩展”建立大量只有一个实现的接口和工厂。
- 工厂找不到实现时静默返回 null，让错误延迟发生。
- 用复杂字符串判断分派，使新增类型仍需改动核心逻辑。
- 以为注解代理对所有调用路径都生效；Spring 代理有边界和限制。

## 练习

在当前项目搜索一个 Factory 和它创建的实现。画出调用方、工厂、接口和实现之间的关系，说明新增一种类型时需要改哪些文件，以及未知类型怎样报错。再任选 Adapter、Decorator 或 Spring Proxy，写出它隔离的变化点和一个验证失败行为的测试。
