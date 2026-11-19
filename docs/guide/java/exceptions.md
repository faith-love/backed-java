# Java 异常与错误边界（Java 11）

异常解决的是“当前操作不能按正常结果继续执行”这个问题。前端看到的 `HTTP 4xx/5xx`、统一错误码和提示信息，通常都是后端在某一层捕获、转换或交给全局处理器后的结果。学习异常时不要只记语法，要同时回答三个问题：

1. **谁能处理？** 当前层是否有足够上下文做恢复、重试或返回替代结果？
2. **应该保留什么信息？** 原始异常、业务 ID、请求参数摘要和调用链是否仍可追踪？
3. **边界返回什么？** Controller、消息消费者或定时任务怎样把失败转换成调用方能理解的状态？

异常表示失败，不能把异常当成普通分支，也不能用空结果掩盖失败。只有在当前层能恢复、补充上下文或转换协议时才捕获；否则让异常继续向能处理它的边界传播。

## Throwable 层级：先区分故障性质

Java 中所有可被 `throw` 抛出并可被 `catch` 捕获的对象都继承自 `Throwable`。它有两个主要分支：`Error` 和 `Exception`。

~~~text
Throwable
├── Error
│   ├── OutOfMemoryError
│   ├── StackOverflowError
│   └── NoClassDefFoundError ...
└── Exception
    ├── RuntimeException（unchecked，运行时异常）
    │   ├── NullPointerException
    │   ├── IllegalArgumentException
    │   ├── IllegalStateException
    │   └── IndexOutOfBoundsException ...
    └── 其他 Exception（checked，受检异常）
        ├── IOException
        ├── SQLException
        └── TimeoutException ...
~~~

### Error：通常表示运行时环境无法正常继续

`Error` 多数表示 JVM、类加载、线程栈或内存等基础设施出现严重问题。例如堆内存耗尽会抛 `OutOfMemoryError`，递归没有终止可能抛 `StackOverflowError`。应用一般不应该捕获 `Error` 后继续伪装成成功，因为进程状态可能已经不可靠。

**解决的问题：** 让 JVM 报告无法由普通业务代码恢复的严重故障。

**何时处理：** 通常由进程管理器、容器和监控系统负责重启、告警和保留诊断信息。只有框架在明确知道如何清理资源时，才会非常谨慎地捕获特定 `Error`。

**后端场景：** 如果容器日志出现 `OutOfMemoryError`，优先检查堆转储、对象保留、缓存上限和容器内存，而不是在 Controller 中 `catch (Throwable)` 返回“系统繁忙”。

### Exception：应用可以表达和处理的失败

`Exception` 更适合表示输入无效、资源访问失败、业务规则不允许或下游调用失败。`RuntimeException` 是 `Exception` 的子类，但编译器不强制调用方声明或捕获它。

看到 `catch (Throwable)` 或 `catch (Exception)` 时要提高警惕：它们可能把真正的编程错误、系统故障和业务失败全部混在一起。通常应捕获最具体、能处理的异常类型。

## checked 与 unchecked：编译器要求你表态的边界

### checked exception（受检异常）

除 `RuntimeException` 和 `Error` 外的 `Exception` 默认是 checked exception。方法必须捕获它，或在签名上用 `throws` 声明继续交给上层处理。

**解决的问题：** 强迫调用方面对文件、数据库、网络等可能失败的外部资源操作，避免完全忘记处理失败路径。

**适用场景：** 失败具有明确恢复方式，且调用方确实应该根据异常类型做决定。例如读取可选配置文件时，可以捕获 `IOException` 并使用默认配置；文件上传时，可以把读取失败转换为一个有上下文的业务异常。

受检异常也有成本：它会沿很多层传播，方法签名可能变得冗长。若某层无法恢复，应统一转换为带原因的领域异常，而不是每层都打印一次或返回空值。

### unchecked exception（非受检异常）

`RuntimeException` 不要求在方法签名声明。它适合表示参数违反契约、对象状态不合法、资源配置错误或业务边界失败。

**解决的问题：** 让不适合由每个调用方逐层处理的失败直接传播到统一边界，同时保留清晰的类型和消息。

**后端场景：** `IllegalArgumentException` 可以表示分页大小超出范围；自定义 `BusinessException` 可以表示“报表不存在”或“当前用户无权访问”，再由全局异常处理器转换为统一 JSON。

unchecked 不代表可以随意抛。它仍然需要明确的错误码、日志上下文和边界处理；否则前端只能看到模糊的 500。

## 常见异常与定位方向

| 异常 | 常见原因 | 解决的问题 | 排查重点 |
|---|---|---|---|
| `NullPointerException` | 对 `null` 引用调用方法或拆箱 | 暴露违反非空预期的位置 | DTO 是否允许空、数据库字段是否为空、调用链哪一步返回了 null |
| `IllegalArgumentException` | 参数值不符合方法约定 | 拒绝无效输入 | 参数来源、边界校验、单位和格式 |
| `IllegalStateException` | 对象当前状态不允许执行操作 | 防止错误的状态迁移 | 是否重复提交、生命周期是否已结束 |
| `IndexOutOfBoundsException` | 列表或数组下标越界 | 暴露错误的索引计算 | 分页起始位置、空列表、下标与长度 |
| `ClassCastException` | 强制类型转换不匹配 | 暴露错误的动态类型假设 | `Map<String,Object>` 值类型、反序列化结构 |
| `NumberFormatException` | 文本不能转换成数字 | 拒绝格式错误的分页或筛选参数 | 前端 query 是否为空、是否包含非数字字符 |
| `IOException` | 文件、流或网络读写失败 | 表达外部 I/O 不可用 | 路径、权限、连接关闭、超时和磁盘空间 |
| `SQLException` | SQL、连接或事务失败 | 表达数据库操作失败 | SQL 参数、连接池、约束冲突和事务日志 |
| `TimeoutException` | 操作超过等待上限 | 限制调用方等待时间 | 哪一跳超时、重试次数、下游健康状态 |
| `OutOfMemoryError` | 堆、元空间或直接内存耗尽 | 报告进程资源失控 | 堆转储、缓存、批量数据、容器限制 |

异常类型只能提供线索，最终仍要结合堆栈、请求 ID、业务 ID 和运行环境日志确认事实。

## `throw` 与 `throws`：抛出和声明是两件事

- `throw` 在方法体中主动抛出一个异常对象，表示当前代码判断操作不能继续。
- `throws` 出现在方法签名中，声明该方法可能把某个 checked exception 交给调用方。
- `throw` 后面必须是一个 `Throwable` 实例；`throws` 后面是异常类型列表。
- 异常消息应说明失败对象和关键上下文，但不要写入密码、Token 或完整敏感请求体。

~~~java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * throw 与 throws 的最小示例。
 * 保存为 ThrowDeclarationDemo.java 后，可使用 Java 11 编译运行。
 */
public class ThrowDeclarationDemo {

    /**
     * checked 异常由方法签名声明，调用方必须捕获或继续声明。
     *
     * @param path 要读取的文件路径
     * @return 文件文本
     * @throws IOException 文件不存在、无权限或读取过程中失败
     */
    static String readText(Path path) throws IOException {
        // 这是外部 I/O 失败，不能假设一定能恢复，因此交给上层决定策略。
        return Files.readString(path);
    }

    /**
     * unchecked 异常由业务代码主动抛出，不需要在签名上声明。
     *
     * @param pageSize 每页数量
     * @return 原始分页大小
     */
    static int validatePageSize(int pageSize) {
        // 参数违反接口约定时立即失败，避免非法值深入查询层。
        if (pageSize < 1 || pageSize > 100) {
            // throw 创建并抛出一个运行时异常对象。
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
        return pageSize;
    }

    /**
     * 程序入口，展示调用方分别处理两类异常。
     */
    public static void main(String[] args) {
        try {
            // 运行时异常表示调用方传入了不符合约定的值。
            System.out.println(validatePageSize(200));
        } catch (IllegalArgumentException exception) {
            // 当前层能给出明确的参数错误提示，因此在边界处处理它。
            System.out.println("参数错误：" + exception.getMessage());
        }

        try {
            // checked 异常必须捕获，或者让 main 继续写 throws 声明。
            System.out.println(readText(Path.of("missing-file.txt")));
        } catch (IOException exception) {
            // 这里只展示可理解的结果；真实服务还应记录请求 ID 和文件标识。
            System.out.println("文件读取失败：" + exception.getMessage());
        }
    }
}
~~~

## `try-catch-finally`：在能决定策略的地方处理

`try` 放可能失败的操作，`catch` 按异常类型处理，`finally` 无论成功或失败都尝试执行清理。`finally` 适合释放旧式资源，但资源关闭本身也可能失败，手写代码容易遗漏或覆盖原始异常。

~~~java
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * try-catch-finally 的示例；重点是按职责处理，而不是捕获所有异常。
 */
public class FinallyDemo {

    /**
     * 读取第一行文本，并在 finally 中关闭旧式 Reader。
     *
     * @param filePath 文件路径
     * @return 第一行文本，空文件返回 null
     * @throws IOException 读取或关闭文件失败
     */
    static String firstLine(String filePath) throws IOException {
        BufferedReader reader = null;
        try {
            // 打开资源可能失败；失败时 reader 仍然是 null。
            reader = new BufferedReader(new FileReader(filePath));
            // 读取操作也可能抛 IOException，交给调用方处理。
            return reader.readLine();
        } finally {
            // 只有成功创建的 Reader 才能关闭，避免对 null 调用方法。
            if (reader != null) {
                // 关闭失败会继续抛 IOException，并可能影响原始读取异常的诊断。
                reader.close();
            }
        }
    }
}
~~~

在新代码中，优先使用 try-with-resources；它会自动关闭实现 `AutoCloseable` 的资源，并通过 suppressed exception 保留关闭阶段的异常信息。

## try-with-resources：可靠关闭文件、连接和响应体

**解决的问题：** 文件句柄、数据库连接、HTTP 响应体等资源如果没有关闭，会逐渐耗尽进程或连接池资源。try-with-resources 把关闭动作绑定到代码块生命周期，减少遗漏。

~~~java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * try-with-resources 可编译示例。
 * StringReader 只是演示 AutoCloseable；真实项目可替换为文件、数据库或 HTTP 资源。
 */
public class ResourceDemo {

    /**
     * 读取输入的第一行。
     *
     * @param content 文本内容
     * @return 第一行，空输入返回 null
     * @throws IOException Reader 读取失败
     */
    static String readFirstLine(String content) throws IOException {
        // try 头部声明资源，代码块结束时 Java 会自动调用 close。
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            // 读取失败仍然向上抛出，调用方可以转换为 API 错误或重试。
            return reader.readLine();
        }
    }

    /**
     * 程序入口。
     */
    public static void main(String[] args) throws IOException {
        // StringReader 没有真实文件依赖，示例可直接运行。
        System.out.println(readFirstLine("第一行\n第二行"));
    }
}
~~~

多个资源会按照声明顺序的反序关闭。若主体和关闭过程都抛异常，主体异常会作为主异常，关闭异常会保存在 `getSuppressed()` 中。不要在 `finally` 中无条件抛出新异常覆盖主异常。

## 包装异常：补充上下文并保留原始原因

底层异常通常使用技术术语，直接暴露给前端既难理解，也可能泄露内部结构。上层可以创建更符合领域的异常，并把原异常作为 cause 传入构造器：

~~~java
try {
    // 调用文件、数据库或远程服务等外部组件。
    repository.save(entity);
} catch (java.sql.SQLException exception) {
    // 新异常说明业务动作和对象标识，原始 SQLException 仍保留在 cause 链中。
    throw new DataAccessFailureException("保存用户 " + entity.getId() + " 失败", exception);
}
~~~

包装异常要遵循三条规则：

1. **保留 cause：** 使用 `new XxxException(message, exception)` 或 `initCause`，不要只拼接原异常消息。
2. **每层增加有价值的上下文：** 例如“导入文件中的第 32 行解析失败”，不要重复打印同一堆栈。
3. **到边界再转换协议：** Service 可以抛领域异常，Controller 全局处理器再转换成 HTTP 状态和 JSON；不要让 Service 拼接 HTTP 响应。

如果异常已经包含足够上下文且当前层没有新信息，可以直接继续抛出，避免无意义地多包一层。

## 自定义业务异常：表达可预期的业务失败

**解决的问题：** “报表不存在”“版本已停用”“当前用户无权访问”等情况不是 JVM 故障，但调用方需要稳定的错误码和可区分的处理方式。自定义业务异常能把这些规则与 `NullPointerException`、数据库异常区分开。

~~~java
/**
 * 业务异常携带稳定错误码；示例使用 unchecked 形式，避免每层都声明 throws。
 */
public class BusinessException extends RuntimeException {

    // 前端或全局处理器可根据错误码选择提示或跳转逻辑。
    private final String code;

    /**
     * 创建业务异常并保留用户可理解的消息。
     *
     * @param code 稳定的业务错误码
     * @param message 适合日志和受控展示的说明
     */
    public BusinessException(String code, String message) {
        // 父类保存异常消息，便于日志和堆栈输出。
        super(message);
        // 错误码不能为 null，避免错误响应再次失败。
        this.code = java.util.Objects.requireNonNull(code, "错误码不能为空");
    }

    /**
     * 读取错误码。
     *
     * @return 稳定错误码
     */
    public String getCode() {
        return code;
    }
}
~~~

业务异常的消息不应包含 SQL、服务器绝对路径、访问令牌或密码。日志可以记录内部异常和受控上下文，返回给前端的响应只暴露必要信息。

## 错误边界：把异常转换成调用方能处理的结果

错误边界是一个协议边界：Controller、消息消费者、定时任务或 CLI 入口需要决定异常怎样离开当前进程。

### HTTP Controller 边界

Spring 项目通常使用全局异常处理器（例如 `@RestControllerAdvice`）把异常转换成统一响应：

| 异常类型 | 可能的 HTTP 状态 | 前端可处理的语义 |
|---|---:|---|
| 参数校验失败 | 400 | 高亮字段并提示修改输入 |
| 未认证 | 401 | 清理登录状态并跳转登录 |
| 无权限 | 403 | 提示没有操作权限 |
| 资源不存在 | 404 | 展示空详情或返回列表 |
| 业务冲突 | 409 | 提示状态已变化或重复提交 |
| 下游暂时不可用 | 502/503 | 展示可重试提示，保留失败状态 |
| 未知编程错误 | 500 | 展示通用错误并记录 traceId |

HTTP 状态只是第一层语义。项目如果还有 `code`、`message`、`data` 包装，前端要同时读取 HTTP 状态和业务错误码。不要把所有异常都包装成 HTTP 200 的空数据，否则页面无法区分“确实没有数据”和“服务失败”。

### 消息消费者边界

消费者捕获异常后要决定确认、重试还是进入死信队列：

- 参数永久无效：记录原因并进入死信，避免无休止重试。
- 下游暂时超时：按有限次数和退避策略重试。
- 业务处理已成功但确认失败：依靠幂等键安全处理重复投递。
- 未知异常：记录消息 ID、业务 ID、尝试次数和完整 cause，再交给监控或死信流程。

### 定时任务边界

定时任务不能因为一个数据项失败就静默吞掉整批错误。应记录批次 ID、成功/失败数量和失败项，按业务决定继续处理其他项、重试失败项或终止批次。

## 一份完整的 Java 11 示例

下面的单文件示例把自定义业务异常、异常包装、try-with-resources 和边界处理放在一个可运行流程中。保存为 `ExceptionDemo.java`，用 `javac ExceptionDemo.java && java ExceptionDemo` 运行（Java 11）：

~~~java
import java.io.IOException;
import java.io.StringReader;
import java.util.Objects;

/**
 * Java 11 异常综合示例。
 *
 * 运行结果会展示：
 * 1. 参数错误如何在边界被转换为业务响应；
 * 2. 底层 IOException 如何包装成带 cause 的业务异常；
 * 3. try-with-resources 如何自动关闭资源。
 */
public class ExceptionDemo {

    /**
     * 可预期的业务异常，携带稳定错误码供 API 边界使用。
     */
    static final class BusinessException extends RuntimeException {

        // 错误码用于前端区分提示类型，不把中文文案当作程序判断条件。
        private final String code;

        /**
         * 创建业务异常。
         *
         * @param code 稳定错误码
         * @param message 受控错误说明
         */
        BusinessException(String code, String message) {
            // 调用父类保存 message，堆栈和日志可以输出它。
            super(message);
            // 错误码为空属于编程错误，立即暴露而不是产生坏响应。
            this.code = Objects.requireNonNull(code, "错误码不能为空");
        }

        /**
         * 获取业务错误码。
         *
         * @return 错误码
         */
        String getCode() {
            return code;
        }
    }

    /**
     * 表示保存动作失败的领域异常，保留底层 cause。
     */
    static final class SaveFailureException extends RuntimeException {

        /**
         * 以业务上下文包装底层异常。
         *
         * @param message 业务动作说明
         * @param cause 原始异常
         */
        SaveFailureException(String message, Throwable cause) {
            // cause 链让日志能够追到真正的 IOException。
            super(message, cause);
        }
    }

    /**
     * 用 try-with-resources 读取第一行，演示 checked IOException 的传播。
     *
     * @param content 输入文本
     * @return 第一行文本
     * @throws IOException 底层 Reader 读取失败
     */
    static String loadFirstLine(String content) throws IOException {
        // StringReader 实现 AutoCloseable，代码块退出时会自动 close。
        try (StringReader reader = new StringReader(content)) {
            // StringReader 的读取操作可能按 Reader 契约抛 IOException。
            char[] buffer = new char[64];
            int length = reader.read(buffer);
            // -1 表示没有任何字符；把空内容转换为业务层可理解的空字符串。
            return length < 0 ? "" : new String(buffer, 0, length);
        }
    }

    /**
     * 执行保存流程，展示参数校验和异常包装。
     *
     * @param userId 用户标识
     * @param content 要保存的内容
     * @return 保存结果
     */
    static String save(String userId, String content) {
        // 入口校验属于业务契约，使用自定义异常而不是 NullPointerException。
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("USER_ID_REQUIRED", "用户标识不能为空");
        }
        // 空内容不符合保存规则，抛出可被 API 边界识别的业务错误。
        if (content == null || content.isBlank()) {
            throw new BusinessException("CONTENT_REQUIRED", "保存内容不能为空");
        }

        try {
            // 真实项目这里可以替换为文件、数据库或远程调用。
            String firstLine = loadFirstLine(content);
            // 返回领域结果；Controller 再把它放进统一响应结构。
            return "已保存首行：" + firstLine;
        } catch (IOException exception) {
            // 当前层无法恢复 I/O 失败，但能补充“保存用户”的业务上下文。
            throw new SaveFailureException("保存用户 " + userId + " 失败", exception);
        }
    }

    /**
     * 模拟 HTTP 错误边界，把异常转换为稳定响应文本。
     *
     * @param userId 用户标识
     * @param content 保存内容
     * @return 模拟的 API 响应
     */
    static String handleRequest(String userId, String content) {
        try {
            // 正常路径返回业务结果。
            return "200 OK | " + save(userId, content);
        } catch (BusinessException exception) {
            // 业务错误不是服务器崩溃；返回可供前端识别的错误码。
            return "400/409 | code=" + exception.getCode()
                    + " | message=" + exception.getMessage();
        } catch (SaveFailureException exception) {
            // 内部日志应记录完整 cause；对调用方只返回受控的通用信息。
            System.err.println("保存失败，原因：" + exception.getCause());
            return "503 | code=SAVE_UNAVAILABLE | message=保存服务暂时不可用";
        }
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数，本示例不使用
     */
    public static void main(String[] args) {
        // 合法请求走正常路径。
        System.out.println(handleRequest("user-1", "第一行\n第二行"));
        // 非法请求走业务错误路径；没有用空字符串伪装成功。
        System.out.println(handleRequest("", "第一行"));
    }
}
~~~

这个示例刻意没有写 `catch (Exception) { return null; }`。吞掉异常会让上层误以为查询没有结果、保存已经成功或消息已经处理，从而把真正的故障扩散到更难排查的地方。

## 代码阅读与排错步骤

遇到一个接口报错时，可以按以下顺序追：

1. **先看浏览器结果：** 记录 HTTP 状态、业务 `code`、`message`、traceId 和响应时间。
2. **找到边界处理：** 搜索 `@RestControllerAdvice`、`@ExceptionHandler`、过滤器或网关错误包装，确认异常如何变成响应。
3. **沿 cause 链回溯：** 从最外层异常的 `getCause()` 和堆栈第一处业务代码开始，找到真正失败的资源或参数。
4. **判断异常类别：** 参数错误、权限错误、业务冲突、下游超时和编程错误的修复路径不同。
5. **核对是否重复处理：** 同一个异常是否被每层重复打印、包装丢失 cause，或被 catch 后返回空值。
6. **结合上下文验证：** 使用请求 ID、用户/报表 ID、消息 ID 和时间范围对照服务日志、SQL 日志和下游状态。

## 常见误区

- `catch (Exception)` 不等于健壮；捕获后必须恢复、转换或继续抛出。
- `catch (Throwable)` 通常会把 `Error` 也吞掉，可能掩盖 JVM 已经无法可靠运行的事实。
- 只打印 `exception.getMessage()` 会丢失堆栈和 cause；日志通常应记录异常对象本身。
- `throw new RuntimeException(exception.getMessage())` 会丢失原始异常类型和堆栈上下文，应使用带 cause 的构造器。
- `finally` 中无条件 `return` 会覆盖 try/catch 中的返回值和异常，禁止这样写。
- `finally` 中抛出新异常可能覆盖主异常；优先使用 try-with-resources。
- 用异常表示普通循环分支或“查不到就返回空”的常态，会让性能和控制流变差。
- 把所有失败返回 HTTP 200，会让前端、监控和重试逻辑无法区分成功与失败。
- 业务异常消息不能包含密码、Token、SQL、文件绝对路径或完整用户输入。
- 重试前先确认操作是否幂等；超时不代表服务端一定没有执行成功。

## 练习

1. 找一个项目中的全局异常处理器，列出它对参数错误、业务异常和未知异常分别返回什么 HTTP 状态与 JSON 字段。
2. 找一处 `catch (Exception)`，判断它是否真正恢复或转换了错误；如果只是打印或返回空值，写出更合适的处理边界。
3. 把一个文件读取示例改为 try-with-resources，并说明 Reader、数据库连接或 HTTP 响应体不关闭会造成什么资源问题。
4. 为“报表版本已停用”设计异常类和错误码，说明前端应展示什么提示、是否允许重试。
5. 在浏览器 Network、服务日志和异常 cause 链之间建立一条 traceId 对应关系，记录一次从 400 或 500 响应回溯到根因的过程。

下一步：[并发与线程安全](./concurrency)。
