# 线程池、Future 与异步任务编排

业务请求通常提交的是**任务**，不是要求系统立刻创建一条新线程。线程池负责接收任务、安排工作线程、限制排队和并发，并把结果/失败交给调用方。线程如何创建见[线程专题](./threads.md)，共享数据如何保护见[锁专题](./locks.md)。

## 为什么不为每个请求 new Thread

假设一秒有 1,000 个导出请求，每次都 `new Thread(...).start()`，而导出耗时数秒：线程会持续累积，占用栈内存、CPU 调度、数据库连接和文件句柄。没有容量上限时，负载越大越容易把整个进程拖垮。

线程池通过复用工作线程减少创建销毁，并给任务数量设上限。它不能把机器的 CPU 或数据库容量变大，也不能保证内存里的任务在重启后恢复。支付发货、批量账单等不能丢的任务，需要持久任务记录或消息/outbox。

## ThreadPoolExecutor 的七个参数

| 参数 | 作用 | 选择时要回答的问题 |
|---|---|---|
| `corePoolSize` | 核心线程数，默认按需创建 | 平稳流量要并行处理多少项？是否预启动？ |
| `maximumPoolSize` | 最大线程数 | 突发时最多占多少 CPU/下游连接？ |
| `keepAliveTime` | 空闲非核心线程的回收等待时间 | 扩容线程何时缩回？是否另开核心线程超时？ |
| `unit` | 存活时间单位 | 秒和毫秒是否使用正确？ |
| `workQueue` | 等待执行的任务队列 | 最多排多少项？允许等多久？ |
| `threadFactory` | 线程名称、daemon、异常处理等 | 日志里能否辨识是哪种任务？ |
| `handler` | 拒绝策略 | 满载或关闭时如何明确告诉提交方？ |

简化的接收顺序：**线程数未到 core → 创建工作线程；否则尝试排队；队列满且未到 max → 扩容；都满或池关闭 → 拒绝**。注意它不是先扩到 max 再排队。实际源码还会复查运行状态，处理并发关闭等竞态。

### 不同队列的取舍

| 队列 | 特性 | 适用与风险 |
|---|---|---|
| `ArrayBlockingQueue` | 固定容量，FIFO | 容量清晰，常用于控制排队 |
| `LinkedBlockingQueue(capacity)` | 可指定容量，FIFO | 建议显式设置；无参版本容量极大，接近无界 |
| `SynchronousQueue` | 不存储任务，直接交接 | 没有空闲接收者就扩容/拒绝，需严格限制 max |
| `PriorityBlockingQueue` | 按优先级取任务，默认无界 | 不是按提交顺序；需要另行控制容量和任务可比较性 |

`Executors.newFixedThreadPool(n)` 默认使用近乎无界队列；`newCachedThreadPool()` 可能创建大量线程。它们可做小实验，但服务端需要按负载明确配置边界。不是把工厂方法全部换成构造器就自然安全，还要有合适的参数与过载反馈。

### 拒绝策略不是实现细节

| 策略 | 行为 | 适合场景与缺点 |
|---|---|---|
| `AbortPolicy` | 抛出 `RejectedExecutionException` | 请求入口可明确返回稍后重试/系统繁忙；调用方必须处理 |
| `CallerRunsPolicy` | 未关闭时由提交线程执行 | 可向上游施加背压，但会阻塞 Servlet/事件循环调用者；关闭后不执行任务 |
| `DiscardPolicy` | 丢弃新任务 | 仅适用于业务明确可丢并有观测的场景；Future 可能永远不完成 |
| `DiscardOldestPolicy` | 丢弃队头，再尝试提交新任务 | 可能丢弃关键旧任务；不适合付款、发货 |
| 自定义 | 记录并返回可识别拒绝或转可靠存储 | 必须明确是否真正接收，不要打印日志后假装成功 |

为已经返回给用户的任务 ID 提供“已接收”状态时，要确保任务已可靠记录。线程池拒绝了执行但页面仍显示导出成功，是业务契约错误。

## 完整例子一：队列、扩容和拒绝

场景：最多两个发布工作线程，只有一个排队位置。先让工作线程等待闸门，确定性地制造饱和，再验证第四个任务被拒绝，最后放行并回收资源。它只打印演示信息，不修改真实页面。

<a href="/examples/concurrency/PoolSaturationDemo.java" download>下载 PoolSaturationDemo.java</a>

<<< ../../public/examples/concurrency/PoolSaturationDemo.java{java}

运行后应观察到：第四个任务拒绝、已接收的三个任务结果合计为 6。用 latch 协调能保证检查时任务仍占用工作线程；不能通过“随便 sleep 100 毫秒”假定线程已启动。

```powershell
# 进入下载示例所在目录，在 Java 11 或更高 JDK 上编译为 Java 11 目标。
javac -encoding UTF-8 --release 11 PoolSaturationDemo.java

# 运行程序；内部断言失败会以非零状态退出。
java '-Dfile.encoding=UTF-8' PoolSaturationDemo
```

## execute、submit、Future 的区别

| API | 返回/失败方式 | 什么时候用 |
|---|---|---|
| `execute(Runnable)` | 无结果句柄；逃出的异常交给线程的未捕获异常处理机制 | 无返回值且有明确错误记录的任务 |
| `submit(Runnable)` | 返回 Future；正常结果通常为 null | 需要观察完成、异常或尝试取消 |
| `submit(Callable<T>)` | 返回 `Future<T>`，包含计算结果/异常 | 有返回值的后台计算 |
| `Future.get()` | 阻塞取结果；失败包装为 ExecutionException | 已确定允许等待，且有外围超时控制 |
| `Future.get(timeout, unit)` | 限制本次等待；超时抛 TimeoutException | 有明确响应时间预算的请求 |
| `Future.cancel(true)` | 请求取消；可能中断正在运行的任务 | 操作允许取消且任务会响应中断 |

任务经 `submit` 抛出的异常通常被保存在 Future 中；如果从不 `get`、从不监控失败，异常可能无人观察。`isDone()` 为 true 也不代表成功：取消和异常完成都算 done。

多个 Future 顺序等待时，如果每个都等待三秒，总时间可能累计。为整个业务建立截止时间，再给每次等待传入剩余预算；或者组合成一个结果并对整体等待设限。`TimeoutException` 只表示调用方停止等待，不自动停止后台任务，更不表示支付平台没有执行。

## CompletableFuture 如何拼接流程

| 方法 | 含义 | 场景 |
|---|---|---|
| `runAsync(action, executor)` | 异步执行无返回值动作 | 有控制的后台任务 |
| `supplyAsync(supplier, executor)` | 异步计算结果 | 用户信息/统计查询 |
| `thenApply` | 用上一步结果计算另一个值 | DTO 转 VO、格式化 |
| `thenCompose` | 用结果启动下一项异步操作并展开嵌套 | 先查用户，再按用户 ID 查订单 |
| `thenCombine` | 合并两个独立任务的结果 | 账户资料与独立统计并行查询 |
| `allOf` | 等所有 Future 完成，返回 `CompletableFuture<Void>` | 统一等待；具体结果仍需从各任务读取 |
| `anyOf` | 任意任务完成就完成，可能是异常完成 | 只需要最先完成结果；不是自动“第一个成功结果” |
| `whenComplete` | 观察结果或失败，一般不转换结果 | 记录耗时/错误；回调自己抛异常仍可能改变结果 |
| `handle` | 同时处理成功与失败并返回新值 | 显式转成带状态的结果对象 |
| `exceptionally` | 对失败返回替代值 | 只有业务允许降级时使用，不把付款失败改成成功 |
| `orTimeout` | 到时异常完成该 Future | Java 9+；不保证中断执行函数 |
| `completeOnTimeout` | 到时用默认值完成该 Future | Java 9+；只用于语义合理的可选能力 |

非 Async 形式的回调可能在完成前一步的线程或注册回调的线程执行，不应把它当成固定的“回到主线程”。Async 版本应显式传业务线程池，避免阻塞 I/O 霸占公共 `ForkJoinPool.commonPool()`。`thenApply` 转换值，`thenCompose` 展开异步结果，类似前端 Promise 的链式组合，但线程上下文不同。

### join、超时与取消的细节

- `get` 可被中断，异常通常是 `ExecutionException`；`join` 用非受检 `CompletionException` 包装失败，不提供同样的可中断等待接口。
- `CompletableFuture.cancel(true)` 不会因为参数为 true 就中断计算函数；这一点与普通 FutureTask 的取消实现不同。
- 组合任务中一项失败，不自动取消其余兄弟任务。特别是网络写入可能仍在进行。
- 要限制真正的 I/O 等待，必须配置 HTTP、JDBC、连接池等底层超时；取消句柄不替代网络超时。
- 不要让同一个小线程池的所有工作线程都提交子任务到这个池，然后在工作线程里 `get()`；子任务没有剩余线程执行，会发生线程饥饿。

## 完整例子二：同时获取资料和订单数

两项查询互不依赖，可以先并发提交，再通过 `thenCombine` 汇总；示例同时验证一个任务失败时，错误能被取到而不是变成“订单数 0”。这里用内存函数模拟数据源，没有发起真实 I/O，因此不以执行耗时声称提速。

<a href="/examples/concurrency/AsyncAggregationDemo.java" download>下载 AsyncAggregationDemo.java</a>

<<< ../../public/examples/concurrency/AsyncAggregationDemo.java{java}

```powershell
# 编译和运行，不需要 Maven 或 Spring。
javac -encoding UTF-8 --release 11 AsyncAggregationDemo.java
java '-Dfile.encoding=UTF-8' AsyncAggregationDemo
```

预期有 `learner: paidOrders=3` 和失败 cause 已被观察到的提示。示例中的池只服务本次程序，退出时关闭；Web 应用应在应用生命周期结束时关闭共享池，不能在每个 Controller 请求 finally 中关掉它。

## 线程数量不是一套固定公式

CPU 密集工作可从接近可用 CPU 核数的规模开始实测；I/O 工作等待多，可能允许更多线程，但还受数据库连接池、HTTP 连接池、接口限额和容器资源限制。

假设一个下游查询平均耗时 200 毫秒、稳定到达率 40 次/秒，在稳定系统里平均在途量约为 `40 × 0.2 = 8`。这只是容量估算，不能直接推出“设八个线程即可”：尾延迟、突发、重试、连接上限、其他调用者都要纳入。

观测至少包括：执行耗时、排队耗时、活跃线程、队列长度、拒绝次数、超时率、错误率和下游连接等待。通常应先优化慢 SQL 和不必要的远程调用，再扩大线程池。

## Spring Boot 中如何使用 @Async

业务代码适合使用应用管理的线程池 Bean，而不是每个请求里构造一个池。下面是 **Spring Boot 2.7 集成片段**，不是仅靠 javac 就能运行的独立例子；需要项目的 Spring 依赖。配置与服务分别放在各自文件中。

```java
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 启用代理式异步方法执行；应用启动时创建共享线程池。
@Configuration
@EnableAsync
public class AsyncConfig {
    // 显式命名线程池，@Async 可选择自己的业务池。
    @Bean("reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 这些数值仅作教学示意，实际应按负载和数据库连接容量确定。
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("report-");
        // 满载时明确拒绝，提交方负责把异常映射成业务反馈。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时允许已接收任务继续；20 秒只限制容器等待，不会自动强制停止任务。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // 作为 Spring Bean 返回，初始化和销毁交给容器。
        return executor;
    }
}
```

```java
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// 从另一个 Spring Bean 调用此对象的代理，才会触发默认 @Async 拦截。
@Service
public class ReportAsyncService {
    // completedFuture 并不会取消异步：方法本身已由代理安排到业务池执行。
    @Async("reportExecutor")
    public CompletableFuture<String> render(String reportId) {
        // 输入通过参数传递，不能写入单例 Service 的 currentReport 字段。
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId 不能为空");
        }
        // 演示返回值；实际生成应带 I/O 超时并向调用者暴露失败。
        return CompletableFuture.completedFuture("report:" + reportId);
    }
}
```

使用时注意：

- 同类里的 `this.render(...)` 默认绕过代理，不会因为注解就异步；私有方法也不是这种代理的合适入口。
- 返回 Future 的方法由调用方观察失败；`void @Async` 需配置 `AsyncUncaughtExceptionHandler` 等错误观测。
- 登录身份和 MDC 不自动随任务迁移；可使用框架安全上下文包装/TaskDecorator，并正确清理，不要随意复制完整 request 对象。
- 数据库事务不能通过 TaskDecorator 复制到另一个线程；异步任务需要独立且明确的事务边界。
- `@Async` 是调度方式，不是可靠消息队列；需要持久化的业务任务必须有任务表/outbox 和恢复机制。

## 定时线程池与生产者消费者

`ScheduledExecutorService` 的 `schedule` 执行一次延迟任务；`scheduleAtFixedRate` 以目标节拍安排；`scheduleWithFixedDelay` 在上次结束后再等待固定间隔。一个周期任务异常逃出时，后续执行可能被抑制，需要明确记录和恢复策略。

同一个周期任务不会因上次没结束而自动自我重叠，但多个服务实例会分别调度，因此不天然防止跨实例重复。订单超时关闭、查单补偿尤其需要数据库状态约束和任务归属控制。

生产者消费者模型通过有界 `BlockingQueue` 隔开提交速度与处理速度；满队列必须产生等待、拒绝或可靠转存，而不是无限缓存。

## 关闭、取消与支付中的正确边界

`shutdown()` 停止接收新任务并继续处理已提交任务；`shutdownNow()` 尝试中断执行线程并返回尚未开始的任务，它不会保证任务立刻停止。未开始的 Future 也需由任务所有者明确取消或重新安排，不能直接遗忘。

在支付回调中，不应“把更新订单丢进线程池后立即告诉平台成功”。应先验签、核对并提交支付状态及 outbox，然后按对应平台协议响应成功。后续通知与发货消费者处理时再保证幂等。完整例子见[支付宝与微信支付](../../examples/online-payment.md)。

## 自测问题

1. core=2、max=8、无界队列时，为什么业务通常只能看到两个工作线程？
2. CallerRunsPolicy 为什么可能把一个原本异步的接口拖慢？
3. get 超时后，下游是不是已经停止？支付结果应该如何确认？
4. 同一个池里父任务等待子任务时，为什么可能所有任务都卡住？
5. 支付状态已经更新但进程在发消息前退出，outbox 怎样帮助恢复？

下一步：[支付宝与微信支付实战](../../examples/online-payment.md)，或继续 [JVM 与垃圾回收](./jvm.md)。
