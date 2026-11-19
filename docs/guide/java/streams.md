# Java Stream（Java 11）

Stream 是对一串数据进行声明式处理的 API。它解决的是“把集合逐条筛选、转换、分组、汇总时，循环和临时变量越写越多”的问题。

前端可以把它粗略类比成 Array 的 filter/map/reduce，但 Java Stream 有自己的生命周期和类型规则：中间操作是惰性的，终端操作才触发计算；一个 Stream 只能消费一次；并行处理时顺序和副作用都需要特别考虑。

本章以 Java 11 为范围，因此会介绍 Java 9 加入的 takeWhile、dropWhile、Collectors.filtering、flatMapping 等方法，但不使用 Java 12 才加入的 Collectors.teeing，也不使用 Java 16 的 Stream.toList()。

## Stream 解决什么问题

假设有一组订单，要统计已支付订单的收入、按商品分类汇总销量、找出高额订单。传统循环通常需要多个可变变量和嵌套分支。Stream 把处理拆成一段可读的流水线：

~~~text
数据源 -> 过滤 -> 转换/展开 -> 排序/截取 -> 汇总或收集
~~~

每一步表达“要做什么”，让读代码的人更容易看清业务意图。Stream 不会自动让算法更快，也不会取代数据库查询；数据很多时，应先判断过滤、聚合是否应在 SQL 或数据源端执行。

## 三类操作与流水线生命周期

| 操作种类 | 示例 | 解决的问题 | 什么时候执行 |
|---|---|---|---|
| 数据源 | Collection.stream、Arrays.stream、Stream.of | 决定从哪里读数据 | 创建流水线时 |
| 中间操作 | filter、map、sorted | 描述如何筛选和变换元素 | 惰性；直到有终端操作才开始 |
| 终端操作 | collect、count、forEach、reduce | 产生最终结果或副作用 | 触发整条流水线执行 |

一条 Stream 流水线通常由一个数据源、零到多个中间操作和一个终端操作组成。中间操作返回新的 Stream 描述；它们不会原地修改集合。

终端操作执行后，该 Stream 已被消费。若还要处理同一集合，应从集合重新创建 Stream，或者提前收集成可复用的数据结构。

## 常用中间操作

下表中的中间操作返回新的 Stream，通常不会立即遍历数据。

| 操作 | 解决的问题 | 何时使用 | 业务场景 |
|---|---|---|---|
| filter(predicate) | 去掉不满足条件的元素 | 按业务条件缩小数据集 | 只保留已支付订单、有效用户、未删除记录 |
| map(function) | 把一种元素映射成另一种元素 | 一对一转换 | 从 Order 提取金额，或从 Entity 转成 VO |
| mapToInt / mapToLong / mapToDouble | 转为基本数值流，避免装箱并使用数值专用操作 | 后续需要 sum、average、summaryStatistics 等 | 汇总订单金额、统计 ID、计算时长 |
| flatMap(function) | 把每个元素映射为多个元素，再摊平成一条流 | 一对多关系或嵌套集合 | 把订单展开成商品明细，把部门展开成员 |
| flatMapToInt / flatMapToLong / flatMapToDouble | 展开并生成基本数值流 | 一对多展开后要做数值汇总 | 展开所有订单行的数量后求总销量 |
| distinct() | 按 equals/hashCode 去重 | 业务上要唯一值，且相等定义正确 | 统计涉及的不同商品类别 |
| sorted() | 按自然顺序排序 | 元素实现 Comparable 且自然顺序符合业务语义 | 对数字、字符串或日期做默认排序 |
| sorted(comparator) | 按明确业务规则排序 | 需要多字段、降序或自定义顺序 | 订单按金额从高到低，再按时间排序 |
| limit(n) | 只取前 n 个 | Top N、控制展示数量 | 首页展示前 10 条告警 |
| skip(n) | 跳过前 n 个 | 处理小数据的偏移分页 | 跳过前几条记录；大数据分页应交给数据库 |
| takeWhile(predicate) | 从开头保留连续满足条件的元素，首次失败后停止 | 输入已有序，且想取满足前缀的部分 | 已按时间倒序排列的事件中，取连续的近期记录 |
| dropWhile(predicate) | 跳过开头连续满足条件的元素，首次失败后保留后续全部元素 | 输入已有序，想跳过一段前缀 | 跳过日志开头的初始化事件 |
| peek(action) | 在元素经过流水线时观察它 | 临时调试且不改变元素 | 检查过滤前后数量或查看映射结果 |
| sequential() | 将后续处理切换为顺序模式 | 之前切成 parallel，但发现顺序更合适 | 处理线程不安全或开销很小的流水线 |
| parallel() | 请求并行执行后续流水线 | 大量数据、计算独立、测量证明确有收益 | 独立计算大量数值指标 |
| unordered() | 表示下游不需要 encounter order | 允许丢弃顺序约束以帮助并行操作 | 并行去重或无序聚合，须确认业务可接受 |

### filter：筛选记录

filter 接收返回 boolean 的条件。它只保留条件为 true 的元素。多个 filter 可表达多个同时成立的约束，读起来通常比一个巨型条件更容易定位规则。

如果条件是“任意一个规则成立”，可以在 lambda 中写 OR，但应给复杂条件起有意义的方法名，或者先拆成 Predicate，避免流水线难以阅读。注意 null：调用元素 getter 前要先判断对象不为 null，或在数据边界统一清理无效值。

### map 与 mapToX：转换记录

map 表示一进一出，例如 Order 转金额、Entity 转 VO。它适合纯转换，不应顺便执行数据库写入、发消息或更新共享字段。

如果后面要求和或平均值，mapToInt、mapToLong、mapToDouble 会得到 IntStream、LongStream、DoubleStream。它们提供 sum、average、summaryStatistics 等操作，也避免部分数值装箱开销。

### flatMap：展开嵌套集合

当 `Order` 中有 `List<OrderLine>` 时，`map(Order::getLines)` 会得到“订单流中的商品行流”，即 `Stream<List<OrderLine>>`。`flatMap(Order::getLinesAsStream)` 会把各订单的商品行摊平成 `Stream<OrderLine>`。如果内部集合可能是 null，应先规范成空集合，避免 flatMap 回调返回 null。

### 排序、limit 和 skip 的成本

sorted 通常要先看到全部输入才能确定完整顺序，因此它会缓存元素，成本可能是 O(n log n) 时间和 O(n) 额外空间。sorted().limit(10) 在一般有序排序场景下仍可能需要处理大部分数据；真正的 Top N 算法或数据库 ORDER BY + LIMIT 可能更合适。

skip().limit() 是内存偏移分页，偏移越大仍需遍历越多元素；数据库数据应优先使用服务端分页。对无限流使用 sorted 或无界收集会永远无法得到结果。

### peek 不等于业务处理

peek 是观察流水线的调试钩子，不保证在没有终端操作时执行。不要用 peek 做重要写操作或状态更新：流水线可能短路、跳过元素、改变执行顺序，或被优化。

## 常用终端操作

终端操作会触发计算，并且通常消费掉 Stream。

| 操作 | 返回类型 | 解决的问题 | 业务场景 / 注意点 |
|---|---|---|---|
| collect(collector) | R | 把元素收集为集合、Map 或统计结果 | 订单列表、分组报表、类别汇总 |
| forEach(action) | void | 对每个元素执行副作用 | 输出日志、调用外部操作；并行时顺序不保证 |
| forEachOrdered(action) | void | 按 encounter order 执行副作用 | 有序流必须按原顺序输出；并行时可能损失并行收益 |
| toArray() / toArray(generator) | 数组 | 转换为数组供旧 API 使用 | 需传入数组构造器以获得具体元素类型 |
| reduce(identity, accumulator) | T | 通过结合操作折叠为一个值 | 求和、拼接少量值；运算必须满足结合性 |
| reduce(accumulator) | `Optional<T>` | 无默认值地折叠非空流 | 找最大值可直接用 max，避免不必要的自定义 reduce |
| collect(supplier, accumulator, combiner) | R | 手动定义可变容器的并行收集规则 | 自定义收集结构；通常优先用标准 Collector |
| count() | long | 统计元素个数 | 统计符合条件的订单数量 |
| min / max(comparator) | `Optional<T>` | 找最小/最大元素 | 最低价格、最近日期；比较规则要明确 |
| anyMatch(predicate) | boolean | 判断是否至少一个符合条件 | 是否存在高风险订单；可能短路 |
| allMatch(predicate) | boolean | 判断是否所有元素符合条件 | 所有行是否都已校验通过；空流返回 true |
| noneMatch(predicate) | boolean | 判断是否没有元素符合条件 | 是否不存在违规数据；空流返回 true |
| findFirst() | `Optional<T>` | 找 encounter order 的第一个元素 | 需要确定的第一条匹配记录 |
| findAny() | `Optional<T>` | 找任意一个元素 | 并行处理中只需任意匹配值时可能更灵活 |
| iterator() / spliterator() | Iterator / Spliterator | 转为命令式迭代或供拆分遍历 | 与旧 API 对接；使用后同样视为消费 |

### Optional 是什么

`findFirst`、`findAny`、`min`、`max` 等可能找不到结果，所以返回 `Optional<T>`，而不是直接返回 null。调用方要决定无结果时怎么办：提供默认值、返回业务错误，或继续表达“可能不存在”。避免直接调用 `get()`，否则空值时会抛 `NoSuchElementException`。

### reduce 的规则

reduce 用一个满足结合律的运算把元素压成一个结果。并行时数据可能分块运算再合并，因此运算应满足结合性；identity 必须是该运算的单位元。例如加法单位元为 0，乘法单位元为 1。

浮点加法受精度和结合次序影响，不保证并行结果与顺序结果逐位相同。金额通常用 BigDecimal 或最小货币单位整数，不能依赖 double 的精确十进制行为。将列表收集到新集合时用 collect，不要用 reduce 改造可变容器。

## Collectors 分类与业务用法

Collectors 是 Collector 工厂。它把 Stream 元素收集到容器、Map 或统计值中。Java 11 常用方法如下。

### 一、基本收集器

| Collector | 解决的问题 | 示例场景 |
|---|---|---|
| toList() | 收集到 List | 返回符合条件的 VO 列表 |
| toSet() | 收集并去重 | 不重复的权限码、类别 |
| toCollection(supplier) | 指定集合类型 | 需要 TreeSet 排序或 LinkedHashSet 保序 |
| joining() | 拼接字符串 | 生成逗号分隔的编码或说明文本 |

默认 toList() 不保证 List 的具体实现类和可变性契约；若需要特定集合，使用 toCollection(ArrayList::new) 等明确表达。

### 二、Map 与键冲突处理

| Collector | 解决的问题 | 示例场景 |
|---|---|---|
| toMap(keyMapper, valueMapper) | 按元素字段建立 Map | 根据用户 ID 建索引 |
| toMap(keyMapper, valueMapper, mergeFunction) | 处理重复 key | 同一类别合计、重复键保留较新值 |
| toMap(keyMapper, valueMapper, mergeFunction, mapSupplier) | 同时指定冲突策略和 Map 实现 | 生成有序 Map 或并发 Map |
| toConcurrentMap(...) | 并行收集为并发 Map | 多线程独立计算且结果不要求有序 |

toMap 遇到重复 key 默认抛 IllegalStateException。要明确冲突是非法数据、求和、保留首值还是保留末值，不能通过随意使用 (oldValue, newValue) -> newValue 掩盖数据问题。

### 三、分组与分区

| Collector | 解决的问题 | 示例场景 |
|---|---|---|
| groupingBy(classifier) | 按键分成多个组 | 按商品类别分组销售额 |
| groupingBy(classifier, downstream) | 分组后再统计/映射/收集 | 按状态统计数量，按类别求总价 |
| groupingBy(classifier, mapFactory, downstream) | 指定分组 Map 类型 | 保持分类插入顺序 |
| groupingByConcurrent(...) | 并行分组到并发 Map | 大型无序数据的并行分组，需测性能 |
| partitioningBy(predicate) | 按 true/false 二分 | 合格/不合格、过期/未过期 |
| partitioningBy(predicate, downstream) | 二分后对子组继续统计 | 分别统计有效和无效记录数 |

partitioningBy 的 key 是 Boolean，且会产生 true 与 false 两组。若业务要按多个状态分类，用 groupingBy。

### 四、下游转换与筛选

下游 Collector 允许在每组内部继续操作，避免先物化所有分组列表再循环。

| Collector | 解决的问题 | 示例场景 |
|---|---|---|
| mapping(mapper, downstream) | 分组后只收集映射字段 | 每个部门收集员工姓名 |
| filtering(predicate, downstream) | 分组后过滤组内元素 | 保留每个类别里销量为正的商品 |
| flatMapping(mapper, downstream) | 分组后展开嵌套流 | 每个订单客户下收集所有商品名 |
| collectingAndThen(downstream, finisher) | 收集完成后再加工一次 | 转不可变视图、格式化统计对象 |
| teeing(first, second, merger) | 同一输入同时进入两个 Collector 后合并 | Java 12 起可用；Java 11 不包含，勿用在 Java 11 项目 |

Java 11 的 Collectors 有 filtering 和 flatMapping，常见于 groupingBy 的下游 Collector。不能把 Java 9 的 Stream.takeWhile 和 Collectors.filtering 与 Java 8 API 混为一谈。

### 五、数值统计

| Collector | 解决的问题 | 示例场景 |
|---|---|---|
| counting() | 对组内元素计数 | 每个状态的订单数 |
| summingInt/Long/Double(mapper) | 对映射后的值求和 | 每个类别的件数或收入 |
| averagingInt/Long/Double(mapper) | 求映射值平均数 | 平均订单金额 |
| summarizingInt/Long/Double(mapper) | 一次得到 count、sum、min、average、max | 运营统计摘要 |
| minBy/maxBy(comparator) | 每组取极值元素 | 每个科室最贵项目 |
| reducing(identity, mapper, op) | 将映射值按运算归约 | 特殊自定义汇总，优先考虑标准 summing 等 |

分组后统计示例可用 groupingBy(Category::getName, summingInt(Category::getCount))，避免建立每组元素列表后再循环求和。操作需保持语义清晰；涉及金额时优先使用 BigDecimal 组合收集，或先转换为整数分值。

## 惰性、一次消费和短路

### 惰性求值

filter、map 等中间操作只描述步骤，不会立即运行。以下只创建流水线但没有终端操作，不会打印任何内容：

~~~java
// 创建一个集合来源的 Stream。
Stream<String> names = users.stream();
// 声明过滤步骤；此时不会立即遍历 users。
Stream<String> activeNames = names.filter(User::isActive);
// 只有调用 count、collect 等终端操作后，过滤才真正执行。
long count = activeNames.count();
~~~

惰性能减少无用工作。例如 findFirst 可能在找到第一个匹配元素后就停止后续遍历；anyMatch、allMatch 和 limit 也可能短路。

### 一次消费

Stream 类似一次性流水线，不是集合的容器。终端操作后不能重复使用同一个 Stream，否则抛 IllegalStateException。需要两种结果时，可以从源集合各创建一次 Stream，或一次收集为合适的中间结果。

### 短路与无限流

anyMatch、findFirst、limit 等短路操作可能不读取全部元素。对 Stream.generate 或 iterate 创建的无限流，必须在终端前限制数量，或使用可短路终端操作；sorted、count 等需要完整输入的操作可能无法结束。

## 顺序与并行

### Encounter order

部分数据源有 encounter order，例如 List 通常按列表次序遍历；HashSet 通常没有稳定迭代顺序。filter 和 map 通常保留原有顺序，sorted 会建立新顺序，unordered 会显式表示后续不要求保持 encounter order。

- forEach 不承诺并行执行顺序。
- forEachOrdered 对有序流按顺序消费，但可能削弱并行收益。
- findFirst 返回逻辑上的第一个；findAny 可返回任一匹配项。
- toSet 不保证结果遍历顺序；需保序时明确收集到 LinkedHashSet。

### 并行什么时候有用

parallelStream 或 parallel 可能在元素很多、计算量较重、每个元素相互独立、数据能有效拆分且合并成本较低时带来收益。小集合、简单 getter、网络/数据库 I/O、共享状态、顺序敏感任务常常不适合并行。

Java parallel stream 通常使用共享的 ForkJoinPool。不要假设它自动使用专门的业务线程池，也不要在并行流中执行阻塞 I/O 后期望系统自然扩展。

从顺序改并行前，应在代表性数据量下测量耗时和资源使用；还要检查顺序语义、异常传播和线程上下文（例如安全用户上下文）是否正确传递。

## 副作用、线程安全与常见陷阱

### 不要在流水线里修改共享集合

不要在 parallelStream 的 forEach 中向普通 ArrayList 或 HashMap 写入。即使改成线程安全容器，也还要确认复合操作是否原子、执行顺序是否重要。优先使用 collect，让 Collector 负责正确组合各线程的局部结果。

### lambda 尽量写成纯函数

filter 和 map 的回调最好只基于输入计算结果。修改全局变量、递增共享计数器、写数据库或调用远程接口会使流水线难以推理，并且在短路、并行和重试下可能执行次数与顺序不同。

### 并行调用外部副作用可能重复或乱序

不要用 parallelStream 批量发不可重复的付款、通知、状态更新。若业务要求重试或至少一次投递，应单独设计幂等键、并发限制和失败恢复，不能把它藏在 Stream lambda 里。

### 常见问题清单

- Stream 只用一次；需要多次终端操作时从源重新创建。
- 不要因为代码短就默认比循环快；基准测试后再做性能结论。
- 空集合的 allMatch 返回 true、noneMatch 返回 true、anyMatch 返回 false，这是逻辑定义的边界结果。
- findFirst/min/max 返回 Optional；先规定无结果的业务行为。
- sorted 可能需要缓存全部输入；大数据排序常应下推数据库。
- skip/limit 是内存分页，不能替代数据库分页。
- toMap 重复 key 会抛异常；主动定义冲突规则。
- distinct 依赖 equals/hashCode；实体若按对象身份比较，可能去不了业务重复。
- BigDecimal 不要使用 double 构造；金额计算保留精度规则。
- filter 不能把 null 自动消掉；先做 null 检查或规范输入。
- peek 仅用于调试观察，不承担重要业务逻辑。
- reduce 的二元运算应满足结合性，identity 应为单位元。
- Stream 不会自动把数据库查询变成流式读取；JDBC 游标生命周期和事务要单独管理。

## 完整可运行示例：订单和商品销售报表

下面的程序使用 Java 11 语法。它筛选已支付订单，计算总收入，按类别统计销售额和销量，分区高低金额订单，并产生 Top 2 订单列表。每条非空 Java 源码行都附有中文说明。

~~~java
import java.math.BigDecimal; // 使用十进制定点数表示金额，避免 double 的二进制精度误差。
import java.util.Arrays; // 创建示例订单和订单明细列表。
import java.util.List; // 声明只读意义上的订单列表接口。
import java.util.Map; // 接收分组与分区后的键值结果。
import java.util.Optional; // 表示查询可能没有结果的 Top N 元素。
import java.util.stream.Collectors; // 提供分组、求和、计数和收集等 Collector。
import java.util.stream.Stream; // 订单明细通过流展开时需要 Stream 类型。

public class StreamBusinessDemo { // 定义可直接用 java 命令运行的示例入口类。
    public static void main(String[] args) { // Java 程序从 main 方法开始执行。
        List<Order> orders = Arrays.asList( // 创建三个用于报表示范的订单。
            new Order("A-100", OrderStatus.PAID, Arrays.asList( // 构造第一笔已支付订单。
                new OrderLine("耗材", "导管", 2, new BigDecimal("120.50")), // 第一单包含两件导管。
                new OrderLine("设备", "监护仪", 1, new BigDecimal("860.00")) // 第一单包含一台监护仪。
            )), // 结束第一笔订单的明细和对象构造。
            new Order("A-101", OrderStatus.PENDING, Arrays.asList( // 构造一笔尚未支付的订单。
                new OrderLine("耗材", "注射器", 10, new BigDecimal("3.20")) // 待支付订单包含十支注射器。
            )), // 结束待支付订单的明细和对象构造。
            new Order("A-102", OrderStatus.PAID, Arrays.asList( // 构造第二笔已支付订单。
                new OrderLine("耗材", "导管", 1, new BigDecimal("120.50")) // 第二单再购买一件导管。
            )) // 结束第二笔订单的明细和对象构造。
        ); // 完成订单数据源的创建。
        List<Order> paidOrders = orders.stream() // 从订单集合创建一条新流水线。
            .filter(order -> order.getStatus() == OrderStatus.PAID) // 只保留已经支付的订单以计算确认收入。
            .collect(Collectors.toList()); // 终端收集让前面的惰性过滤真正执行并生成新列表。
        BigDecimal totalRevenue = paidOrders.stream() // 为总收入创建独立 Stream，避免复用已消费的流。
            .flatMap(order -> order.getLines().stream()) // 把订单中的明细列表摊平为一条商品明细流。
            .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))) // 计算每行的金额等于单价乘数量。
            .reduce(BigDecimal.ZERO, BigDecimal::add); // 以零为加法单位元，将所有明细金额累加。
        Map<String, BigDecimal> revenueByCategory = paidOrders.stream() // 从已支付订单开始生成类别销售额报表。
            .flatMap(order -> order.getLines().stream()) // 将一对多的订单明细展开以便按明细类别统计。
            .collect(Collectors.groupingBy( // 按商品类别建立一个类别到汇总值的 Map。
                OrderLine::getCategory, // 选择商品明细的类别作为分组键。
                Collectors.mapping( // 在每个类别组内先把明细映射成金额再汇总。
                    line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())), // 将每条明细转换为行金额。
                    Collectors.reducing(BigDecimal.ZERO, BigDecimal::add) // 对同组金额使用 BigDecimal 安全累加。
                ) // 结束组内映射和归约 Collector。
            )); // 执行收集并得到每个商品类别的销售额。
        Map<String, Long> unitsByCategory = paidOrders.stream() // 再创建一条独立流水线统计商品件数。
            .flatMap(order -> order.getLines().stream()) // 展开每笔已支付订单的全部商品明细。
            .collect(Collectors.groupingBy( // 按商品类别分组并为每组产生一个统计结果。
                OrderLine::getCategory, // 使用明细类别作为 Map 的 key。
                Collectors.summingLong(OrderLine::getQuantity) // 对组内商品数量求和并返回 long。
            )); // 完成各类别销量统计。
        Map<Boolean, List<Order>> ordersByAmount = paidOrders.stream() // 对已付款订单做高低金额二分。
            .collect(Collectors.partitioningBy(order -> order.total().compareTo(new BigDecimal("500.00")) >= 0)); // true 代表金额至少五百元，false 代表较低金额。
        List<Order> topTwo = paidOrders.stream() // 生成用于查找 Top 2 的独立流水线。
            .sorted((left, right) -> right.total().compareTo(left.total())) // 按订单总额从高到低排序。
            .limit(2) // 排序后只保留金额最高的两笔订单。
            .collect(Collectors.toList()); // 收集 Top 2 订单供后续展示。
        Optional<Order> largestOrder = paidOrders.stream() // 为最大订单查找再创建一条流水线。
            .max((left, right) -> left.total().compareTo(right.total())); // 按订单总额比较并在没有订单时返回空 Optional。
        System.out.println("已支付订单数：" + paidOrders.size()); // 输出筛选后的订单数量。
        System.out.println("确认收入：" + totalRevenue); // 输出所有已支付订单的商品金额总和。
        System.out.println("按类别销售额：" + revenueByCategory); // 输出每个类别的收入分组。
        System.out.println("按类别销量：" + unitsByCategory); // 输出每个类别售出的件数。
        System.out.println("高额订单数：" + ordersByAmount.get(true).size()); // 输出达到五百元阈值的订单数量。
        System.out.println("低额订单数：" + ordersByAmount.get(false).size()); // 输出低于五百元阈值的订单数量。
        System.out.println("Top 2 订单：" + topTwo); // 输出排序后金额最高的两笔订单。
        System.out.println("最大订单：" + largestOrder.map(Order::getId).orElse("无订单")); // 安全处理最大订单可能不存在的情况。
    } // 结束示例程序的入口方法。
} // 结束订单销售报表示例类。

enum OrderStatus { // 声明订单状态，避免使用含义不清的字符串常量。
    PENDING, // 表示订单已提交但尚未完成支付。
    PAID // 表示订单已支付，可计入确认收入。
} // 结束订单状态枚举。

final class Order { // 定义订单对象并限制它被继承修改。
    private final String id; // 保存订单业务编号。
    private final OrderStatus status; // 保存订单当前状态。
    private final List<OrderLine> lines; // 保存订单包含的商品明细。

    Order(String id, OrderStatus status, List<OrderLine> lines) { // 通过构造器一次性提供订单所需字段。
        this.id = id; // 保存业务编号。
        this.status = status; // 保存订单状态。
        this.lines = lines; // 保存商品明细集合。
    } // 结束订单构造器。

    String getId() { return id; } // 提供订单编号的只读访问方法。
    OrderStatus getStatus() { return status; } // 提供订单状态的只读访问方法。
    List<OrderLine> getLines() { return lines; } // 提供订单明细的只读访问方法。

    BigDecimal total() { // 计算当前订单全部明细的金额总和。
        return lines.stream() // 从订单明细创建一个局部 Stream。
            .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))) // 把每个商品明细转换成行金额。
            .reduce(BigDecimal.ZERO, BigDecimal::add); // 使用 BigDecimal 累加并以零表示空订单总额。
    } // 结束订单金额计算方法。

    @Override // 声明此方法覆盖 Object 的文本表示方法。
    public String toString() { // 提供适合报表示例输出的订单摘要。
        return id + "=" + total(); // 用订单编号和订单金额构成易读摘要。
    } // 结束订单摘要方法。
} // 结束订单类。

final class OrderLine { // 定义一条订单商品明细。
    private final String category; // 保存商品类别，用于按类别分组。
    private final String product; // 保存商品名称，便于识别具体物品。
    private final int quantity; // 保存购买数量。
    private final BigDecimal unitPrice; // 保存商品单价。

    OrderLine(String category, String product, int quantity, BigDecimal unitPrice) { // 创建带类别、名称、数量和单价的明细。
        this.category = category; // 保存商品类别。
        this.product = product; // 保存商品名称。
        this.quantity = quantity; // 保存购买数量。
        this.unitPrice = unitPrice; // 保存单价。
    } // 结束明细构造器。

    String getCategory() { return category; } // 提供分组统计所需的类别。
    String getProduct() { return product; } // 提供展示商品名称所需的读取方法。
    int getQuantity() { return quantity; } // 提供销量统计所需的数量。
    BigDecimal getUnitPrice() { return unitPrice; } // 提供金额计算所需的单价。
} // 结束订单明细类。
~~~

此示例体现了多条独立流水线的好处：每项统计都重新从可复用的订单列表创建 Stream。它也适合数据已在内存且规模有限的业务报表；真实的大型订单表通常应由数据库按状态、日期和类别过滤/分组，再由 Java 组装 API 响应。

## 输出结果参考

示例运行后会得到约如下结果（Map 的遍历显示顺序不保证）：

~~~text
已支付订单数：2
确认收入：1221.50
按类别销售额：{设备=860.00, 耗材=361.50}
按类别销量：{设备=1, 耗材=3}
高额订单数：1
低额订单数：1
Top 2 订单：[A-100=1101.00, A-102=120.50]
最大订单：A-100
~~~

## 什么时候用 Stream，什么时候不用

适合 Stream 的情况：

- 对内存集合进行清楚的筛选、转换、分组或汇总。
- 一个流水线表达式可以让业务步骤更易读。
- 每个元素的计算相互独立，且不会修改共享状态。

考虑普通循环的情况：

- 算法有多阶段状态、复杂 break/continue 或需精确控制副作用顺序。
- 调试时必须逐步改变状态，Stream 会让控制流更难看清。
- 多层 flatMap/groupingBy 让代码读起来比循环更绕。
- 操作主要是数据库、HTTP 或文件 I/O，想靠 parallelStream 提速。
- 大数据筛选和分页应推到 SQL 查询或服务端游标，而不是先全量加载再 Stream 处理。

最终标准是业务意图是否更容易理解、性能与错误行为是否可预测，而不是代码行数更少。
