# 并发容器：线程共享数据怎样读写

并发容器解决的是**同一个 Java 进程内，多个线程同时访问集合结构时的数据竞争**。Servlet 应用中，Tomcat 工作线程会并行处理请求；Spring `@Service` 默认通常是单例对象，所以不同请求可能同时调用同一个 Service。

并发容器不会自动提供数据库事务、跨 JVM 锁、持久化、过期淘汰或业务唯一性。选择时要分清：容器内部结构安全、一次 Map 方法原子、多个业务步骤作为整体原子、多个服务实例共同正确，是不同范围。

本页以 Java 11 API 为基线。基础集合结构见[基础容器专题](./containers.md)，线程与锁见[并发总览](./concurrency.md)，线程池的容量和拒绝策略见[线程池专题](./thread-pools.md)。

## 线程共享容器的风险从哪里来

线程同时执行 `ArrayList.add()` 或 `HashMap.put()` 时，操作可能交错，内部大小、数组、桶或链结构可能被破坏。即使容器内部某个方法安全，“先检查，再修改”的两步逻辑也可能被另一个线程插入。

```text
线程 A：if (!map.containsKey(key))
线程 B：if (!map.containsKey(key))
线程 A：map.put(key, valueA)
线程 B：map.put(key, valueB)

两次检查都通过；最终值由最后一次写覆盖，业务上可能丢失一方结果。
```

Spring Service 里的共享字段尤其要留心：方法局部变量通常属于当前调用栈；单例对象的实例字段由所有请求线程共同看到。即使字段类型是 `final List<T>`，也只是不能重新给引用赋值，仍可被 `add/remove` 修改。

```java
import java.util.ArrayList;
import java.util.List;

// 对比单例 Service 中共享可变字段与方法局部集合。
public class ServiceScopeDemo {

    // Java 程序入口；真实 Spring 项目中这两个对象可能由容器长期复用。
    public static void main(String[] args) {
        // 创建错误示例，模拟一个被多个请求共同调用的 Service。
        UnsafeReportService unsafe = new UnsafeReportService();
        // 连续查询会发现上次返回的共享结果也会被后续请求清空。
        List<String> firstResult = unsafe.query("status=OPEN");
        // 后一次调用修改同一实例字段，firstResult 也引用该列表。
        unsafe.query("status=CLOSED");
        // 输出可能已变为 CLOSED，说明结果引用被另一次请求改写。
        System.out.println(firstResult);

        // 安全示例每次使用局部集合，不把请求状态存在单例字段中。
        StatelessReportService safe = new StatelessReportService();
        // 第一次结果不会被下一次查询清空或覆盖。
        List<String> stableResult = safe.query("status=OPEN");
        // 另一请求使用自己的局部列表。
        safe.query("status=CLOSED");
        // 仍输出第一次请求的数据。
        System.out.println(stableResult);
    }

    // 错误示意：Spring 默认单例 Service 的实例字段会被多个请求线程共享。
    private static final class UnsafeReportService {
        // 每个请求都会把自己的参数写入同一个列表，可能互相覆盖或混入结果。
        private final List<String> currentFilters = new ArrayList<>();

        // Servlet 容器可同时让多个请求线程进入该方法。
        private List<String> query(String filter) {
            // 清理共享状态会影响正在执行的另一个请求。
            currentFilters.clear();
            // 当前请求写入共享列表。
            currentFilters.add(filter);
            // 返回的仍是共享可变列表，后续调用还会改变它。
            return currentFilters;
        }
    }

    // 安全方向：每次调用使用自己的局部结果。
    private static final class StatelessReportService {
        // 方法的参数和局部集合属于本次调用，不放进单例实例字段。
        private List<String> query(String filter) {
            // 每个请求创建自己的结果对象，不会和其他请求争用集合结构。
            List<String> result = new ArrayList<>();
            // 这里只把当前请求筛选条件加入本地结果。
            result.add(filter);
            // 返回新列表，调用方后续修改不会影响 Service 内部状态。
            return new ArrayList<>(result);
        }
    }
}
```

真实业务通常由 Mapper 返回新建的 List，再转换成 VO；关键是确认集合或元素对象是否被缓存后跨请求共享。局部变量只解决**容器引用不共享**，如果里面放的是共享可变对象，仍要另外保护对象状态。

## 并发容器总览

| 容器 | 线程安全方式/迭代语义 | 适合 | 主要限制 |
|---|---|---|---|
| `ConcurrentHashMap` | 并发读写；迭代弱一致 | 按 key 共享进程内状态/索引 | 不支持 null；多 key 事务和外部副作用不原子 |
| `CopyOnWriteArrayList` | 写入复制底层数组；迭代器读快照 | 读多写少的小型监听器列表 | 每次写复制 O(n) 数组，内存和 GC 成本高 |
| `ConcurrentLinkedQueue` | 非阻塞并发队列；弱一致迭代 | 线程间传递轻量、可自行控流的元素 | 无界、不等待容量；高压下可能无限占堆 |
| `BlockingQueue` | 空/满时可等待；线程间传递任务 | 固定 worker 的生产者—消费者 | 容量满策略、关闭和中断必须设计 |
| `ConcurrentSkipListMap/Set` | 排序并发结构；弱一致迭代 | 并发维护排序 key、范围查询 | 操作通常 O(log n)，比哈希容器成本高 |
| `Collections.synchronizedXxx` | 每个方法通过同一把互斥锁串行化 | 简单兼容、低竞争旧代码 | 遍历和复合操作还需外部同步 |

## ConcurrentHashMap：并发 key-value 索引

### 能解决什么，不能解决什么

`ConcurrentHashMap<K,V>` 允许多个线程安全地按 key 读取和更新，适合本进程内共享的状态表、handler 索引或轻量计数映射。它通过更细粒度的协作减少“所有操作都争一把全局锁”的等待；Java 8+ 实现结合 CAS、桶级同步和冲突树等机制，具体内部实现属于 JDK 细节，不应被业务代码依赖。

| 场景 | 推荐写法 | 原因 |
|---|---|---|
| 单 key 设置默认值 | `putIfAbsent` | 条目不存在时原子地设置 |
| 基于当前值做单 key 变换 | `compute` / `computeIfPresent` | 避免分开的 `get`、计算、`put` 竞争 |
| 初始化每个 key 的轻量对象 | `computeIfAbsent` | 并发时同一 key 仅按原子映射操作建立条目 |
| 多线程计数 | `computeIfAbsent(key, ... LongAdder).increment()` | 适合高竞争统计；不是精确余额 |
| 多 key 同时更新并要求全成全败 | 不能仅靠 ConcurrentHashMap | 应使用锁、数据库事务或业务状态机明确范围 |

ConcurrentHashMap 不接受 null key/value。普通 `get()` 返回 null 可表示未命中，不会与“命中一个 null value”混淆。它的聚合 `size()`、遍历和视图在并发更新时不构成整张表的事务快照。

### 错误的复合操作与正确的按 key 原子操作

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// 演示单 key 的原子创建和更新；不访问外部系统。
public class ConcurrentMapDemo {

    // 用线程安全 Map 保存每个租户已经处理的请求数。
    private static final ConcurrentMap<String, Integer> COUNTS = new ConcurrentHashMap<>();

    // 对一个 key 原子加一并返回新值。
    private static int increment(String tenantId) {
        // compute 在该 key 的原子映射操作中读取旧值并计算新值。
        return COUNTS.compute(tenantId, (key, oldValue) -> {
            // 首次出现从 1 开始，后续请求加一。
            return oldValue == null ? 1 : oldValue + 1;
        });
    }

    // Java 程序入口。
    public static void main(String[] args) {
        // 同 key 连续增加两次，得到 2。
        increment("tenant-A");
        // 再次增加时读取当前映射值并原子替换。
        int current = increment("tenant-A");
        // 输出当前示例进程中的计数值。
        System.out.println(current);
    }
}
```

如果只有计数目的且写冲突很高，可将 value 改为 `LongAdder`。但 `LongAdder.sum()` 在并发写入时是统计观测，不是用于扣款、限额校验或唯一序号的原子快照。

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

// 演示适合监控统计的高并发计数，不用于账户余额等精确不变量。
public class ConcurrentFrequencyDemo {

    // 每个事件名对应一个分段累加器，减少多线程争用同一个 long。
    private static final ConcurrentMap<String, LongAdder> FREQUENCIES = new ConcurrentHashMap<>();

    // 收到一次事件时增加对应计数。
    private static void record(String eventName) {
        // 原子建立计数器后，在计数器本身执行并发累加。
        FREQUENCIES.computeIfAbsent(eventName, key -> new LongAdder()).increment();
    }

    // Java 程序入口。
    public static void main(String[] args) {
        // 记录一次看板查询事件。
        record("dashboard-query");
        // 输出观测计数；并发写入时该值不是业务事务快照。
        System.out.println(FREQUENCIES.get("dashboard-query").sum());
    }
}
```

### compute 函数里不要执行慢副作用

映射函数应短小、确定、只计算本地值。不要在 `computeIfAbsent` 里调用远程 HTTP、等待锁、访问慢数据库或递归更新同一个 Map；映射计算期间相关更新可能等待，而且失败时远程副作用也不会被 Map 自动回滚。对于缓存加载，更稳妥的做法通常是把“缓存的 Future/任务状态”与加载流程设计清楚，并使用专用缓存库处理过期、并发加载和容量淘汰。

ConcurrentHashMap 的迭代器是弱一致的：允许和更新并发迭代，不会像普通 HashMap 那样因并发结构改动就必然快速失败；迭代结果可能包含部分并发新值，也可能看不到刚发生的更新。若要导出一致快照，需在业务层协调更新或复制并接受复制时点的语义。

## CopyOnWriteArrayList：读多写少的快照列表

写操作会复制底层数组，再让新数组成为当前版本；已经创建的迭代器继续读取旧数组快照。读取和遍历不需要长时间锁住写入者，适合配置规模小、读远多于更新的监听器列表或路由快照。它的快照迭代器不支持 `Iterator.remove()`，迭代中新增/删除也不会反映到已有快照。

```java
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

// 演示迭代器使用创建时的数组快照。
public class CopyOnWriteDemo {

    // 保存少量事件监听器，运行时读取频繁、配置更新很少。
    private static final List<String> LISTENERS = new CopyOnWriteArrayList<>();

    // Java 程序入口。
    public static void main(String[] args) {
        // 注册最初的监听器。
        LISTENERS.add("listener-A");

        // 创建迭代器时固定当前数组快照。
        for (String listener : LISTENERS) {
            // 处理当前快照里的监听器。
            System.out.println(listener);
            // 后续写入会复制数组，不会让当前迭代器改读新数组。
            LISTENERS.add("listener-B");
        }

        // 新的遍历可看到更新后的数组。
        System.out.println(LISTENERS);
    }
}
```

该示例只为说明快照边界；循环中重复 `add` 会持续复制数组，真实代码不应这样写。它不适合高频变更、大列表或需要“迭代中必须立即看到新增项”的场景。元素对象本身仍可能可变，快照只固定数组引用，不复制对象。

## ConcurrentLinkedQueue：无锁等待，但没有背压

`ConcurrentLinkedQueue` 是多线程可并发 `offer/poll` 的非阻塞 FIFO 队列。生产者没有消费者时仍能持续加元素，没有容量上限；它不会因为队列满而等待或拒绝。因此它适合短暂、可自行控制流量的进程内队列，不适合作为默认的后台任务缓冲区。

高压时无界队列会持续堆积对象，最终可能引发长时间延迟或 OOM。`size()` 在并发变化中需要遍历且只是观察值，不适合作为“先看 size 是否小于上限，再 offer”的容量控制；检查与写入会竞争，无法形成原子上限。

## BlockingQueue：有界缓冲与生产者—消费者

`BlockingQueue` 用来把任务从生产线程交给消费线程，并可在队列为空/满时协作等待。`put/take` 等待；`offer/poll` 立即返回成功/失败；带 timeout 的 `offer/poll` 有界等待。队列阻塞或拒绝会对上游形成**背压**：当消费者慢时，生产端停止继续无限制造任务。

| 实现 | 容量/顺序 | 适用场景 | 注意事项 |
|---|---|---|---|
| `ArrayBlockingQueue` | 固定数组容量，FIFO；可选择公平锁 | 需要明确容量上限、行为容易估算 | 单个队列锁保护操作；公平性会有吞吐成本 |
| `LinkedBlockingQueue` | 链节点 FIFO；构造器可设容量 | 生产/消费需要相对独立的锁路径 | 无参构造容量非常大，服务中应显式设上限 |
| `PriorityBlockingQueue` | 按优先级堆排序；实际上无界 | 并发任务按优先级取出 | `put` 不会因为容量满而等待；要另行限流，迭代器不保证排序 |
| `SynchronousQueue` | 容量为 0，直接交接 | 提交方与 worker 必须配对交接 | 不能保存排队任务；没有接收线程时会阻塞/拒绝 |
| `DelayQueue` | 无界，按延迟到期时间取出 | 进程内延迟任务、短时超时索引 | 任务不会早于到期时间被 `take`；重启会丢失所有内存任务 |

### BlockingQueue 的等待方法怎样选择

| 行为 | 入队 | 出队 | 常见用途 |
|---|---|---|---|
| 失败立即返回 | `offer(e)`，满时返回 `false` | `poll()`，空时返回 `null` | HTTP 请求线程不应被长时间挂住；可立即返回繁忙 |
| 等待空间/元素 | `put(e)`，满时等待 | `take()`，空时等待 | 专用生产者/消费者工作线程 |
| 有限时间等待 | `offer(e, time, unit)` | `poll(time, unit)` | 允许短暂排队，再转成明确超时/拒绝 |
| 满/空时抛异常 | `add(e)` | `remove()` / `element()` | 需要用异常表达非法状态时；调用前仍要设计异常处理 |

阻塞方法可抛 `InterruptedException`。线程池关闭、请求取消或服务停机时，任务应保留中断并释放资源；不要捕获后忽略中断标志，否则关闭流程可能一直等不到任务退出。`put/take` 并不意味着“永远安全地等待”，必须设计线程生命周期和服务停止信号。

线程池同样使用这些队列决定任务饱和行为；线程数、队列容量、拒绝策略和下游容量要一起设计。参见[线程池专题](./thread-pools.md)，不要因为用了 BlockingQueue 就误以为任务具有持久性。

### 完整生产者—消费者示例

下面代码用一个有界队列在同一 JVM 中传递任务。队列容量是 2；示例由主线程生产，由单一工作线程消费。使用结束标记让消费者正常退出；生产端使用 `put` 体现背压，并保留中断语义。

~~~java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 可独立运行：演示有界队列、生产者背压、消费者退出和线程池清理。
public class BoundedQueueDemo {

    // 字符串队列不允许 null，因此使用约定的特殊值作为结束信号。
    private static final String STOP = "__STOP__";

    // Java 程序入口。
    public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
        // 队列最多保存两个等待任务，避免突发时无限占用堆内存。
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(2);
        // 单一 worker 逐个处理队列任务。
        ExecutorService workers = Executors.newSingleThreadExecutor();

        // 提交消费者任务，并保存 Future 以观察完成或异常。
        Future<?> consumer = workers.submit(() -> {
            try {
                // 持续等待任务；队列为空时 worker 阻塞等待，不空转占 CPU。
                while (true) {
                    // take 会移除队首任务；队列为空时可被中断。
                    String task = queue.take();
                    // 遇到结束标记就退出消费循环。
                    if (STOP.equals(task)) {
                        break;
                    }
                    // 真实服务会在此处调用任务处理器并记录成功/失败状态。
                    System.out.println("处理任务：" + task);
                }
            } catch (InterruptedException exception) {
                // 恢复中断标记，使上层知道该 worker 收到了停止请求。
                Thread.currentThread().interrupt();
            }
        });

        try {
            // put 在容量已满时等待消费者腾出空间，对生产端形成背压。
            queue.put("export-1");
            // 继续提交第二个任务。
            queue.put("export-2");
            // 再提交一个任务；如果队列已满，主线程会等 worker 消费后再继续。
            queue.put("export-3");
            // 发送结束信号；worker 处理完此前任务后会读取到它并退出。
            queue.put(STOP);

            // 等待消费者结束，带超时避免主线程无限期等待错误的退出逻辑。
            consumer.get(5, TimeUnit.SECONDS);
        } finally {
            // 不再接受新任务并让线程池正常结束。
            workers.shutdown();
            // 限时等待线程池退出。
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                // 超时后请求中断；任务代码仍必须协作响应中断。
                workers.shutdownNow();
            }
        }
    }
}
~~~

`put` 让提交线程等待，适合能够阻塞的生产线程；HTTP 请求线程直接 `put` 可能把 Web 线程池也堵住。请求入口通常用有界等待的 `offer(task, timeout, unit)`，队列满时将拒绝转换成明确的“繁忙/稍后重试”，或者先把任务写入持久化任务表。结束标记还需要考虑多消费者时每个 worker 都要收到信号；生产和消费都可能失败时，应以任务状态、取消协议和 shutdown hook 管理生命周期。

## ConcurrentSkipListMap / Set：并发排序与范围查询

`ConcurrentSkipListMap` 使用跳表结构，按 key 的比较顺序维护映射，常见 `get/put/remove` 操作期望平均为 O(log n)。迭代器弱一致，允许并发更新；`subMap/headMap/tailMap` 可以按范围查询。对应的 `ConcurrentSkipListSet` 可维护并发有序集合。它不允许 null key/value；比较器若把不同业务键比较为 0，后续 put 会更新同一个排序项。

它适合需要**同时排序与并发访问**的内存索引，例如按时间戳定位近期状态；如果不需要顺序，`ConcurrentHashMap` 通常更轻。如果排序 key 可变或 Comparator 将不同 key 比成 0，元素仍会被覆盖/合并。并发有序 Map 不提供跨多个 key 的原子事务。

## Vector 和 Hashtable：旧同步容器的边界

`Vector` 和 `Hashtable` 的许多单方法操作带同步，属于早期集合 API。同步方法不代表一段业务操作整体原子：`if (!table.containsKey(k)) table.put(k, v)` 仍可能交错；遍历期间的锁定规则也需要明确。新代码通常选择 `ArrayList/HashMap` 配合明确的线程模型，或按并发访问模式选 `CopyOnWriteArrayList/ConcurrentHashMap`，避免误以为“类名里看起来老牌”就自动满足所有线程安全要求。

## 同步包装器与迭代边界

`Collections.synchronizedMap(map)` 等包装器让单个方法通过同一把互斥锁执行，适合简单旧代码；它们会把并发调用大体串行化。按文档遍历时还要在包装对象上外部同步：

```java
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// 演示 synchronizedMap 的遍历协议；所有访问必须通过同一个包装器。
public class SynchronizedMapDemo {

    // 用包装器保护普通 HashMap 的单个方法访问。
    private static final Map<String, String> VALUES =
            Collections.synchronizedMap(new HashMap<>());

    // Java 程序入口。
    public static void main(String[] args) {
        // 通过包装器写入数据。
        VALUES.put("theme", "dark");

        // 迭代时要在包装器对象上同步，防止另一个线程结构性修改。
        synchronized (VALUES) {
            // 锁保护整个遍历过程，而不仅是 iterator.next() 单次调用。
            for (Map.Entry<String, String> entry : VALUES.entrySet()) {
                // 在临界区内读取当前 key-value。
                System.out.println(entry.getKey() + "=" + entry.getValue());
            }
        }
    }
}
```

不要一处使用原始 HashMap、另一处使用包装器；其他线程绕过包装器就绕开了锁。即使每次 `get` 和 `put` 分别同步，“检查后插入”仍需在同一把锁内覆盖整组逻辑。多数新代码根据读写模型选择并发容器或显式状态机更清楚。

## 如何选择并发容器

| 需求 | 起点 | 必须补充的设计 |
|---|---|---|
| 按 key 并发读写，顺序不重要 | `ConcurrentHashMap` | 复合操作是否用 `compute/merge`；对象 value 是否可变 |
| 高并发监控计数 | `ConcurrentHashMap<K, LongAdder>` | 不能将 `sum` 用作支付额度或严格快照 |
| 小列表，大量遍历，极少更新 | `CopyOnWriteArrayList` | 写入复制成本、快照是否可接受 |
| 多线程并发交接，但可自行限流 | `ConcurrentLinkedQueue` | 要另行设计队列容量和拒绝规则 |
| 生产/消费需要等待和明确容量 | 有界 `BlockingQueue` | 队列满时阻塞、限时拒绝还是持久化 |
| 并发读写且必须按 key 排序/范围查询 | `ConcurrentSkipListMap` | comparator、O(log n) 成本和弱一致遍历 |
| 简单兼容共享 Map，竞争很低 | 同步包装器 | 遍历外部同步、复合操作范围 |

所有这些都是 JVM 内存结构。多台应用实例各自拥有独立 Map/队列；若业务要求全局唯一、跨重启恢复或可靠任务分发，需要数据库唯一约束、Redis 的明确数据模型或具备所需交付语义的消息代理。

## 常见故障与定位

- **普通 HashMap 在服务里偶发异常：** 找单例 Bean、静态字段和缓存引用，确认是否多请求线程共享并修改。
- **换成 ConcurrentHashMap 仍然丢计数：** 检查是否把 `get + 1 + put` 拆开；使用 `merge/compute` 或高竞争计数器。
- **ConcurrentHashMap 的 value 状态仍然竞争：** 容器保护 key-value 关联，不自动同步 value 对象内部多个字段。
- **队列长度长期增长：** 消费能力低于生产；检查下游 SQL/HTTP、并发上限、重试放大和队列容量。
- **任务突然消失：** 进程重启会清空内存队列；确认是否应使用持久化任务表或消息代理。
- **线程池繁忙但 CPU 很低：** 线程可能在等数据库连接、HTTP 响应、锁或有界队列；查看等待时间和下游池容量。
- **遍历结果似乎漏了刚写入项：** 并发 Map 的弱一致迭代或 CopyOnWrite 的旧快照可能是预期语义；业务要完整快照时需另行协调。
- **无界队列导致堆内存增长：** `ConcurrentLinkedQueue`、无界 `LinkedBlockingQueue` 和 `PriorityBlockingQueue` 不会自动形成背压。

## 练习

1. 在单例 Service 中添加一个可变 `ArrayList`，设计两个并发请求交错时会产生的问题，再改成方法局部结果。
2. 用 `containsKey` 后 `put` 统计请求，说明两个线程如何丢更新；改用 `compute`。
3. 比较 `CopyOnWriteArrayList` 与普通 ArrayList 的一次遍历/写入成本，并说明什么时候快照语义有用。
4. 把示例的 ArrayBlockingQueue 容量改为 1，观察生产线程何时阻塞；再改为 timed `offer`，设计队列满时的 HTTP 响应。
5. 为可靠导出任务设计“入队后应用立刻重启”的行为，说明为什么要把任务先持久化。

下一步：[线程池、Future 与异步编排](./thread-pools.md)、[基础容器](./containers.md)、[锁与并发工具](./locks.md)。
