# 类、对象与类型：从定义到可维护的业务模型

Java 的“类”不是只用来声明字段的模板。一个设计良好的类会把状态、允许的操作和有效性规则放在同一个边界内，使调用者不能随意构造或破坏业务数据。本章从 JavaScript/TypeScript 的经验出发，逐步讲清类、对象、引用、构造器、封装、继承、多态和常用设计技巧。

## 先建立类与对象的直觉

类是编译期定义的类型；对象是程序运行时按类创建出来的一个实例。类决定对象有哪些字段和方法，每个对象则拥有自己的实例字段值。

```text
Dashboard 类（定义）
  字段：id、title、status
  方法：rename、publish

dashboardA 对象（实例）        dashboardB 对象（另一个实例）
  id = "sales"                  id = "operations"
  title = "销售看板"              title = "运营看板"
```

`Dashboard` 是类型，`dashboardA` 是引用变量，`new Dashboard(...)` 才创建对象。变量保存的是对象引用；把一个对象赋给另一个变量通常不会复制对象本身。

| 前端概念 | Java 中相近的概念 | 重要区别 |
|---|---|---|
| TypeScript `interface` | Java `interface` | Java 类需要明确 `implements`，不是只要字段形状相同就自动符合 |
| TypeScript `class` | Java `class` | 都能定义字段、构造过程和方法；Java 访问控制和类型检查更严格 |
| JavaScript 对象字面量 | DTO、普通 Java 对象 | Java 对象的结构由编译期类声明；一般不能临时添加字段 |
| `const obj` | `final` 引用 | 两者都限制变量重新赋值；并不自动冻结对象内部状态 |
| JS 的 `===` | primitive 的 `==` 或对象的 `equals` | Java 对象 `==` 比引用身份，值相等通常用 `equals` |

## 类中有哪些成员

一个类常包含以下成员：

- **字段（field）**保存对象状态，例如页面的标题、状态、图表列表。
- **构造器（constructor）**在创建对象时接收参数并建立初始状态。
- **实例方法**读取或改变某一个对象的状态，例如 `rename`。
- **静态成员**属于类本身，常用于常量、工厂方法和与实例无关的工具。
- **嵌套类型**把只服务于外层类的类型收在一起，例如 `Dashboard.Builder`。

方法签名中的参数类型和返回类型构成调用契约。`private` 字段配合公开方法，让类能够检查调用并保护自身规则。类不是越大越好：如果一个类同时管理 HTTP 请求、数据库、文件导出和权限策略，就承担了太多变化原因。

## 访问修饰符：决定谁可以使用成员

| 修饰符 | 同一个类 | 同一个 package | 子类 | 任意位置 | 常见用途 |
|---|---:|---:|---:|---:|---|
| `private` | 是 | 否 | 否 | 否 | 默认优先选择；保护内部状态和辅助方法 |
| 无修饰符（package-private） | 是 | 是 | 同包子类可访问 | 否 | 只供同一功能包内部协作的类型或方法 |
| `protected` | 是 | 是 | 是 | 否 | 有明确继承扩展点时使用；不要当作“更方便的 public” |
| `public` | 是 | 是 | 是 | 是 | 对外稳定 API、框架入口或确需跨包调用的能力 |

字段通常设为 `private`，只公开业务需要的操作。把字段全部声明为 `public`，会让任意调用方都能写出非法状态；之后想增加校验或改内部实现也会变得困难。

Java 顶层类一般只能是 `public` 或 package-private。一个 `.java` 文件最多有一个 `public` 顶层类，文件名必须和该类名一致；其余顶层类可以是 package-private。较大的项目通常每个公开类单独放一个文件，并用 `package` 和目录表达模块边界。

## 构造器：让对象从一开始就有效

构造器名称与类名相同，没有返回类型。创建对象时通过 `new` 调用构造器。只要类声明了任何构造器，编译器就不会再自动提供无参构造器。

几个实用规则：

1. 用构造器接收创建对象必需的信息，并立即校验。
2. 一个构造器可以用 `this(...)` 委托给同类的另一个构造器；这必须是第一条语句。
3. 子类构造器通过 `super(...)` 调用父类构造器；若没写，编译器尝试插入 `super()`。
4. 当参数很多或同类型参数容易传反时，考虑静态工厂或 Builder，不要无限增加位置参数。
5. 无参构造器有时是 ORM、序列化框架的要求；是否需要以及字段如何赋值，要看项目框架配置。

## 完整示例：封装、有效状态、不可变值与 Builder

下面示例可保存为 `ClassDesignDemo.java`，使用 Java 11 编译运行。它展示一个可变的看板实体如何保护自己的状态，以及一个不可变图表值如何安全地作为字段和集合元素使用。

~~~java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 可运行示例：演示类、对象、构造器、封装、组合、相等性和 Builder。
 * 保存为 ClassDesignDemo.java 后，可使用 javac --release 11 编译。
 */
public class ClassDesignDemo {

    /**
     * 程序入口：创建对象并通过公开业务方法使用对象。
     */
    public static void main(String[] args) {
        // 静态工厂表达“创建一个草稿看板”，比直接暴露构造细节更有语义。
        Dashboard dashboard = Dashboard.createDraft("sales", "销售分析");

        // addChart 会检查参数和重复编码，调用者不能绕过看板自身的规则。
        dashboard.addChart(new Chart("revenue", "收入趋势"));

        // 用 Builder 创建包含多个可选设置的看板，避免长串位置参数。
        Dashboard operations = Dashboard.builder("operations", "运营总览")
                // 设置可选状态；Builder 会在 build 时再次校验组合状态。
                .status(DashboardStatus.PUBLISHED)
                // 链式添加图表配置。
                .addChart(new Chart("orders", "订单数量"))
                // 完成配置并创建正式对象。
                .build();

        // 通过 getter 读取对象状态；类没有公开可直接写入的字段。
        System.out.println(dashboard.getTitle() + "：" + dashboard.getCharts());
        System.out.println(operations.getTitle() + "：" + operations.getStatus());

        // 同一个 code 的 Chart 被定义为相同值，因此 equals 返回 true。
        Chart first = new Chart("revenue", "收入趋势");
        Chart second = new Chart("revenue", "另一份展示标题");
        System.out.println("图表编码是否相同：" + first.equals(second));

        // 返回集合是不可修改的副本，外部代码不能绕过 addChart 修改内部集合。
        List<Chart> safeCopy = dashboard.getCharts();
        System.out.println("安全读取的图表数量：" + safeCopy.size());
    }
}

/**
 * 枚举固定的状态集合，避免用任意字符串表示状态。
 */
enum DashboardStatus {
    // 新建对象的默认状态。
    DRAFT,
    // 已发布状态。
    PUBLISHED
}

/**
 * 不可变的图表值对象：创建后编码和标题都不能改变。
 */
final class Chart {

    // 图表编码决定这个值对象的业务身份。
    private final String code;

    // 图表标题是展示内容，不参与本例定义的身份判断。
    private final String title;

    /**
     * 创建一份有效的图表值。
     *
     * @param code 图表唯一编码
     * @param title 图表展示标题
     */
    public Chart(String code, String title) {
        // 在对象边界拒绝 null，避免错误在很深的调用处才暴露。
        this.code = requireText(code, "图表编码");
        this.title = requireText(title, "图表标题");
    }

    /**
     * 提供图表编码的只读访问。
     *
     * @return 图表编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 提供图表标题的只读访问。
     *
     * @return 图表标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 按业务身份比较图表值；本例定义 code 相同即为同一个图表值。
     *
     * @param other 待比较对象
     * @return 类型和编码都相同则返回 true
     */
    @Override
    public boolean equals(Object other) {
        // 同一对象必然相等，可以快速返回。
        if (this == other) {
            return true;
        }

        // null 或不同运行时类型不属于同一种图表值。
        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        // 通过安全类型转换读取对方编码，再按值比较。
        Chart chart = (Chart) other;
        return code.equals(chart.code);
    }

    /**
     * equals 使用的字段也必须参与 hashCode，才能满足集合契约。
     *
     * @return 根据图表编码计算的哈希值
     */
    @Override
    public int hashCode() {
        // 与 equals 一样，仅使用 code 表达本例的业务身份。
        return Objects.hash(code);
    }

    /**
     * 返回适合日志和调试的信息，不要在这里包含密码或 Token。
     *
     * @return 图表的简短文本描述
     */
    @Override
    public String toString() {
        // 用可读文本帮助调试，不把对象地址当作业务信息。
        return "Chart{code='" + code + "', title='" + title + "'}";
    }

    /**
     * 校验字符串字段必须非空且有实际内容。
     *
     * @param value 待检查文本
     * @param fieldName 字段的中文名称
     * @return 去掉首尾空白后的文本
     */
    private static String requireText(String value, String fieldName) {
        // Objects.requireNonNull 会在 value 为 null 时抛出明确异常。
        String trimmed = Objects.requireNonNull(value, fieldName + "不能为空").trim();

        // 空字符串同样不是合法的业务值。
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空字符串");
        }

        // 返回已标准化的值，确保对象内部状态一致。
        return trimmed;
    }
}

/**
 * 看板实体：身份固定，但标题和状态可以通过受控方法变化。
 */
final class Dashboard {

    // 所有看板标题共用同一个上限，常量属于类而不是某个对象。
    private static final int MAX_TITLE_LENGTH = 40;

    // 看板身份在创建后不能被替换。
    private final String id;

    // 标题允许变更，但必须通过 rename 校验。
    private String title;

    // 状态允许变化，但变化必须符合业务规则。
    private DashboardStatus status;

    // final 只保证引用不被重新赋值；集合内容仍可能改变，所以字段保持 private。
    private final List<Chart> charts = new ArrayList<>();

    /**
     * 创建草稿看板的简化构造器，演示构造器委托。
     *
     * @param id 看板唯一 ID
     * @param title 看板标题
     */
    public Dashboard(String id, String title) {
        // this(...) 必须是构造器的第一条语句，避免重复初始化逻辑。
        this(id, title, DashboardStatus.DRAFT);
    }

    /**
     * 创建指定状态的看板。
     *
     * @param id 看板唯一 ID
     * @param title 看板标题
     * @param status 初始状态
     */
    public Dashboard(String id, String title, DashboardStatus status) {
        // 校验并保存不可变身份。
        this.id = requireText(id, "看板 ID");

        // 将标题校验集中在唯一方法中，构造和改名使用相同规则。
        this.title = normalizeTitle(title);

        // 枚举参数也不允许为空，避免对象落入未定义状态。
        this.status = Objects.requireNonNull(status, "看板状态不能为空");
    }

    /**
     * 有语义的静态工厂：创建草稿状态的看板。
     *
     * @param id 看板 ID
     * @param title 看板标题
     * @return 新的草稿看板
     */
    public static Dashboard createDraft(String id, String title) {
        // 工厂方法在内部选择合适构造器，调用方无需重复写默认状态。
        return new Dashboard(id, title, DashboardStatus.DRAFT);
    }

    /**
     * 创建 Builder；当对象有多个可选配置时，链式配置比位置参数清楚。
     *
     * @param id 看板 ID
     * @param title 看板标题
     * @return 新的 Builder
     */
    public static Builder builder(String id, String title) {
        // Builder 是静态嵌套类，不需要先创建 Dashboard 外部对象。
        return new Builder(id, title);
    }

    /**
     * 返回看板 ID。
     *
     * @return 看板 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 返回当前标题。
     *
     * @return 看板标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 返回当前发布状态。
     *
     * @return 看板状态
     */
    public DashboardStatus getStatus() {
        return status;
    }

    /**
     * 返回图表集合的不可修改副本，避免调用者改动内部状态。
     *
     * @return 图表列表快照
     */
    public List<Chart> getCharts() {
        // 先复制再包装，避免返回内部集合或只读视图却仍被内部更新影响。
        return Collections.unmodifiableList(new ArrayList<>(charts));
    }

    /**
     * 通过业务操作修改标题，而不是暴露可任意赋值的 setter。
     *
     * @param newTitle 新标题
     */
    public void rename(String newTitle) {
        // 重新校验，保证对象每个时刻都满足标题规则。
        this.title = normalizeTitle(newTitle);
    }

    /**
     * 添加图表并阻止相同编码重复出现。
     *
     * @param chart 要添加的图表
     */
    public void addChart(Chart chart) {
        // 不允许集合中出现 null，否则后续按编码读取会产生空指针。
        Chart candidate = Objects.requireNonNull(chart, "图表不能为空");

        // 用业务编码判断重复，而不是依赖调用方是否传入同一个对象实例。
        boolean duplicate = charts.stream()
                .anyMatch(existing -> existing.getCode().equals(candidate.getCode()));

        // 重复编码意味着看板配置冲突，因此明确拒绝。
        if (duplicate) {
            throw new IllegalArgumentException("图表编码重复：" + candidate.getCode());
        }

        // 只有所有检查通过后才修改内部状态。
        charts.add(candidate);
    }

    /**
     * 发布草稿看板；这个状态转换只能通过业务方法发生。
     */
    public void publish() {
        // 防止重复发布或从非法状态转换。
        if (status != DashboardStatus.DRAFT) {
            throw new IllegalStateException("只有草稿看板可以发布");
        }

        // 所有前置条件通过后更新状态。
        this.status = DashboardStatus.PUBLISHED;
    }

    /**
     * 校验标题并移除首尾空白。
     *
     * @param value 原始标题
     * @return 标准化后的标题
     */
    private static String normalizeTitle(String value) {
        // 先检查 null，再将空白处理规则统一应用。
        String normalized = requireText(value, "看板标题");

        // 标题长度限制属于看板自身的有效性规则。
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("看板标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }

        // 返回标准化文本供字段使用。
        return normalized;
    }

    /**
     * 校验必填文本；static 方法不依赖任何 Dashboard 实例字段。
     *
     * @param value 原始文本
     * @param fieldName 字段中文名
     * @return 标准化后的文本
     */
    private static String requireText(String value, String fieldName) {
        // 在类边界拒绝 null 值。
        String normalized = Objects.requireNonNull(value, fieldName + "不能为空").trim();

        // 空字符串不能代表有效身份或标题。
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空字符串");
        }

        // 返回去除首尾空白后的值。
        return normalized;
    }

    /**
     * 静态嵌套 Builder：逐项收集可选配置，最后创建有效的 Dashboard。
     */
    public static final class Builder {

        // Builder 保存创建对象所需的基础字段。
        private final String id;

        // Builder 保存标题，最终仍由 Dashboard 构造器校验。
        private final String title;

        // 未显式指定时默认创建草稿。
        private DashboardStatus status = DashboardStatus.DRAFT;

        // 暂存图表配置；build 时会通过 addChart 再校验重复编码。
        private final List<Chart> charts = new ArrayList<>();

        /**
         * 创建 Builder，基础信息是必填项。
         *
         * @param id 看板 ID
         * @param title 看板标题
         */
        private Builder(String id, String title) {
            // 先校验基础参数，避免 Builder 保留明显无效数据。
            this.id = requireText(id, "看板 ID");
            this.title = normalizeTitle(title);
        }

        /**
         * 设置状态并返回当前 Builder，支持链式调用。
         *
         * @param status 初始状态
         * @return 当前 Builder
         */
        public Builder status(DashboardStatus status) {
            // 不保存 null，避免 build 后才出现不清楚的失败。
            this.status = Objects.requireNonNull(status, "看板状态不能为空");
            // 返回 this 以支持 .status(...).addChart(...) 的写法。
            return this;
        }

        /**
         * 暂存一项图表配置并返回当前 Builder。
         *
         * @param chart 要添加的图表
         * @return 当前 Builder
         */
        public Builder addChart(Chart chart) {
            // 先拒绝 null，再把图表留待最终对象创建时校验重复项。
            this.charts.add(Objects.requireNonNull(chart, "图表不能为空"));
            // 返回当前 Builder 继续链式配置。
            return this;
        }

        /**
         * 创建最终对象；不直接暴露 Builder 内部的列表引用。
         *
         * @return 满足类不变量的看板对象
         */
        public Dashboard build() {
            // 构造器会验证 ID、标题和状态。
            Dashboard dashboard = new Dashboard(id, title, status);

            // 逐项通过业务方法添加，复用重复图表编码检查。
            for (Chart chart : charts) {
                dashboard.addChart(chart);
            }

            // 返回配置完成且不与 Builder 列表共享引用的对象。
            return dashboard;
        }
    }
}
~~~

运行后的两个关键观察：`dashboard.rename(...)` 只能通过类规定的标题校验；`dashboard.getCharts()` 返回的是外部不能修改的快照。类因此保护了自己的不变量——比如标题非空、图表编码不重复、已发布对象不能再次发布。

## 引用、别名与 Java 的按值传参

Java 的参数传递始终是**按值传递**。当参数是对象时，复制的值是“指向对象的引用”：调用方法可以通过这份引用修改同一个对象，却不能把调用方变量重新指向另一个对象。

~~~java
/**
 * 可独立运行：演示 Java 对象参数是“引用值的副本”。
 * 保存为 ReferenceDemo.java 后，可使用 javac --release 11 编译。
 */
public class ReferenceDemo {

    /**
     * 程序入口：分别观察修改对象和重新绑定方法形参的结果。
     */
    public static void main(String[] args) {
        // user 变量保存一个指向 User 对象的引用。
        User user = new User("初始名字");

        // 把引用值复制给方法形参；两个引用暂时指向同一个对象。
        changeName(user);

        // 对象字段被方法改过，因此调用方可以看到新名字。
        System.out.println("方法结束后的对象名字：" + user.getName());
    }

    /**
     * 修改共享对象后，再将局部形参绑定到另一个对象。
     *
     * @param user 从调用方复制过来的引用值
     */
    private static void changeName(User user) {
        // user 和调用方变量指向同一对象，所以字段修改对双方可见。
        user.setName("修改后的名字");

        // 这里仅让局部变量 user 指向新对象，不会改变调用方变量。
        user = new User("另一个对象");

        // 此处打印的是新对象；方法返回后这个局部引用就消失。
        System.out.println("方法内部重新绑定后的名字：" + user.getName());
    }

    /**
     * 一个用于演示对象引用的简单可变类。
     */
    private static final class User {

        // 用户名字是对象的可变状态。
        private String name;

        /**
         * 创建用户对象。
         *
         * @param name 初始名字
         */
        private User(String name) {
            // 保存调用方传入的初始值。
            this.name = name;
        }

        /**
         * 修改对象名字。
         *
         * @param name 新名字
         */
        private void setName(String name) {
            // 修改当前 User 实例的字段。
            this.name = name;
        }

        /**
         * 读取对象名字。
         *
         * @return 当前名字
         */
        private String getName() {
            // 返回当前实例保存的名字。
            return name;
        }
    }
}
~~~

理解别名（多个变量引用同一对象）可以解释很多“为什么我没直接改它，它却变了”的问题。DTO、集合和可变实体在多层调用间共享时，要明确谁有修改权限；返回内部集合、缓存共享对象或把请求数据存入单例字段，都会扩大副作用范围。

## 字段默认值、局部变量与空值

字段会在对象创建时得到默认值：数值 primitive 通常为 `0`，`boolean` 为 `false`，引用类型为 `null`。方法中的局部变量没有默认值，必须先明确赋值再读取；这是编译器避免使用未初始化数据的保护。

| Java 字段类型 | 字段默认值 | 常见接口含义 |
|---|---|---|
| `int`、`long` 等 primitive | `0` | 无法区分“调用方未传”和“调用方明确传 0” |
| `Integer`、`Long` 等包装类型 | `null` | 可以用 `null` 表示缺省，但需要校验和防拆箱空指针 |
| 普通引用类型 | `null` | 访问成员前要确认值已创建 |

JSON 中字段省略、显式 `null` 和 `0` 的具体绑定结果还受 Jackson 配置、构造器和校验规则影响。用 `int` 接收可选分页大小时，字段省略可能无法表示“没有提供”；用 `Integer` 可以表示缺失，但把 `null` 自动拆箱成 `int` 会抛 `NullPointerException`。服务入口应通过校验注解、默认值或显式分支定义语义。

基本类型也不能调用 `equals`，包装类型可能为空；比较两个可能为空的值可用 `Objects.equals(a, b)`。

## 封装：把规则放在数据旁边

封装不是机械地“所有字段 private，再生成一堆 setter”。它的目标是让类控制自己的有效状态：

1. 字段保持 `private`，外部只能看到需要的能力。
2. 构造器校验创建时的必需条件。
3. 用有业务含义的方法（如 `publish`、`addChart`）代替任意字段赋值。
4. 如果确实提供 setter，也要检查它是否能让对象进入无效状态。
5. 返回集合时考虑副本或只读视图，防止外部绕过类的方法修改内部状态。

`getCharts()` 返回不可修改副本比直接返回内部 `List` 安全，但复制是浅复制：如果列表元素本身可变，调用方仍可能改元素。示例中的 `Chart` 是不可变对象，因此浅复制足够。若元素可变，还需要返回不可变 DTO/副本，或让元素本身提供受控操作。

## 可变对象与不可变对象

可变对象创建后状态可以变化；不可变对象创建后状态不能变化。不可变对象更容易共享、缓存和并发读取，调试时也不必追踪谁在什么时候改过它。

构造不可变类的常见方法：

- 类声明为 `final`，避免子类增加会改变状态的行为。
- 状态字段使用 `private final`。
- 构造时校验所有必需输入，不提供修改字段的 setter。
- 对可变输入做防御性复制；返回可变内部值时也要复制。
- 使用 `equals` 和 `hashCode` 定义值相等语义。

项目中常见的请求 DTO 可能需要框架反序列化，因此不一定适合做成完全不可变类；是否可以用构造器注入、字段注入或 setter，要以当前 Jackson/ORM 配置为准。

## `this`、`super`、构造器链和初始化顺序

- `this.field` 表示当前对象的字段，常用来区分同名参数与字段，例如 `this.title = title`。
- `this(...)` 调用同类的另一个构造器，必须放在第一条语句。
- `super(...)` 调用父类构造器，也必须放在第一条语句；因此它不能与同一个构造器里的 `this(...)` 同时出现。
- `super.method()` 可以显式调用父类实现，常见于重写方法后扩展父类行为。

对象初始化大致按以下顺序发生：先初始化父类，再按源码顺序初始化当前类的实例字段和实例初始化块，最后执行构造器主体。静态字段和静态初始化块在类初始化时按源码顺序执行。不要在字段初始化期间调用可被子类重写的方法，否则子类字段可能还没初始化。

## `static`、`final` 与常量

`static` 成员属于类本身，不依赖某个对象；静态方法不能直接访问实例字段，因为它没有 `this`。静态方法常用于工厂、无状态工具逻辑和常量访问。

`final` 对不同成员有不同含义：

- **final 变量/字段**：只能赋值一次。若字段保存对象引用，不能重新指向其他对象，但对象本身仍可能可变。
- **final 方法**：子类不能重写该方法。
- **final 类**：不能被继承，例如示例中的不可变 `Chart`。
- **static final 常量**：惯例使用全大写下划线命名。引用类型常量仍不自动不可变，常用 `String`、primitive 或不可变值。

Java 没有用来冻结任意对象图的 `const`。要保护集合，需要限制引用访问并返回不可变包装或副本。

## `==`、`equals`、`hashCode` 与 `toString`

- primitive 的 `==` 比较值。
- 对象的 `==` 比较两个引用是否指向同一个对象（身份相同）。
- `equals` 用于定义业务上的值相等；默认实现仍是身份比较，许多值类会重写它。
- 重写 `equals` 时必须同时重写 `hashCode`，并使用同一组身份字段，否则 `HashSet`/`HashMap` 的查找行为会异常。
- `toString` 适合提供日志和调试摘要，避免输出密码、Token、完整个人数据等敏感字段。

`new String("a") == new String("a")` 为 `false`，即使内容一样；字符串驻留可能让某些 `==` 示例偶然为 `true`，不能依赖这种行为。`Objects.equals(left, right)` 可以安全处理任一参数为 `null` 的值比较。

实体和值对象对“相等”的定义可能不同：实体通常按稳定 ID 识别同一个业务对象；值对象通常按其组成值比较。不要把所有字段都不加思考地放进 `equals`，尤其字段可变时不要用它作为哈希键。

## 重载、重写与多态调用

**重载（overload）**是同一类里方法同名、参数列表不同；编译器根据参数类型和数量选择具体签名。只改变返回类型不能构成重载。

**重写（override）**是子类/实现类提供父类或接口方法的实现；调用哪个重写版本由运行时对象决定。加 `@Override` 让编译器检查是否真的匹配了父类方法。

~~~java
/**
 * 可独立运行：演示重载按编译期类型选择、重写按运行时对象分派。
 * 保存为 OverloadOverrideDemo.java 后，可使用 javac --release 11 编译。
 */
public class OverloadOverrideDemo {

    /**
     * 程序入口：分别调用两个重载方法和一个被重写的方法。
     */
    public static void main(String[] args) {
        // 创建格式化器，用来观察重载解析。
        Formatter formatter = new Formatter();

        // 变量的静态类型是 Object，因此编译器选择 format(Object)。
        Object value = "文本内容";
        formatter.format(value);

        // 变量的静态类型是 String，因此编译器选择 format(String)。
        String text = "另一段文本";
        formatter.format(text);

        // 左侧类型是接口，实际对象是 CsvExporter。
        Exporter exporter = new CsvExporter();

        // 运行时根据实际对象选择 CsvExporter 覆写的 export 实现。
        System.out.println("导出格式：" + exporter.export());
    }

    /**
     * 定义两个参数列表不同的同名方法，构成重载。
     */
    private static final class Formatter {

        /**
         * 处理一般对象。
         *
         * @param value 输入对象
         */
        private void format(Object value) {
            // 输出方法签名，便于观察编译器选择了哪个重载。
            System.out.println("调用 format(Object)：" + value);
        }

        /**
         * 专门处理字符串。
         *
         * @param value 输入字符串
         */
        private void format(String value) {
            // 输出方法签名，便于观察编译器选择了哪个重载。
            System.out.println("调用 format(String)：" + value);
        }
    }

    /**
     * 规定所有导出器都必须提供的能力。
     */
    private interface Exporter {

        /**
         * 执行导出并返回示例格式名称。
         *
         * @return 导出格式
         */
        String export();
    }

    /**
     * CSV 导出器提供 Exporter 接口的一种实现。
     */
    private static final class CsvExporter implements Exporter {

        /**
         * 重写接口方法；调用者可以只依赖 Exporter 类型。
         *
         * @return CSV 格式名称
         */
        @Override
        public String export() {
            // 运行时实际对象是 CsvExporter，因此执行此实现。
            return "CSV";
        }
    }
}
~~~

重载与重写的混淆常导致调试误判：编译器先按变量声明类型解析重载；之后对象方法的虚调用再按实际对象分派重写实现。静态方法不能重写；子类声明同签名静态方法属于隐藏，不应当成多态。

## 接口、抽象类与组合如何选择

| 机制 | 解决的问题 | 适合场景 |
|---|---|---|
| 接口 `interface` | 约定调用能力，减少调用者对实现细节的依赖 | 多个无关类具有同一能力；例如可替换的数据导出器 |
| 抽象类 `abstract class` | 共享共同状态、构造过程和部分默认实现 | 多个类型确实属于同一类概念，且需要复用受保护的实现 |
| 组合（一个类持有另一个对象） | 复用一个协作能力而不建立强继承关系 | 可替换策略、服务协作者、查询执行器 |
| 继承 `extends` | 表达可替换的“is-a”关系 | 子类能在所有使用父类的地方保持父类契约 |

Java 类只能继承一个父类，但可以实现多个接口。优先用组合表达“使用某个能力”；不要为了复用几行代码就继承一个语义不相关的类。若子类不能安全地替换父类，继承结构就会让调用方难以预测行为。

## Builder、静态工厂和 JavaBean 风格

- **静态工厂**：用有语义的方法名创建对象，例如 `createDraft(...)`；可隐藏构造器选择并返回已有实例或子类型。
- **Builder**：必需参数先传入 Builder，可选参数用链式方法设置，适合配置项多且可选项多的对象。
- **JavaBean 风格**：常见 `private` 字段、无参构造器、getter/setter；很多 ORM 和 JSON 框架支持这种约定，但应检查框架实际配置。
- **构造器注入**：Spring Service 常通过构造器明确声明必需依赖，比字段注入更容易测试，也能让依赖不可变。

避免为只有一两个字段的简单对象机械增加 Builder。也不要将 Builder 本身泄漏到业务层并长期复用；示例的 Builder 每次 `build()` 都创建独立 Dashboard。

## 嵌套类与内部类

- **静态嵌套类**使用 `static class`，不隐式持有外部对象引用。Builder 常采用这种形式。
- **成员内部类**没有 `static`，每个实例会隐式关联一个外部类对象；只在确实需要访问外部实例状态时使用。
- **局部类/匿名类**定义在方法内部，适合范围很小的实现；现代 Java 中简单回调通常优先 Lambda。

内部类会让外部依赖关系不那么显眼。若把非静态内部类交给长生命周期任务保存，它可能连带让外部对象无法回收；Android 生命周期场景尤其常见。

## Java 类与项目中的 DTO、Entity、VO

同样是 Java 类，放在不同层的职责不同：

- **DTO**描述接口或服务之间传输的数据，应关注字段校验和兼容性。
- **Entity**描述数据库持久化结构，字段和注解受表设计、ORM 影响。
- **VO**描述某个接口希望返回给调用方的形状，可组合多份数据并隐藏内部字段。
- **Service**通常表达业务能力。Spring 的 Service Bean 多数是单例，因此不要把某次 HTTP 请求的临时参数保存到可变实例字段；请求数据应放在方法局部变量或明确的请求上下文中。

Java 泛型有助于编译期类型检查，例如 `List<Chart>`；但 HTTP JSON 进入应用时还要经过反序列化和业务校验。字段名、运行时子类型及多态 JSON 如何恢复，取决于 Jackson 注解、TypeHandler 或项目自定义配置。不要把“代码声明了泛型”当成外部输入已经验证过。

在当前项目阅读时，可以从 `DashboardPageController` 的请求 DTO 和注入的 Service 开始，再看 `PageEntity` 与返回 VO；页面配置字段的运行时类型要继续追查 TypeHandler/序列化配置。路径随分支变化时，用类名搜索确认。

## 类设计检查清单

读或写一个类时，逐项问：

1. 它表达哪一个清晰概念？是否承担多个互不相关的职责？
2. 哪些字段构成身份，哪些只是可变状态或展示内容？
3. 对象刚创建时必须满足什么条件？构造器是否保证条件成立？
4. 哪些状态转换有业务规则？是否应使用命名方法代替 setter？
5. 可变集合/对象是否泄漏给调用方？是否需要副本？
6. 这个类应该按身份比较，还是按字段值比较？`equals` 与 `hashCode` 是否一致？
7. 它依赖具体实现还是抽象能力？继承是不是合理的 is-a 关系？
8. 它是否被 Spring 作为单例管理？有没有错误地保存请求级状态？
9. JSON/ORM 框架如何创建实例、填字段、恢复多态类型？

## 常见误区

- 以为把字段改成 `private` 就完成封装；仍要防止 setter 和集合 getter 破坏不变量。
- 以为 Java 对象参数是“引用传递”；准确说法是按值传递，传入的值恰好是对象引用。
- 用 `==` 比较字符串内容；应使用 `equals` 或 `Objects.equals`。
- 重写 `equals` 却忘记 `hashCode`，导致哈希集合中查找异常。
- 只因实现复用几行代码就继承；继承首先表达可替换的类型关系。
- 认为 `final List<X>` 不可修改；它只禁止字段引用重新赋值。
- 在 Spring 单例 Service 上放 `currentUser`、`currentRequest` 等可变字段，造成并发请求互相覆盖。
- 认为泛型能验证外部 JSON；运行时仍需要正确反序列化和输入校验。

## 练习

1. 将示例中的 `Dashboard` 字段改为 `public`，试着指出哪几条状态规则会被绕过，再恢复封装。
2. 给 Dashboard 加一个 `removeChart(code)` 方法，说明找不到图表时是忽略、返回状态还是抛异常，并实现一致行为。
3. 修改 `Chart.equals`，试着只比较标题；分析这是否符合图表“身份”的定义，以及 HashSet 会如何表现。
4. 写一个接收 `User` 参数的方法：修改对象属性，再给形参 `new User(...)` 重新赋值，观察调用方结果。
5. 给一个有四个可选字段的请求配置设计构造器和 Builder，比较两种调用方式的可读性。
6. 搜索项目里的 `@Service` 类实例字段，区分常量/只读依赖与请求级可变状态。

下一步：[集合：List、Set、Queue 与 Map](./collections.md)。
