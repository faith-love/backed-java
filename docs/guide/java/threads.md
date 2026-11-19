# 线程：从前端异步到 Java 执行模型

线程解决“程序如何同时推进多个执行流程”的问题。先分清任务、线程与异步，才能判断一个接口是否需要并发、为什么启动了线程仍然慢，以及如何在任务取消时正确收尾。

本文以项目使用的 Java 11 为基线，示例使用 JDK 17 的 `--release 11` 编译。Java 21 的虚拟线程不属于本章运行环境。先读[并发学习入口](./concurrency.md)，掌握本章后再读[锁与共享状态](./locks.md)和[线程池](./thread-pools.md)。

## 1. 进程、线程、任务分别是什么

### 解决什么问题

前端里的事件循环、Promise、Web Worker 都与执行顺序有关，但并不等价于 Java 的 Thread。先建立边界，避免把“创建了一个任务对象”误认为“开启了一个线程”。

| 概念 | 含义 | 在项目中的例子 |
|---|---|---|
| 进程 | 操作系统管理的运行中程序，拥有自己的地址空间和资源 | 分别启动 gateway、auth、dashboard 的多个 Java 进程 |
| 线程 | 一个进程中的执行流程，拥有自己的调用栈；可共享进程内对象 | Servlet 请求处理线程、调度线程、后台工作线程 |
| 任务 | 想完成的工作，描述输入、计算和输出 | 查询一个数据集、解析一份文件、生成报告 |
| 线程池 | 管理一组工作线程和等待任务，限制资源使用 | Spring 管理的任务执行器、应用专用执行器 |
| Future | 跟踪某个任务的结果、异常和取消状态的句柄 | 提交报告生成任务后获得的返回值 |

任务可以由调用线程直接执行，也可以交给新线程或线程池执行。一个线程可以先后执行很多任务；一项复杂任务也可能拆成多个子任务。

### 与 Vue / JavaScript 的对应关系

- 一般页面 JavaScript 的同步代码在同一主线程中执行，Promise 回调通过事件循环调度。`Promise.all` 等待多个 Promise，不会自动为计算密集代码创建 CPU 并行线程。
- HTTP 请求可以同时由网络系统推进，页面主线程不必一直阻塞等待，所以“多个请求同时进行”不等于“多个 JS 回调同时执行”。
- Web Worker 提供另一执行上下文；它与主线程通常通过消息通信。Java 普通线程共享堆对象，直接共享可变对象更常见，因此要主动考虑线程安全。
- Java 中直接调用耗时方法，通常仍由当前线程同步执行。方法名叫 `asyncQuery` 也不会使它自动异步。

## 2. 并发、并行、同步、异步怎样区分

### 解决什么问题

这几组词描述不同维度。混用会导致“接口异步了就一定更快”“多线程一定同时占用多个 CPU 核”等错误判断。

| 维度 | 含义 | 示例 |
|---|---|---|
| 并发 | 一段时间内多个任务都在推进，可以交错执行 | 一个核心交替处理多个任务 |
| 并行 | 同一时刻多个任务实际同时执行 | 多核 CPU 同时计算互不依赖的分片 |
| 同步调用 | 调用方的后续步骤依赖当前调用完成 | `client.send(...)` 返回后才处理响应 |
| 异步调用 | 先发起工作，通过 Future、回调等以后接收完成结果 | `submit(task)` 先返回 Future，稍后处理结果 |
| 阻塞等待 | 当前线程暂停推进，等待锁、结果或事件 | `Future.get()`、`Thread.join()` |
| 非阻塞尝试 | 暂时不能完成时先返回状态，不挂起线程等待 | `queue.poll()`，队列空时直接返回 |

同步/异步关注调用与结果的协作方式，阻塞/非阻塞关注等待时线程如何行动。异步任务内部仍可能执行阻塞数据库查询；前端 `await` 挂起的是异步函数的继续执行，不能直接等同于 Java `Future.get()` 阻塞当前线程。

例如两个独立 HTTP 调用串行共需约 600 ms 与 800 ms，并发时理想耗时接近较慢的 800 ms，加上调度与汇总成本。如果第二次调用依赖第一次返回的 ID，则不能直接并发；如果两次都争抢同一个数据库连接，并发也未必缩短耗时。

## 3. 什么时候值得开线程

### 解决什么问题

线程消耗栈内存、调度资源和下游容量。目标是缩短可并行工作的关键路径，或把可延后任务交给受控执行器，并非“凡是慢操作就 new Thread”。

| 场景 | 判断方式 | 通常做法 |
|---|---|---|
| CPU 密集 | 数据已在内存，主要时间用于压缩、计算、解析 | 在可分割且工作量足够时并行；并发度结合可用 CPU 配额测试 |
| I/O 密集 | 大量时间等待数据库、HTTP 或存储返回 | 独立调用可受控并发，同时限制连接数、超时和下游压力 |
| 短小同步逻辑 | 校验参数、转换几条字段、少量计算 | 直接在当前线程完成，创建线程的成本可能更大 |
| 有严格依赖顺序 | 后一步依赖前一步输出或事务结果 | 明确按序执行；先分析可分割边界 |
| 长期后台工作 | 定时刷新、批量报告、消息处理 | 使用受管理执行器或任务系统，明确状态、取消和失败恢复 |
| 不能丢失的任务 | 支付、关键导出、必须完成的数据同步 | 任务持久化与可恢复机制；普通后台线程不提供可靠交付 |

### Servlet 已经有请求线程

典型 Spring MVC / Servlet 服务由容器线程池分配请求线程。两个浏览器请求本来就可能同时执行同一个单例 Service 的方法；不需要在 Controller 里 `new Thread` 才会出现并发。

把一次数据库查询放进新线程，然后立即 `get()` 等结果，会让请求线程等待、工作线程也占着资源，通常只是多花一个线程。网关等响应式模块可能采用事件循环，其阻塞处理限制与 Servlet 不同，要按具体模块的依赖和配置判断。

### 不应该随手开线程的情况

- 每次请求都 `new Thread`：流量上升时线程数、内存和数据库连接需求一起失控。
- 为了规避事务错误而丢到后台：原线程的事务通常不会自动覆盖新线程。
- 在后台捕获异常后只打印日志：HTTP 已返回成功，调用者无法得知实际失败。
- 假定身份、日志 trace 和其他 ThreadLocal 自动传到新线程。
- 让线程持有请求/响应对象，HTTP 生命周期结束后继续使用。
- 希望客户端取消请求就自动中断后台任务；需要显式设计取消传播。

生产应用优先查看[线程池的提交、容量与关闭](./thread-pools.md)。本章使用显式 Thread，是为了观察线程自身的生命周期。

## 4. Java 11 的六种线程状态

### 解决什么问题

线程转储中看到的状态可以帮助区分等待 CPU、争抢监视器、等待事件和正常结束。状态是瞬时观察值，不是安排任务顺序的同步协议。

| `Thread.State` | 含义 | 常见触发 |
|---|---|---|
| `NEW` | Thread 对象已创建，但没有成功启动 | `new Thread(task)` 后，尚未 `start()` |
| `RUNNABLE` | 可运行或正在 JVM 中执行；也可能在等某些系统资源 | 计算中的线程、部分原生或网络 I/O 调用 |
| `BLOCKED` | 等待获得对象监视器锁 | 进入被其他线程占用的 `synchronized`，或 `wait` 结束后重新竞争该锁 |
| `WAITING` | 无显式超时地等待其他动作 | `Object.wait()`、`Thread.join()`、`LockSupport.park()` |
| `TIMED_WAITING` | 有时间界限地等待 | `sleep`、限时 `wait/join`、`parkNanos` |
| `TERMINATED` | `run()` 正常返回或因未捕获异常退出 | 线程任务已经结束 |

几个判断边界：

- Java 没有单独的 `RUNNING` 枚举；`RUNNABLE` 不证明线程当时正在消耗 CPU。
- `BLOCKED` 专指对象监视器竞争。等待 `ReentrantLock` 的线程常表现为 `WAITING/TIMED_WAITING`，不能只看状态名字就判断“没有锁竞争”。
- 执行 `notifyAll()` 后，等待者还需重新获得监视器，可能先处于 `BLOCKED`。
- `getState()` 结果可能立刻改变。不要写“不断读到 WAITING 后就认为业务准备好了”；使用 `CountDownLatch`、Future 或明确业务条件。
- 成功 `join` 只证明线程已经结束，不证明业务成功。任务可能因异常退出，仍需检查结果或异常通道。

## 5. 定义任务与启动线程的四种写法

### Runnable + Thread

`Runnable` 的 `run()` 描述任务，无返回值。`Thread` 是执行任务的一个具体线程对象。

- `new Thread(task)` 创建 Thread 对象，没有启动新的执行流程。
- `thread.run()` 是普通方法调用：由调用线程执行，不会把该 Thread 从 NEW 变成已启动。
- `thread.start()` 请求启动新线程，由 JVM 在新线程中调用 `run()`；何时真正得到 CPU 由调度决定。
- 同一个 Thread 对象只能成功 `start()` 一次，执行结束也不能重启。需要再次执行时创建新 Thread，或向线程池再提交任务。
- `start()` 之前对对象的初始化对新线程可见；成功等待线程终止后，其完成的写入对等待方可见。运行期间共享可变状态仍需同步。

### 继承 Thread

继承 Thread 并重写 `run()` 也能定义任务，仍要调用 `start()`。Java 类只能继承一个类；把业务任务和线程控制混在一起也不利于提交到不同执行器，因此业务代码通常更倾向 Runnable/Callable 配合执行器。

### 示例一：run 与 start、二次启动和继承

保存为 `ThreadStartBasicsDemo.java`，或<a href="/examples/concurrency/ThreadStartBasicsDemo.java" download>下载完整源码</a>。页面从同一源文件导入代码，后续维护只需修改这一份源码。

<<< ../../public/examples/concurrency/ThreadStartBasicsDemo.java

预期输出：

~~~text
PASS: run() 在调用线程执行，Thread 仍为 NEW
PASS: start() 在新线程执行，join 后为 TERMINATED
PASS: 同一个 Thread 不能二次 start()
PASS: 继承 Thread 并覆盖 run() 也能定义任务
~~~

示例中的 `join(3_000)` 只限定最长等待时间，之后仍检查 `isAlive()`。测试断言使用显式异常，不依赖 JVM 的 `-ea` 开关。

### Callable + FutureTask

`Callable<V>` 描述有返回值、可抛受检异常的任务，但它不是线程，`new Thread(callable)` 不是合法构造形式。`FutureTask<V>` 同时具备 Runnable 与 Future 能力：可以交给 Thread 执行，也能保存返回值、异常或取消状态。

关系如下：

~~~text
Callable<Integer>          // 要计算什么、返回什么
        ↓ 交给
FutureTask<Integer>        // 执行包装 + 完成状态 + 结果/异常
        ↓ 交给
Thread                     // 谁来执行
~~~

`FutureTask.run()` 本身也能由当前线程直接执行；是 Thread.start 或线程池负责选择执行线程。FutureTask 一旦完成不会因为再次调用 run 就重新计算，想执行新的业务任务需建立新的任务实例。

### 线程池 submit

`ExecutorService.submit(runnable)` 或 `submit(callable)` 提交任务并返回 Future，由线程池复用工作线程。不要把每个 Future 与一个独占线程一一对应。

提交和执行也有区别：线程池队列中可能有等待任务，提交后不一定立刻开始。异常、拒绝、队列边界和关闭方式见[线程池](./thread-pools.md)。如果任务由 Spring 管理的共享线程池执行，单个请求不应负责关闭整个池。

## 6. 中断是取消请求，不是强制杀死线程

### 解决什么问题

当请求超时、任务不再需要或应用准备关闭时，需要让工作线程停止等待或提前结束计算。`interrupt()` 提供协作信号，任务自己要响应并执行清理。

| 操作 | 行为 |
|---|---|
| `thread.interrupt()` | 请求对目标线程中断；具体反应取决于目标正在做什么 |
| `thread.isInterrupted()` | 查看指定线程中断标记，不清除 |
| `Thread.interrupted()` | 查看并清除**当前线程**中断标记；这是静态方法 |
| `sleep/wait/join` 被中断 | 通常抛 `InterruptedException`，同时清除中断标记 |
| 普通 CPU 循环被中断 | 不会自动退出，要检查中断标记并返回 |
| 等待进入 `synchronized` 被中断 | 不会因此立刻放弃监视器获取 |
| 阻塞 I/O 被中断 | 取决于 I/O API 和实现；不能假定所有 Socket/JDBC 调用都会抛 InterruptedException |

处理 `InterruptedException` 时选择清晰的边界：能向上层声明就继续抛出；若在 `Runnable.run()` 中无法抛出受检异常，通常恢复标记并尽快退出。不要捕获后继续无限循环。

不要使用已废弃的 `Thread.stop()` 强制结束线程。它可能让锁在数据更新一半时释放，使对象处于不一致状态。对于网络和数据库调用，取消还要配合 API 本身的超时或资源关闭机制。

### Future.get 超时与 cancel 的区别

- `future.get(timeout, unit)` 超时只结束**本次等待**，不会自动取消任务。
- `future.cancel(true)` 尝试取消，并尝试中断正在执行的任务；返回 true 不证明工作代码已经停止。
- `future.isDone()` 包含正常完成、异常完成和取消，不能用它当作“业务成功”。
- 正常结果由 get 返回；任务失败通常由 get 抛 `ExecutionException` 包装原因；取消后 get 抛 `CancellationException`。
- 若任务忽略中断、仍在外部系统执行，它可能在 Future 显示取消后继续产生副作用。线程池场景下尤其要区分任务状态与工作线程状态。

### 示例二：返回值、限时等待和可协作取消

保存为 `ThreadFutureTaskDemo.java`，或<a href="/examples/concurrency/ThreadFutureTaskDemo.java" download>下载完整源码</a>。任务通过 CountDownLatch 通知“已进入执行”，主线程不发出解除等待信号，因此短超时能可靠验证“等待超时并不取消任务”。30 秒是异常情况下的兜底上限，正常路径会马上中断退出。

<<< ../../public/examples/concurrency/ThreadFutureTaskDemo.java

预期输出：

~~~text
PASS: Callable 返回值由 FutureTask.get() 取得：42
PASS: get(timeout) 只结束本次等待，任务仍未完成
PASS: cancel(true) 发出中断，任务响应后退出并完成清理
~~~

示例先检查 Future 已取消，再通过限时 join 检查工作线程真正退出，并断言任务观察到中断、finally 清理已完成。这三件事不能用一个 isCancelled 代替。

## 7. join、sleep、yield、wait 会不会释放锁

### 解决什么问题

“线程暂时不执行”与“把锁交给别人”是两件事。在锁内 sleep 或 join 可能让其他线程一直等锁；wait 则为了等待共享条件，暂时交出指定对象的监视器。

| 调用 | 等待什么 | 是否释放锁 | 使用要点 |
|---|---|---|---|
| `Thread.sleep(...)` | 请求暂停一段时间，到时后仍需调度；可被中断 | 不释放已持有的业务锁 | 时间经过不是其他任务已完成的证据 |
| `Thread.yield()` | 向调度器提示当前线程愿意让出执行机会 | 不释放锁 | 调度器可忽略，无顺序和公平性保证 |
| `other.join(timeout)` | 等待 other 线程终止，或超时/中断 | 不会释放当前线程持有的无关业务锁 | 返回后检查 isAlive；不要持着 other 完成所需的锁等待 |
| `monitor.wait(timeout)` | 等待通知、超时、中断或虚假唤醒 | 只释放 monitor 对应的对象监视器 | 必须先持有该监视器；返回前重新获取 |
| `monitor.notifyAll()` | 通知该 monitor 上的等待者重新竞争锁 | 不立即释放监视器 | 调用者退出 synchronized 后其他线程才有机会获取 |

Java 11 的 join 实现在目标 Thread 对象上使用 wait 机制；这不意味着业务代码持有的其他锁也被释放。不要把 Thread 对象本身当业务锁，也不要依赖 join 的内部监视器机制设计业务同步。

`wait()` 只能在持有同一对象监视器时调用，否则抛 `IllegalMonitorStateException`。必须放在条件循环中，避免虚假唤醒或其他线程先消费条件：

~~~java
// 此片段只展示条件等待结构，完整可运行程序见下方示例。
synchronized (monitor) {
    // 每次重新获得锁都检查条件，不能只用 if 检查一次。
    while (!ready) {
        // 实际实现还要计算剩余时间，超时后停止等待。
        monitor.wait(timeoutMillis);
    }
    // 只在条件成立时读取受同一监视器保护的数据。
    consume();
}
~~~

嵌套持有多把锁时，wait 只交出它调用对象的那一把监视器。`Condition.await()` 则释放对应的 Lock，并在返回前重获该 Lock，具体用法放在[锁章节](./locks.md)。

### 示例三：验证 wait 和 notifyAll 的锁行为

保存为 `ThreadWaitSignalDemo.java`，或<a href="/examples/concurrency/ThreadWaitSignalDemo.java" download>下载完整源码</a>。共享条件只在同一 monitor 内读写，等待使用剩余时间预算，后台异常会回传给主线程断言。

<<< ../../public/examples/concurrency/ThreadWaitSignalDemo.java

预期输出：

~~~text
PASS: wait 释放 monitor，notifyAll 不立即释放 monitor
PASS: 释放 monitor 后读取到 hello
~~~

这里 CountDownLatch 只表示读取线程进入了临界区；真正的消息条件由 monitor 保护。主线程发出 notifyAll 后仍持有监视器，因此可以确定读取线程尚不能完成读取，无需 sleep 或观察调度概率。

## 8. daemon 线程与 JVM 生命周期

### 解决什么问题

JVM 正常运行时，非守护线程会维持进程存活；当只剩守护线程时，JVM 可以退出。这决定一个后台任务是否能阻止进程结束。

- 主线程通常是非守护线程；新建线程通常继承创建它的线程的守护属性。
- 守护属性应在 start 前通过 `thread.setDaemon(true)` 设置；对仍在运行的线程设置会抛 `IllegalThreadStateException`。线程结束后再改属性也无法影响已经完成的执行。
- daemon 不是低优先级线程、不是独立进程，也不提供“后台任务一定完成”的保证。
- 不要把关键保存、事务提交或必须送达的消息交给无可靠性保障的 daemon 线程。
- 如果 JVM 因只剩 daemon 线程而退出，不保证其代码继续执行到 finally；正常控制流程中的 finally 不能当作进程退出保障。
- 非守护线程也无法抵御进程强制终止、机器断电等故障；可靠任务还需要持久化与重试恢复。

本章示例使用普通非守护线程，并明确中断、限时 join 和检查清理结果，避免把 daemon 作为“程序结束不了”的补丁。

## 9. 编译与运行示例

### 解决什么问题

能编译只能确认类型和 API 基线；还要实际运行并检查输出，才能确认启动、取消和等待逻辑符合预期。

在已下载三个 Java 文件的目录运行以下命令。`--release 11` 限定字节码和公开 API 基线；它不代表实际运行时就是 JDK 11。

~~~powershell
# 查看当前编译器与运行时版本；本章验证环境为 JDK 17。
javac -version
java -version

# 创建独立 class 输出目录，避免编译产物混进源码。
New-Item -ItemType Directory -Path .\classes -Force | Out-Null

# 将三个独立示例按 Java 11 的语言、API 和字节码基线编译。
javac -encoding UTF-8 --release 11 -d .\classes .\ThreadStartBasicsDemo.java .\ThreadFutureTaskDemo.java .\ThreadWaitSignalDemo.java

# 默认字符集指定为 UTF-8，保持中文输出一致。
java '-Dfile.encoding=UTF-8' -cp .\classes ThreadStartBasicsDemo
java '-Dfile.encoding=UTF-8' -cp .\classes ThreadFutureTaskDemo
java '-Dfile.encoding=UTF-8' -cp .\classes ThreadWaitSignalDemo
~~~

实际验证记录（2026-11-17）：Temurin JDK 17.0.19；三个完整示例均以 `javac -encoding UTF-8 --release 11` 编译，并在同一 JDK 17 环境运行。三个进程均在 15 秒的外部验证时限内以退出码 0 结束，PASS 输出逐行匹配本页预期，未产生标准错误输出。未使用 JDK 11 运行时执行，不将编译基线验证等同于运行时验证。

## 10. 项目阅读与排错

以当前项目的 Controller、Service 和异步工具为入口，逐个记录“谁启动、谁等待、谁取消、谁清理”。

1. **定位执行者**：搜索 `new Thread`、`ExecutorService`、`submit`、`@Async`，查看工作是否由容器、线程池或手写线程执行。
2. **识别共享对象**：单例 Service 的依赖字段通常是共享引用；当前请求状态应该位于方法局部变量或明确的数据结构中。
3. **检查等待链**：Controller 是否提交后台任务后立刻 get；线程是否持有业务锁等待 join/Future；下游连接池是否排队。
4. **检查异常通道**：Runnable 的未捕获异常是否仅写日志；submit 返回的 Future 是否被忽略；任务失败是否能在接口或状态页呈现。
5. **检查取消链**：客户端超时、Future 超时、线程中断和数据库/HTTP 请求取消是否真正关联。
6. **检查停机**：是谁拥有线程或执行器、容器关闭时如何停止提交、已有任务何时结束。
7. **解释转储**：结合线程名、堆栈和 CPU 使用率分析状态，避免把所有 WAITING 都当故障，或把所有 RUNNABLE 都当高 CPU。

如果报告生成放到异步任务中，还要明确登录身份、权限检查、事务边界和 trace 的传递。不要把“换了线程”当作这些业务上下文自动继承的证据。

## 11. 常见误区

- 把 `run()` 当启动方法：它只是当前线程的同步调用；启动用 start。
- Thread 结束后再次 start：必须创建新的线程对象，或重新提交新的任务。
- Callable、FutureTask、Future 都是线程：它们分别描述工作或跟踪结果，执行者另有其人。
- start 后下一行必定晚于子线程：父子线程的后续执行顺序由调度决定，要用同步协议建立依赖。
- sleep 100 ms 就能保证对方完成：在负载、暂停或调试器下完全不成立。
- yield 提供公平性：它仅是调度提示。
- join(timeout) 返回就代表完成：可能只是超时，要检查 isAlive。
- interrupt 会立即停止所有代码：它是协作请求，不可中断的 I/O 或不检查标记的循环可能继续执行。
- Future 取消后外部副作用也一定撤销：取消状态不等于远端调用回滚。
- wait/notifyAll 后条件一定满足：需要在 while 中检查条件，并设计超时。
- daemon 任务会在 JVM 退出前完成：没有这种保证。

## 12. 练习

1. 运行示例一，把 Thread 的初始名字改掉，解释为什么 run 记录的是调用线程，而 start 记录新线程名。
2. 用自己的 Runnable 在正常返回前写一个结果，使用限时 join 等待并检查异常通道；不要用 sleep 保证顺序。
3. 运行示例二，记录 get 超时、Future 已取消、后台线程终止、finally 执行分别发生在哪一步。
4. 将示例三的 notifyAll 暂时移除，保持有限超时，观察主线程怎样得到错误并清理后台线程；不要改成无限等待。
5. 在项目找一个线程或异步任务，列出所有者、返回值、异常、取消、超时和资源清理位置。
6. 给“Servlet 请求中做两个独立 HTTP 查询”设计有界并发方案，说明为何线程池上限不能脱离下游连接池容量。
7. 阅读一个线程转储，分别找出 RUNNABLE、监视器 BLOCKED 和事件 WAITING，并结合栈信息说明原因。

继续阅读：[锁与共享状态](./locks.md) · [线程池](./thread-pools.md)。
