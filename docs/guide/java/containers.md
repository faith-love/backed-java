# Java 基础容器：List、Set、Map、Queue 怎样选

容器（也叫集合）是在程序运行时保存一组对象的数据结构。选择容器时要回答四个业务问题：**是否允许重复、是否必须保留顺序、主要如何查找/插入/删除、数据是否会被多个线程共同修改**。容器只保存 JVM 进程内的数据；服务重启后要恢复、或要被多个服务实例共享的数据，应存入数据库、Redis 或消息系统。

本页以项目使用的 Java 11 为 API 基线。Stream 的过滤和汇总见 [Stream 专题](./streams.md)；多线程共享容器见[并发容器专题](./concurrent-containers.md)。

## 集合框架层级与选择维度

```text
Iterable
└── Collection
    ├── List：有位置顺序，可重复
    ├── Set：按相等规则去重
    └── Queue / Deque：按队列或双端队列规则取元素

Map：key 映射到 value；它不属于 Collection
```

| 问题 | 需要看什么 | 例子 |
|---|---|---|
| 重复是否有意义？ | `List` 保留重复；`Set` 表示唯一性 | 表格明细可以重复，权限码常需去重 |
| API 是否承诺顺序？ | 插入顺序、排序顺序、优先级顺序是不同契约 | 用户选择顺序、按编码排序、最近截止任务优先 |
| 主要查找方式？ | 下标、哈希 key、比较器范围、队首/队尾 | 页面行号、按 ID 查对象、按日期范围查 |
| 会不会跨线程共享？ | 普通集合不是并发容器；线程安全要看访问协议 | Spring 单例 Bean 被多个请求线程同时调用 |
| 容量能否增长？ | 动态数组、树、无界队列或有界队列 | 请求突增时无界队列可能耗尽堆内存 |

下面复杂度是常见实现的量级，不是所有输入下的时延承诺。哈希性能取决于散列分布，树性能取决于比较成本，数组搬移量取决于插入位置；实际性能还受对象分配、CPU 缓存和数据大小影响。

## 数组和动态数组：ArrayList

数组 `T[]` 长度固定，按下标访问通常是 O(1)，但长度不能增减。`ArrayList<T>` 用可扩容数组保存元素：索引读取 O(1)，尾部追加平均摊还 O(1)，中间插入或删除需要搬移后续元素，通常 O(n)。容量不足时会创建更大的数组并复制旧元素，因此一次扩容可能是 O(n)，多次尾部追加的平均成本才是摊还 O(1)。具体扩容比例属于 JDK 实现细节，不要把某一版本的增长公式当作业务契约。

| 操作 | 常见量级 | 为什么 |
|---|---:|---|
| `get(index)` / `set(index, value)` | O(1) | 直接按数组下标访问 |
| 尾部 `add(value)` | 摊还 O(1) | 偶尔扩容复制，整体平均计算 |
| 中间 `add(index, value)` / `remove(index)` | O(n) | 需要移动一段元素 |
| `contains(value)` | O(n) | 逐个比较元素 |

默认选择场景：数据库 Mapper 返回的一页结果、前端表格行、顺序明确的配置项、频繁遍历而较少在头部插入的列表。已知数据量大致范围时，可用 `new ArrayList<>(expectedSize)` 减少扩容，但不要为不可信的超大请求一次分配巨量容量。

```java
import java.util.ArrayList;
import java.util.List;

// 演示 ArrayList 的顺序、重复值和按下标读取。
public class ArrayListDemo {

    // Java 程序入口。
    public static void main(String[] args) {
        // 预估本页最多约 20 行，提前设置容量可减少少量扩容复制。
        List<String> selectedCharts = new ArrayList<>(20);

        // List 按添加顺序保存元素。
        selectedCharts.add("sales");
        // List 允许重复值；是否重复要由业务规则决定。
        selectedCharts.add("sales");
        // 再添加一个不同的图表编码。
        selectedCharts.add("inventory");

        // 按索引访问适合表格的行号语义。
        System.out.println(selectedCharts.get(0));
        // 输出 sales；第二项 sales 仍然保留。
        System.out.println(selectedCharts);
    }
}
```

## 链表：LinkedList 什么时候才合适

`LinkedList` 是双向链表，每个元素节点保存前后节点引用。它实现 `List` 和 `Deque`，首尾插入/删除可在 O(1) 完成；按下标访问、按值查找通常是 O(n)，因为需要从一端逐节点走到目标。节点对象和两个引用有额外内存开销，也较难利用 CPU 缓存局部性。

常见误解是“链表插入永远很快”。若只有 `add(index, value)` 的 index，而没有对应节点迭代器，仍需先 O(n) 找位置，再 O(1) 改指针；对大多数普通列表，`ArrayList` 往往更省内存、访问更快。需要两端入队/出队时优先比较 `ArrayDeque`，需要线程间阻塞交接时选择 `BlockingQueue`。

## Set：唯一性要由相等规则定义

Set 不以“数据库主键”自动判断重复。`HashSet`/`LinkedHashSet` 使用元素的 `equals()` 和 `hashCode()`；`TreeSet` 使用比较结果，若 `compare(a, b) == 0` 就视为同一个排序键，即使 `equals()` 返回 false。可变对象放入 Set/Map 后再修改参与散列或排序的字段，会使元素难以查找或删除。

| 实现 | 顺序 | 常见操作量级 | 适用场景 |
|---|---|---:|---|
| `HashSet` | 不保证遍历顺序 | 平均 O(1) | 只关心是否出现过、按值去重 |
| `LinkedHashSet` | 保留插入顺序 | 平均 O(1) | 去重后还要按用户输入顺序返回错误 |
| `TreeSet` | Comparator/自然顺序 | O(log n) | 始终按编码、时间或优先规则排序 |

HashSet 内部主要借助 HashMap 保存元素。哈希桶碰撞后仍需比较对象；Java 8+ 的部分桶在条件满足时会树化来改善极端碰撞，但不应靠实现细节替代正确的 `hashCode()`。Set 里放自定义对象时要实现稳定的相等规则，或先映射到业务唯一键。

## HashMap：从 hash 到桶，再处理碰撞

HashMap 内部是桶数组。写入时先对 key 的 `hashCode()` 做散列处理，再定位桶位；桶为空就放入，发生碰撞时再比较 key。常见情况下查找和更新平均 O(1)，但坏散列、昂贵 `equals()` 或大量扩容都会提高成本。

| 术语 | 含义 | 影响 |
|---|---|---|
| capacity | 桶数组长度 | 越大越减少碰撞但占用更多内存；通常按 2 的幂扩展 |
| load factor | 允许元素数相对桶容量的阈值，默认常见为 `0.75` | 达到阈值会扩容并重新分布元素 |
| collision | 不同 key 落到同一桶 | 需要链表/树结构进一步区分 key |
| resize | 创建新桶数组并重新安排条目 | 可能产生一次较明显的 CPU 和内存成本 |

Java 8+ 在特定碰撞条件下可将长桶转换为树结构，改善极端散列的最坏情况；树化阈值、最小容量等属于 JDK 实现细节。更实用的做法是提供稳定且分布良好的 `hashCode()`，估算合理容量，并避免把不断变化的字段作为 key。

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

// 演示 value 对象以稳定业务 ID 作为 Map key。
public class StableKeyDemo {

    // 具有稳定 ID 的示例键类型。
    private static final class UserKey {
        // 参与相等比较和哈希计算的字段在构造后不再变化。
        private final String id;

        // 创建用户 key。
        private UserKey(String id) {
            // 保存稳定业务标识。
            this.id = id;
        }

        // 两个 key 是否代表同一业务用户。
        @Override
        public boolean equals(Object other) {
            // 先处理同一引用。
            if (this == other) {
                return true;
            }
            // 不同类型对象不相等。
            if (!(other instanceof UserKey)) {
                return false;
            }
            // 按稳定 ID 比较，而不是按可变昵称比较。
            UserKey that = (UserKey) other;
            return Objects.equals(id, that.id);
        }

        // 相等 key 必须有相同 hashCode。
        @Override
        public int hashCode() {
            // 使用与 equals 相同的业务字段计算散列。
            return Objects.hash(id);
        }
    }

    // Java 程序入口。
    public static void main(String[] args) {
        // Map 根据 key 的 equals/hashCode 识别映射。
        Map<UserKey, String> users = new HashMap<>();
        // 插入第一个 key。
        users.put(new UserKey("U-100"), "小林");
        // 新建的等价 key 能找到之前的 value。
        System.out.println(users.get(new UserKey("U-100")));
    }
}
```

## Map：通过唯一 key 建立索引

Map 把 key 映射到 value，适合按稳定编码直接定位对象，例如 `chartCode -> ChartConfig`。常见实现如下：

| 实现 | 主要特性 | 查找/更新常见量级 | 典型用途 |
|---|---|---:|---|
| `HashMap` | 不保证遍历顺序，允许一个 null key 与 null value | 平均 O(1) | 普通 ID 到对象的内存索引 |
| `LinkedHashMap` | 维护插入顺序；也可配置访问顺序 | 平均 O(1) | 稳定输出顺序、小型 LRU |
| `TreeMap` | 按 key 的比较器排序 | O(log n) | 范围查询、按时间或编码排序 |
| `EnumMap` | key 必须是单一 enum 类型，内部按枚举顺序组织 | 通常 O(1) | 固定状态枚举到统计值的映射 |

HashMap 根据 key 的 hash 定位桶，再使用 `equals()` 确认 key。key 的 `equals/hashCode` 必须一致且在作为 key 期间保持稳定；例如把 `id` 放进 key 后再修改 `id`，原条目可能留在旧桶，`get`/`remove` 会失败。Map 的 key 唯一是按 Map 自己的相等规则定义，不代表数据库约束。

### LinkedHashMap 与访问顺序缓存

`LinkedHashMap` 可以按插入顺序遍历；构造时指定 `accessOrder=true` 后，访问过的元素移动到末尾。它适合做单线程、小容量的近似 LRU 示例，但**不是并发缓存**，也没有按时间自动过期、容量权重或跨实例同步。生产缓存通常交给 Caffeine/Redis 等专用组件。

```java
import java.util.LinkedHashMap;
import java.util.Map;

// 演示单线程、有容量上限的最近最少使用淘汰；不用于多线程生产缓存。
public class SmallLruDemo {

    // 使用 LinkedHashMap 实现 access-order 遍历。
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(16, 0.75f, true) {
        // 每次插入后检查是否超出示例容量。
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            // 返回 true 会移除最久未访问的首项。
            return size() > 3;
        }
    };

    // Java 程序入口。
    public static void main(String[] args) {
        // 依次插入三个 key，当前顺序是 a、b、c。
        CACHE.put("a", "A");
        CACHE.put("b", "B");
        CACHE.put("c", "C");

        // 读取 a 会把它变成最近访问项，顺序变成 b、c、a。
        CACHE.get("a");
        // 插入 d 后淘汰最久未访问的 b。
        CACHE.put("d", "D");

        // 观察示例结果；真实项目还需要并发、过期和统计等能力。
        System.out.println(CACHE);
    }
}
```

### TreeMap 与 Comparator

TreeMap 基于红黑树维护 key 的排序，能够用 `firstKey()`、`ceilingKey()`、`subMap()` 等做有序和范围操作，查找/插入/删除通常 O(log n)。Comparator 应满足稳定的一致排序关系；如果 comparator 把两个不同业务 key 比为 0，TreeMap 会把它们当成同一个 key。比较器还应处理 null 或明确禁止 null，避免数据进入集合后才抛异常。

### EnumMap

状态只有固定枚举集合时，EnumMap 比字符串 key 更不容易拼错，遍历按枚举声明顺序。它只接受一个 enum 类型的 key，且不能使用 null key；适合统计订单状态数、枚举到 handler 的映射。枚举值变化要留意 API 序列化契约，前端收到的字符串状态仍需稳定定义。

```java
import java.util.EnumMap;
import java.util.Map;

// 演示用 EnumMap 为有限状态建立统计表。
public class EnumMapDemo {

    // 定义业务中允许的有限状态。
    private enum Status {
        // 尚未处理。
        PENDING,
        // 已成功完成。
        SUCCEEDED,
        // 已失败。
        FAILED
    }

    // Java 程序入口。
    public static void main(String[] args) {
        // EnumMap 要明确 key 的枚举类型，不需要字符串常量拼接。
        Map<Status, Integer> counts = new EnumMap<>(Status.class);

        // 对收到的状态逐条计数；merge 会在原值不存在时写入 1。
        for (Status status : new Status[] {Status.PENDING, Status.SUCCEEDED, Status.PENDING}) {
            // 同一状态计数加一；这里是单线程示例。
            counts.merge(status, 1, Integer::sum);
        }

        // 未出现的状态可在展示时按 0 处理。
        System.out.println(counts.getOrDefault(Status.FAILED, 0));
    }
}
```

## Queue 与 Deque：处理顺序也是业务契约

`Queue` 适合“先进入的先处理”或按优先级调度；`Deque` 可以从头尾两侧添加和移除。推荐理解两类方法：`offer/poll/peek` 以返回值表示失败/空队列，`add/remove/element` 在相应失败条件下会抛异常。调用方应显式处理容量不足和队列为空。

| 实现 | 结构与行为 | 典型场景 | 边界 |
|---|---|---|---|
| `ArrayDeque` | 可扩容循环数组，支持双端操作 | 单线程 FIFO 队列、栈、DFS 迭代 | 不允许 null；非线程安全；无容量上限 |
| `PriorityQueue` | 二叉堆；查看堆顶 O(1)，插入/取出 O(log n) | 最早截止任务、最小/最大值调度 | 只保证堆顶优先，不保证整个迭代有序；非线程安全 |
| `LinkedList` | 链表双端队列 | 确需链表语义的双端操作 | 节点开销大；普通单线程 Deque 通常考虑 ArrayDeque |

PriorityQueue 默认把自然顺序最小的元素放在堆顶；需要最大值可反转 comparator。优先级相同的元素不保证按插入顺序，若业务要求稳定先后，应在比较键中加入递增序号。

## 迭代、排序、可变性与容量

- `HashMap`、`HashSet` 的迭代顺序不是 API 契约；业务响应需要稳定顺序时使用明确有序的实现，或在输出前排序。
- 普通集合在迭代时结构性增删，常会抛 `ConcurrentModificationException`。这通常是快速失败提示，不是线程同步机制。
- `Collections.unmodifiableList(list)` 是只读视图：原 list 改动会反映出来。`new ArrayList<>(list)` 是浅拷贝：结构分离但元素对象仍共享。
- 数组和 ArrayList 保存的是对象引用，不会自动复制元素对象；多个列表可能指向同一个可变 DTO。
- 普通集合大多允许动态增长，没有业务容量上限。来自请求的批量数据要校验最大条数，防止一次请求占用大量 CPU 和内存。
- Java 10 的 `List.copyOf` 返回不可变副本且不允许 null；如果目标 API 是 Java 8，不可直接使用它。

安全删除可用 Iterator 的 `remove()`，或用 `removeIf` 明确条件；不要边增强 for 遍历边直接调用集合的 `remove()`。

## 一页选型决策表

| 需求 | 优先考虑 | 还要确认 |
|---|---|---|
| 保存查询返回顺序，可重复、按下标读 | `ArrayList` | 是否限制最大记录数 |
| 唯一性，不关心顺序 | `HashSet` | `equals/hashCode` 是否正确 |
| 去重后保留用户提交顺序 | `LinkedHashSet` | 重复项要丢弃还是报告给用户 |
| 根据 ID/编码快速找到对象 | `HashMap` | key 是否稳定、允许 null 吗 |
| 按 key 有序并做范围查询 | `TreeMap` | Comparator 相等规则是否符合业务唯一性 |
| 状态枚举到统计/处理器 | `EnumMap` | enum 是否是固定类型 |
| 单线程两端增删 | `ArrayDeque` | 是否实际上要跨线程传任务 |
| 每次取最高/最低优先级任务 | `PriorityQueue` | 并列优先级是否要稳定顺序 |
| 多线程共享这些容器 | 阅读[并发容器专题](./concurrent-containers.md) | 单操作安全、复合业务原子性、容量与持久性 |

## 前端开发者练习

1. 一个表格既要保留服务端顺序又允许重复行，选择什么实现？若按唯一 ID 去重，需要在哪一层定义重复语义？
2. 用户按顺序提交 100 个编码，要报告重复编码且结果保持输入顺序，用什么组合？
3. 从 1,000 个配置中反复按 code 查询，如何构造索引？什么时候应先保留 List，再建立 Map？
4. 将不同订单状态统计成计数表，比较 EnumMap 和 `Map<String, Integer>` 的类型安全与序列化边界。
5. 用 PriorityQueue 按时间处理任务时，如何保证相同时间的任务按提交顺序执行？

下一步：[并发容器：ConcurrentHashMap、BlockingQueue 与快照](./concurrent-containers.md)、[Stream：过滤、转换与汇总](./streams.md)。
