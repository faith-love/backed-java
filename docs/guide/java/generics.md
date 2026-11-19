# Java 泛型：把类型约束带到集合、接口与 API 响应

泛型让类、接口和方法在编译时知道自己处理哪一种类型。它主要解决两类问题：减少强制类型转换和运行时类型错误；让一套通用逻辑可以安全地复用于不同业务对象。

对前端开发者来说，可以先把 `List<ChartVO>` 理解为“元素类型是 ChartVO 的数组”，但 Java 泛型不仅描述数据，还参与方法签名、依赖契约和编译检查。泛型不能替代 HTTP JSON 校验：外部数据进入服务时，仍要由反序列化、字段校验和业务规则共同保证正确性。

## 不用泛型时会遇到什么问题

早期集合把元素当成 `Object` 保存，取出时必须强制转换。调用方传错类型，错误可能直到运行时才暴露。

```java
import java.util.ArrayList;
import java.util.List;

// 演示未使用泛型时，集合内可能混入不同类型的数据。
class RawListDemo {

    // 程序入口，用来对比原始集合和带类型参数的集合。
    public static void main(String[] args) {
        // 原始 List 允许放入任意对象，编译器无法保护调用方。
        List rawValues = new ArrayList();
        // 字符串和整数都能加入，错误的数据形状没有在编译期被拒绝。
        rawValues.add("页面一");
        rawValues.add(42);

        // 取值时需要强制转换，而且第二次取值会抛出 ClassCastException。
        String title = (String) rawValues.get(1);
        // 只有上面的强制转换成功后才会执行打印。
        System.out.println(title);
    }
}
```

**适用场景：** 阅读旧项目、原始框架 API 或看到 `List`、`Map` 没有尖括号时，要意识到它失去了编译期类型保护。新代码通常应写成 `List<String>` 或 `Map<String, ChartVO>`。

## 泛型类与泛型接口

在类型名后声明类型参数，例如 `Box<T>`、`Repository<T, ID>`。`T` 是类型变量，可以按约定替换成 `E`（元素）、`K`（键）、`V`（值）等名字；这些名字只是阅读提示，不会改变语言行为。

```java
// 表示一个保存单个值的通用容器。
final class Box<T> {

    // 保存容器内部的值，字段类型由创建 Box 时提供的 T 决定。
    private final T value;

    // 构造器接收与该容器类型参数一致的值。
    Box(T value) {
        // 保存已经通过编译期类型检查的值。
        this.value = value;
    }

    // 返回与创建时相同类型的值，调用方不需要强制转换。
    T get() {
        // 返回容器中保存的对象。
        return value;
    }
}

// 用实体类型和主键类型参数化 Repository 契约。
interface Repository<T, ID> {

    // 按主键查找对应实体；Optional 的内容类型仍是 T。
    java.util.Optional<T> findById(ID id);

    // 保存一个实体并返回数据库生成或更新后的实体。
    T save(T entity);
}

// 用户实体示例，用来演示 Repository 如何绑定具体类型。
final class UserEntity {

    // 数据库主键在本示例中使用 Long。
    private final Long id;

    // 用户名是普通字符串字段。
    private final String name;

    // 创建不可变用户对象。
    UserEntity(Long id, String name) {
        // 保存用户主键。
        this.id = id;
        // 保存用户姓名。
        this.name = name;
    }

    // 暴露主键供业务读取。
    Long getId() {
        // 返回当前用户主键。
        return id;
    }

    // 暴露姓名供业务读取。
    String getName() {
        // 返回当前用户姓名。
        return name;
    }
}
```

例如 `Repository<UserEntity, Long>` 约定“实体是 UserEntity，主键是 Long”。Spring Data、MyBatis 扩展和自定义 DAO 都会用类似签名把实体类型传给基础能力；项目中的 `BaseMapper<PageEntity>` 也是这个思路。

## 泛型方法

泛型方法在返回类型前声明自己的类型参数。它适合“方法只需要遵守一个类型关系，但不需要把类型保存到对象状态中”的情况。

```java
import java.util.List;

// 提供与具体实体类型无关的通用列表工具。
final class ListUtils {

    // <T> 声明此方法自己的类型变量，参数与返回值使用同一个 T。
    static <T> T firstOrNull(List<T> values) {
        // 空集合没有第一项，返回 null；生产接口也可以选择返回 Optional<T>。
        if (values == null || values.isEmpty()) {
            // 对引用类型返回 null，调用方应按契约处理。
            return null;
        }
        // 返回列表中的第一项，返回类型仍由调用处的 T 决定。
        return values.get(0);
    }
}

// 说明方法类型参数会根据调用参数推导。
class GenericMethodDemo {

    // 示例入口。
    public static void main(String[] args) {
        // 创建字符串列表，编译器推断 T 为 String。
        List<String> names = List.of("订单", "报表");
        // 返回值类型也是 String，不需要强制转换。
        String firstName = ListUtils.firstOrNull(names);

        // 创建整数列表，编译器在另一次调用中推断 T 为 Integer。
        List<Integer> pageNumbers = List.of(1, 2, 3);
        // 返回值类型为 Integer，与字符串调用互不影响。
        Integer firstPage = ListUtils.firstOrNull(pageNumbers);

        // 输出两个推断出的值，便于观察同一方法处理不同类型。
        System.out.println(firstName + ":" + firstPage);
    }
}
```

如果类型变量仅用于一段方法逻辑，优先考虑泛型方法；如果一个对象的多个字段或方法需要共享同一类型关系，再把类型参数放到类或接口上。

## 类型边界：限制允许的类型

默认的 `T` 可以是任意引用类型。`T extends SomeType` 将类型参数限制为某个父类型或接口的实现，让方法可以安全调用该边界承诺的能力。

```java
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 只接收实现 Comparable 的元素，保证元素可以彼此比较。
final class SortSupport {

    // T 必须能和同类型对象比较；返回值仍是调用方的具体 T。
    static <T extends Comparable<T>> List<T> sortedCopy(List<T> source) {
        // 复制一份列表，避免修改调用方传入的原集合。
        List<T> result = new ArrayList<>(source);
        // 仅因为 T 有 Comparable<T> 边界，编译器才允许按自然顺序排序。
        result.sort(null);
        // 返回排序后的副本。
        return result;
    }

    // 多边界示例：类型必须同时是 Number，并实现 Comparable<T>。
    static <T extends Number & Comparable<T>> T max(T left, T right) {
        // 比较能力来自 Comparable 边界，而数值能力来自 Number 边界。
        return left.compareTo(right) >= 0 ? left : right;
    }
}

// 演示边界如何把可用操作限制在编译期。
class BoundedTypeDemo {

    // 示例入口。
    public static void main(String[] args) {
        // BigDecimal 同时继承 Number 并实现 Comparable<BigDecimal>。
        BigDecimal amount = SortSupport.max(new BigDecimal("12.50"), new BigDecimal("9.99"));
        // 打印比较结果，不依赖 double 浮点数。
        System.out.println(amount.toPlainString());
    }
}
```

多边界中，类边界必须写在接口边界前面，而且最多只能有一个类边界。边界应描述算法真正需要的能力，不要为了“限制得更严格”而添加无用条件。

## 通配符与 PECS：解决泛型不变性

即使 `Integer` 是 `Number` 的子类，`List<Integer>` 也不是 `List<Number>` 的子类。如果允许这种赋值，调用方就可能把 `Double` 放进一个原本只应该存 Integer 的列表里。Java 因此让泛型默认保持不变。

```java
import java.util.ArrayList;
import java.util.List;

// 演示“生产者 extends、消费者 super”的集合复制方法。
final class NumberLists {

    // source 只负责提供 Number 子类值；target 负责接收 Number 或其父类值。
    static <T> void copyNumbers(List<? extends T> source, List<? super T> target) {
        // 按 source 的元素类型读取；读到的值至少可以作为 T 使用。
        for (T value : source) {
            // target 的元素类型是 T 或 T 的父类，可以安全接收 T。
            target.add(value);
        }
    }
}

// 说明 extends 和 super 的实际调用结果。
class WildcardDemo {

    // 示例入口。
    public static void main(String[] args) {
        // Integer 列表作为数据生产者。
        List<Integer> source = List.of(10, 20, 30);
        // Number 列表可以安全接收 Integer。
        List<Number> target = new ArrayList<>();

        // 编译器允许把 Integer 逐个复制到 Number 列表。
        NumberLists.copyNumbers(source, target);

        // 输出复制结果。
        System.out.println(target);
    }
}
```

PECS 是 **Producer Extends, Consumer Super** 的记忆规则：

- `? extends T` 适合读取数据的生产者。可以把读出的元素当作 T；通常不能向其中加入具体值，因为真实子类型可能未知。
- `? super T` 适合接收 T 的消费者。可以安全加入 T；读出时只能保证是 `Object`，除非再做类型判断。
- 同时读写、需要精确保持类型时，直接使用 `List<T>`，不要机械地添加通配符。

前端对应场景：通用函数把 `List<ChartVO>` 返回给只读渲染逻辑时可接受 `List<? extends ChartVO>`；若函数负责把 `ChartVO` 加入一个输出容器，可使用 `List<? super ChartVO>`。

## 分页响应：把数据类型从实体传到外层

泛型可以表达嵌套响应结构，例如 `PageResponse<ChartVO>`。这让 Controller、Service 和前端 API 契约都能保留具体的列表元素类型。

```java
import java.util.List;

// 表示通用分页数据结构，T 是当前页每一行的类型。
final class PageResponse<T> {

    // 当前页的数据列表只允许保存 T 类型。
    private final List<T> records;

    // 符合筛选条件的总条数。
    private final long total;

    // 创建分页响应并保存列表和总数。
    PageResponse(List<T> records, long total) {
        // 保存当前页数据。
        this.records = records;
        // 保存过滤后的总数。
        this.total = total;
    }

    // 返回具体类型的当前页数据。
    List<T> getRecords() {
        // List 中的元素类型仍然是 T。
        return records;
    }

    // 返回符合查询条件的总条数。
    long getTotal() {
        // 返回分页总数。
        return total;
    }
}

// 图表返回对象只展示前端需要的字段。
final class ChartVO {

    // 图表编码是前端定位图表的业务标识。
    private final String chartCode;

    // 创建带图表编码的 VO。
    ChartVO(String chartCode) {
        // 保存图表编码。
        this.chartCode = chartCode;
    }

    // 返回图表编码。
    String getChartCode() {
        // 提供只读字段访问。
        return chartCode;
    }
}
```

`PageResponse<ChartVO>` 与 `PageResponse<PageEntity>` 是不同的编译期类型。泛型不能决定 HTTP JSON 字段叫什么、页码从 0 还是 1 开始，也不能阻止服务端把 Entity 序列化给前端；这些仍属于 API 契约、映射和权限设计。

## 类型推断与钻石语法

编译器通常能从赋值目标和方法参数推断类型，因此 `new ArrayList<>()` 的尖括号里可以留空。推断失败或影响可读性时，显式写出类型更容易维护。

```java
import java.util.ArrayList;
import java.util.List;

// 演示通过变量目标类型推断构造器中的泛型参数。
class InferenceDemo {

    // 示例入口。
    public static void main(String[] args) {
        // 左侧声明元素为 String，右侧钻石语法据此推断 ArrayList<String>。
        List<String> names = new ArrayList<>();

        // 加入 String 符合列表类型。
        names.add("仪表盘");

        // 下面这类操作会在编译期报错，因为 Integer 不是 String。
        // names.add(100);

        // 输出列表内容。
        System.out.println(names);
    }
}
```

类型推断只在编译期工作，不会为运行时 JSON 自动恢复嵌套泛型类型。不要把“编译器知道 `List<ChartVO>`”误解为“任意 JSON 都会被自动验证为 ChartVO”。

## 类型擦除：运行时为什么看不到 T

Java 泛型主要通过编译期检查实现。编译后大多数类型参数会被擦除：`List<String>` 与 `List<Integer>` 在运行时属于同一个原始类 `List`。这保持了对旧 Java 字节码的兼容，但也带来若干限制：

- 不能直接写 `new T()`，因为运行时不知道具体构造哪个类。
- 不能写 `T.class` 或 `value instanceof List<String>`，因为类型参数通常已擦除。
- 不能创建 `new T[10]`，数组在运行时需要知道元素类型。
- 重载方法不能只通过不同泛型参数区分，例如 `save(List<String>)` 与 `save(List<Long>)` 会因擦除冲突。

需要构造某个类型时，可显式传入 `Class<T>` 工厂或供应器；需要 JSON 序列化库保留嵌套参数时，应传递 `TypeReference`、`ParameterizedTypeReference` 等类型描述。

## 原始类型、数组与堆污染

原始类型是没有类型参数的旧式用法，例如 `List values`。它会绕开编译期检查并可能产生 unchecked 警告。除非维护兼容旧 API，新代码不要使用原始类型，也不要用强制转换压掉警告。

泛型数组创建同样受擦除限制。可以改用 `List<T>`，或由调用者提供数组工厂。下面的例子用通用列表保存元素，避免创建不安全的 `T[]`。

```java
import java.util.ArrayList;
import java.util.List;

// 用 List<T> 实现可变长度容器，避开泛型数组运行时类型不匹配。
final class TypedBuffer<T> {

    // 列表由具体类型参数约束，添加值时由编译器检查。
    private final List<T> values = new ArrayList<>();

    // 添加一个与 T 相同类型的值。
    void add(T value) {
        // 列表会保留创建 TypedBuffer 时指定的编译期元素类型。
        values.add(value);
    }

    // 按下标返回 T 类型的元素。
    T get(int index) {
        // ArrayList 负责越界检查，方法返回类型由 T 决定。
        return values.get(index);
    }

    // 返回当前元素数量。
    int size() {
        // 将底层列表的长度传给调用方。
        return values.size();
    }
}
```

不要把 `List<T>` 当作运行时输入校验器。原始类型、反射、未检查强制转换和不安全泛型可变参数都可能绕过编译器；一旦产生堆污染，错误会在看似正常的读取位置才暴露。

## JSON 边界：泛型不等于运行时校验

从数据库 Mapper 返回 `List<ChartEntity>` 时，泛型能在 Java 调用链中约束元素类型。HTTP JSON 则在运行时解析：如果只把 `List.class` 传给反序列化器，类型参数已丢失，JSON 对象可能变成 `Map`；Spring/Jackson 通常能从 Controller 声明的 `PageResponse<ChartVO>` 解析到类型信息，但配置、原始类型或自定义反序列化器可能改变结果。

排查数据边界时依次确认：

1. HTTP JSON 的字段名、空值和数字格式是否符合 DTO。
2. Controller 参数是否由 Spring/Jackson 按带泛型的类型描述反序列化。
3. 反序列化成功后是否还有 Jakarta/Bean Validation 或业务校验。
4. 数据库实体、DTO 和响应 VO 是否做了明确映射，是否泄露不应返回的字段。

因此，泛型解决的是“代码内部按哪个类型协作”，DTO 校验解决的是“外部输入是否满足契约”，权限校验解决的是“调用者是否可以操作这些数据”。它们互相补充，不能互相替代。

## 常见误区

- 把 `List<Integer>` 当作 `List<Number>`：泛型默认不变，需要 `? extends Number` 等受控边界。
- 给所有参数都加 `?`：通配符会限制写入；需要读写时通常使用精确的 `T`。
- 认为 `? extends T` 可以向集合加入 T：实际子类型未知，通常只能安全读取。
- 认为泛型能验证 API 输入：JSON 反序列化成功不等于字段通过业务校验。
- 看到 `List<T>` 就认为运行时能取得 T：类型擦除让大多数参数不可直接反射获得。
- 使用原始 `List` 后忽略 unchecked 警告：这会把类型错误推迟到运行时。
- 认为 `List.of(...)` 是可写列表：它返回不可变列表；Java 11 可用，但添加或删除会抛出异常。

## 练习

1. 定义 `ApiResponse<T>`，保存业务 code、message 和 data；用 `ApiResponse<PageResponse<ChartVO>>` 描述分页图表响应。
2. 编写一个只读的数字统计方法，判断参数应使用 `List<? extends Number>` 还是 `List<Number>`，并解释为什么。
3. 编写一个把输入复制到目标集合的方法，使用 PECS 选择 source 和 target 的泛型签名。
4. 用一个原始 `List` 复现堆污染，再改成 `List<String>` 观察编译器如何提前阻止错误。
5. 找一个 Controller 响应类型，沿着 JSON 序列化边界确认泛型、字段校验与权限各自负责什么。

继续学习：[类、对象与类型](./core.md)、[集合框架](./collections.md)、[接口参数与响应契约](../../api/response-contracts.md)。
