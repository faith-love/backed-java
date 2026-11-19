# Java 集合：List、Set、Queue 与 Map

集合用来管理一组运行时数量可变的数据。它回答的不只是“怎么存”，还包括“是否允许重复、是否保留顺序、怎样查找、能否并发访问”。选错容器会导致重复数据、顺序不稳定、查找变慢，甚至并发异常。

本页讲集合类型和数据结构的选择。集合数据的筛选、映射、分组和汇总见 [Stream 专题](./streams.md)；错误与失败流程见[异常专题](./exceptions.md)。

如需按具体实现学习数组扩容、哈希桶、红黑树、复杂度和迭代行为，继续读[基础容器专题](./containers.md)。多线程共享时使用的 `ConcurrentHashMap`、`BlockingQueue`、`CopyOnWriteArrayList` 等见[并发容器专题](./concurrent-containers.md)。

## 集合框架的层级

Java 集合主要分成两条路线：

```text
Iterable
└── Collection
    ├── List：有序、可重复
    ├── Set：不允许重复
    └── Queue：排队处理元素

Map：键到值的映射；它不属于 Collection
```

- `Iterable` 提供增强 `for` 循环所需的遍历能力。
- `Collection` 是一组元素的通用接口，常见操作有 `add`、`remove`、`contains` 和 `size`。
- `Map<K, V>` 管理键值对，按 key 查询 value；Map 不是“装着 Entry 的 Collection”这一继承关系。
- 泛型如 `List<Order>` 告诉编译器元素类型，减少强制类型转换；它不代替 HTTP JSON 的运行时校验。

数组长度固定，适合大小已知、追求简单的序列；集合适合元素数量动态变化的业务数据。基本类型要用包装类放进集合，例如 `List<Integer>`，不能写 `List<int>`。

## List：保留位置和重复项

`List<E>` 按位置访问元素，通常保留插入顺序，并允许相同元素出现多次。常见方法有 `get(index)`、`set(index, value)`、`add(value)`、`remove(index)`、`indexOf(value)`。

**解决的问题：** 保存需要稳定遍历次序、允许重复的序列，例如分页结果、用户选择顺序、图表配置列表、批量任务。

| 实现 | 特点 | 适用场景 | 注意点 |
|---|---|---|---|
| `ArrayList` | 动态数组，按下标读取快，尾部追加通常快 | 绝大多数普通列表、查询结果 | 中间插入/删除需要移动后续元素；默认首选 |
| `LinkedList` | 双向链表，头尾操作方便，也实现 `Deque` | 确实频繁在两端增删的队列 | 按下标查找慢，节点开销较大；不要因“插入快”就默认使用 |
| `CopyOnWriteArrayList` | 写时复制，遍历拿到稳定快照 | 读远多于写、列表较小的监听器集合 | 写操作昂贵，不适合频繁增删和大列表 |

前端场景例子：页面按服务端顺序展示“最近访问项目”，用 `List<Project>`；不能改成普通 Set，否则相同条目可能被去重，位置语义也消失。

## Set：表达唯一性

`Set<E>` 不允许按相等规则重复的元素。对自定义对象，HashSet/LinkedHashSet 主要根据 `equals` 与 `hashCode` 判断；TreeSet 根据排序比较结果判断。

| 实现 | 顺序/查找特点 | 具体场景 | 需要留意 |
|---|---|---|---|
| `HashSet` | 不承诺迭代顺序；常见添加、查找、删除平均为常数时间 | 校验一批 ID 是否重复、权限码去重 | 自定义元素需正确实现 `equals`/`hashCode` |
| `LinkedHashSet` | 按插入顺序迭代 | 去重但保留用户选择顺序 | 比 HashSet 多维护顺序信息 |
| `TreeSet` | 按自然顺序或 Comparator 排序；操作通常为对数复杂度 | 需要有序唯一编码、时间点 | 比较结果为 0 的元素会被视为重复 |

具体场景：批量导入部门 ID 时，先用 Set 检测重复，再报告重复项；若错误反馈要按照用户提交顺序排列，选 `LinkedHashSet`。

## Queue 和 Deque：定义处理顺序

`Queue<E>` 表示待处理元素。常用成对方法是 `offer`/`poll`/`peek`：前者分别表示尝试入队、取出并删除队首、只查看队首。相比 `add`/`remove`/`element`，`offer`/`poll`/`peek` 在容量不足或队列为空时能以返回值表达失败，不必依赖异常。

| 类型/实现 | 规则 | 具体场景 |
|---|---|---|
| `ArrayDeque` | 双端队列，头尾插入/取出；可作 FIFO 队列或栈 | 单线程内的待处理任务、遍历栈；通常优先于旧式 `Stack` |
| `PriorityQueue` | 每次优先取排序优先级最高的元素（默认取最小值） | 定时调度按最早时间取任务、按优先级处理工单 |
| `BlockingQueue` 实现 | 满/空时可以阻塞，常用于生产者—消费者协作 | 有界线程池任务缓冲、后台工作队列 |

普通 `ArrayDeque` 和 `PriorityQueue` 不是线程安全的。跨线程共享时，根据需求选择 `BlockingQueue`、`ConcurrentLinkedQueue` 等并发实现，或使用更高层的线程池/消息系统。进程内队列不能替代需要持久化、跨实例传递的消息队列。

具体场景：后台导出功能把待导出的任务放进有界工作队列。队列满时需要明确拒绝、稍后重试或返回“系统繁忙”，不能无限堆积任务耗尽内存。

## Map：通过 key 查询 value

`Map<K, V>` 适合通过唯一 key 快速找到对应值。常用操作包括 `get`、`getOrDefault`、`put`、`putIfAbsent`、`remove`、`containsKey`、`keySet`、`values` 和 `entrySet`。

| 实现 | 顺序/特性 | 具体场景 |
|---|---|---|
| `HashMap` | 不保证遍历顺序；允许一个 null key 和 null value；非线程安全 | 普通的 ID 到对象索引、配置查找；默认常用 |
| `LinkedHashMap` | 默认按插入顺序迭代，也可配置访问顺序 | 保持 API 输出顺序、小型 LRU 结构 |
| `TreeMap` | key 按自然顺序或 Comparator 排序 | 按日期、编号范围有序遍历 |
| `ConcurrentHashMap` | 面向并发读写；不允许 null key/value | 多线程共享缓存、计数映射；仍要理解复合操作原子性 |
| `EnumMap` | key 是 enum，内部实现紧凑 | 按固定状态枚举累计数量 |

### Map 操作的几个细节

- `get(key)` 返回 `null` 可能表示 key 不存在，也可能表示 key 存在但 value 就是 null。若允许 null value，使用 `containsKey` 区分；多数业务 Map 可约定 value 不为 null。
- 计数时可用 `merge(key, 1, Integer::sum)`，避免先 get、再判断、再 put 的重复代码。
- `putIfAbsent` 只适合单个 key 的简单初始化；线程共享的复杂加载通常用 `computeIfAbsent`，但映射函数不应递归修改同一个 Map。
- 遍历时如果需要 key 和 value，通常遍历 `entrySet()`，避免每个 key 再执行一次 `get`。
- 修改普通 HashMap 时，不要在增强 `for` 遍历过程中直接结构性增删；需要删除可用 Iterator 的 `remove()` 或构造新结果。

具体场景：页面一次取得一批图表后，按 `chartCode` 建立 `Map<String, Chart>`，让后续按编码回填配置时无需反复线性扫描列表。

## 如何选择集合：按业务语义决定

| 需求 | 选择 | 示例 |
|---|---|---|
| 保留次序且允许重复 | `ArrayList` | 服务端分页记录、用户拖拽排序项 |
| 只关心是否出现过 | `HashSet` | 批量请求去重、已处理 ID |
| 去重并保留首次出现顺序 | `LinkedHashSet` | 导入项校验并保持提交顺序 |
| key 到对象的普通索引 | `HashMap` | 图表编码到图表配置 |
| key 到对象且输出按插入顺序 | `LinkedHashMap` | 有顺序要求的响应或配置清单 |
| 优先级调度 | `PriorityQueue` | 最早到期的任务先执行 |
| 多线程共享键值状态 | `ConcurrentHashMap` | 多线程缓存索引；避免把多步业务逻辑拆成非原子操作 |
| 多线程生产/消费任务 | `BlockingQueue` | 工作线程从队列取后台任务 |

不要只按“哪个操作看起来快”选集合；先问业务是否要求顺序、唯一性、并发和容量限制，再考虑数据规模和复杂度。

## 完整场景示例：处理仪表盘批量发布请求

场景：前端一次提交多个页面 ID。后端需要按提交顺序检查重复 ID、确认每个页面存在、按 ID 查找记录，并把需要异步发布的工作按先进先出排队。以下示例用 List、LinkedHashSet、Map 和 Queue 各自表达一种语义。

~~~java
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 可运行示例：用多种集合处理仪表盘批量发布请求。
 * 保存为 CollectionUseCaseDemo.java 后，可用 javac --release 11 编译运行。
 */
public class CollectionUseCaseDemo {

    /**
     * 程序入口：展示重复校验、索引查找和 FIFO 任务处理。
     */
    public static void main(String[] args) {
        // 模拟前端提交的 ID 顺序；List 保留顺序，并允许重复以便服务端检查。
        List<String> requestedIds = Arrays.asList("page-A", "page-B", "page-A", "page-C");

        // 模拟仓库查询结果；真实项目通常由 Mapper/Repository 返回 List。
        List<DashboardPage> databaseRows = Arrays.asList(
                new DashboardPage("page-A", "销售看板"),
                new DashboardPage("page-B", "库存看板"),
                new DashboardPage("page-C", "运营看板")
        );

        // 使用 Map 建索引，按页面 ID 查找时不必每次遍历全部结果。
        Map<String, DashboardPage> pageById = new HashMap<>();
        for (DashboardPage page : databaseRows) {
            // key 使用稳定的页面 ID，value 保存对应实体。
            pageById.put(page.getId(), page);
        }

        // LinkedHashSet 检测重复，同时保留 ID 第一次出现时的顺序。
        Set<String> seenIds = new LinkedHashSet<>();

        // List 收集重复输入，让 API 可以向前端指出有问题的 ID。
        List<String> duplicateIds = new ArrayList<>();

        // ArrayDeque 作为先进先出队列，保存稍后要处理的发布任务。
        Queue<PublishTask> publishQueue = new ArrayDeque<>();

        // 按前端提交顺序逐项校验，保持返回错误与输入顺序一致。
        for (String requestedId : requestedIds) {
            // 第一次添加成功；若已存在，说明请求里出现重复 ID。
            if (!seenIds.add(requestedId)) {
                // 记录重复值，而不是静默丢弃，便于给调用方明确反馈。
                duplicateIds.add(requestedId);
                // 已知重复项后跳过后续查询和任务创建。
                continue;
            }

            // 从 Map 通过 key 查询页面，平均查找效率优于 List 逐项扫描。
            DashboardPage page = pageById.get(requestedId);

            // 没有查到页面时记录错误；真实 API 可收集为字段校验结果。
            if (page == null) {
                System.out.println("页面不存在：" + requestedId);
                // 不为不存在的页面创建发布任务。
                continue;
            }

            // 将有效记录转成待处理任务并加入 FIFO 队列。
            publishQueue.offer(new PublishTask(page.getId(), page.getTitle()));
        }

        // 输出重复值，顺序与前端第一次重复出现的顺序一致。
        System.out.println("重复页面 ID：" + duplicateIds);

        // 按先进先出取任务；poll 在队列为空时返回 null。
        PublishTask task;
        while ((task = publishQueue.poll()) != null) {
            // 真实服务会在这里执行权限校验、状态校验和发布逻辑。
            System.out.println("准备发布：" + task.getId() + " / " + task.getTitle());
        }
    }
}

/**
 * 表示从数据库读取的一条仪表盘页面记录。
 */
final class DashboardPage {

    // 页面 ID 是索引 key。
    private final String id;

    // 页面标题用于演示读取对象属性。
    private final String title;

    /**
     * 创建页面记录。
     *
     * @param id 页面 ID
     * @param title 页面标题
     */
    DashboardPage(String id, String title) {
        // 保存页面 ID。
        this.id = id;
        // 保存页面标题。
        this.title = title;
    }

    /**
     * 返回页面 ID。
     *
     * @return 页面 ID
     */
    String getId() {
        // 返回不可变字段。
        return id;
    }

    /**
     * 返回页面标题。
     *
     * @return 页面标题
     */
    String getTitle() {
        // 返回不可变字段。
        return title;
    }
}

/**
 * 表示一个待发布任务。
 */
final class PublishTask {

    // 任务对应的页面 ID。
    private final String id;

    // 任务对应的页面标题。
    private final String title;

    /**
     * 创建发布任务。
     *
     * @param id 页面 ID
     * @param title 页面标题
     */
    PublishTask(String id, String title) {
        // 保存任务 ID。
        this.id = id;
        // 保存任务标题。
        this.title = title;
    }

    /**
     * 返回任务页面 ID。
     *
     * @return 页面 ID
     */
    String getId() {
        // 返回不可变字段。
        return id;
    }

    /**
     * 返回任务页面标题。
     *
     * @return 页面标题
     */
    String getTitle() {
        // 返回不可变字段。
        return title;
    }
}
~~~

## 遍历、排序和安全删除

```java
// 遍历每一项，适合只读或执行不改变集合结构的处理。
for (String id : ids) {
    System.out.println(id);
}

// 使用 Iterator.remove 安全删除当前遍历到的元素。
Iterator<String> iterator = ids.iterator();
while (iterator.hasNext()) {
    String id = iterator.next();
    if (id.isBlank()) {
        iterator.remove();
    }
}

// 明确比较规则后排序，例如先按状态，再按页面 ID。
pages.sort(Comparator.comparing(Page::getStatus)
        .thenComparing(Page::getId));
```

若项目兼容 Java 8，`String.isBlank()` 不可用；Java 8 可用 `trim().isEmpty()`，或按项目已有工具方法判断。排序比较器要考虑 null，并保证比较规则稳定。删除时不能在增强 `for` 中直接调用集合的 `remove`，否则通常会触发 `ConcurrentModificationException`。

## 不可变集合与只读视图

- `Collections.unmodifiableList(list)` 返回只读**视图**：调用方不能通过这个视图改，但若原始 list 后续改变，视图也会反映变化。
- `new ArrayList<>(list)` 创建浅拷贝：后续增删原列表不影响副本，但元素对象仍是同一批引用。
- Java 9+ 的 `List.of(...)` 等创建不可变集合，不能增删，也不接受 null；若项目目标是 Java 8，不可使用这些 API。
- `final List<T>` 只禁止字段引用重新赋值，不会自动禁止调用 `add`。

需要对外提供稳定快照时，通常先复制再包装；需要元素也不可变时，元素类型本身也应不可变或做深拷贝。

## 并发集合的选择

普通 `ArrayList`、`HashMap`、`HashSet` 不是为多个线程并发修改而设计的。Spring Service 常是单例，若多个请求线程共享同一个可变集合，可能出现数据竞争。

| 类型 | 解决的问题 | 典型情况 | 局限 |
|---|---|---|---|
| `ConcurrentHashMap` | 并发读写 key-value | 多线程共享热点缓存或状态索引 | 单次方法安全不代表“先查再改”等多步业务原子 |
| `CopyOnWriteArrayList` | 大量并发遍历，极少修改 | 事件监听器快照 | 修改时复制整个数组 |
| `BlockingQueue` | 线程间传递任务并协调等待 | 生产者/消费者、线程池 | 要定义容量与满队列策略 |
| `Collections.synchronizedList` | 为旧代码提供简单同步包装 | 小规模兼容场景 | 遍历仍需按文档在外部同步；锁竞争会降低吞吐 |

跨实例、需要重启后不丢失的任务应使用持久队列或数据库任务表，而不是只放在 JVM 内存集合里。

## 常见问题与诊断

- **重复数据没有去掉：** 检查目标类的 `equals`/`hashCode`；或明确按业务字段去重，不要假设 Set 自动知道业务唯一键。
- **输出顺序变化：** 检查使用的 HashMap/HashSet；若顺序是 API 契约，使用明确有序实现并测试。
- **Map 查到 null 后空指针：** 确认 key 是否存在、value 是否允许为空；用 `getOrDefault` 或显式判断。
- **并发修改异常：** 检查遍历时是否直接增删集合，改用 Iterator、安全副本或并发结构。
- **内存持续增长：** 检查静态 List/Map、缓存是否无限写入且没有过期或清理策略。
- **翻页顺序不稳定：** 这是数据库查询排序契约问题；集合只保留返回顺序，无法修复 SQL 没有稳定 `ORDER BY` 的问题。
- **返回数据被意外改动：** 检查是否把内部可变集合引用直接暴露给 Controller 或其他服务。

## 选择练习

1. 页面要按用户拖拽顺序保存十个图表，允许图表类型相同，应选哪种集合？为什么？
2. 导入接口要找出重复编码并按用户提交顺序返回错误，应选什么组合？
3. 服务查出一批页面后，要用 `pageCode` 多次定位页面，如何建立索引？
4. 多线程消费后台任务，队列最多只能缓存 500 个，队列满时应该如何反馈？
5. API 返回的集合不能被后续层意外改动，区分不可修改视图和副本，并说明元素是否仍可变。

下一步：[Stream：过滤、转换与汇总](./streams.md)。
