# Java 基础

这组章节面向熟悉 JavaScript、正在阅读 Java 服务端代码的前端开发者。目标是让你能顺着变量类型、对象结构、集合、异常和线程调用，把接口契约读清楚；不要求先掌握所有 Java 语言细节。

## 学完后你应该能

- 读懂 Java 方法签名：参数、返回类型、泛型和异常声明。
- 区分基本类型和引用类型，判断哪些字段可能是 null。
- 理解 Service 接口与实现类的关系，读懂依赖注入后方法如何被调用。
- 读懂 List、Map、Set、Stream 以及常见的异常处理。
- 区分基础集合实现的顺序、唯一性和复杂度，并选择线程安全容器和有界任务队列。
- 识别 Web 服务中的共享状态、线程池和异步调用风险。
- 用 JVM 和 I/O 基础知识排查接口延迟、内存占用、文件上传和外部调用问题。

## 推荐阅读顺序

1. [类、对象与类型](./core.md)：解决“这个对象是什么类型，谁实现了这个能力”的问题。
2. [泛型：类型参数、通配符与类型擦除](./generics.md)：读懂 `List<T>`、分页响应和 Mapper 签名，明确编译期类型检查与运行时边界。
3. [注解大全：Java、Spring 与常用框架](./annotations.md)：理解注解如何描述元数据，以及框架如何读取它们产生行为。
4. [集合框架总览](./collections.md)：理解接口层级、泛型和集合共同操作。
5. [基础容器与数据结构](./containers.md)：按顺序、去重、排序、查找和复杂度选 List/Map/Set/Queue 的实现。
6. [并发容器](./concurrent-containers.md)：理解并发 Map、Copy-On-Write、BlockingQueue、有界队列和背压。
7. [Stream：过滤、转换与汇总](./streams.md)：解决“如何清楚地表达集合数据处理流水线”的问题。
8. [日期时间与金额精度](./date-time-money.md)：在 API、数据库和支付处理中正确选择时区、时间类型、小数精度和舍入方式。
9. [异常：分类、传播与处理](./exceptions.md)：解决“失败如何向合适边界传播并转换成可理解响应”的问题。
10. [并发总览与使用场景](./concurrency.md)：判断何时值得并发，理解共享状态和内存可见性。
11. [线程创建、生命周期与中断](./threads.md)：学会创建线程、取结果、等待和协作停止。
12. [锁的类型、优缺点与选择](./locks.md)：选择 synchronized、显式锁、读写锁和原子操作。
13. [线程池、Future 与异步编排](./thread-pools.md)：限制并发、处理过载、超时和异常。
14. [支付宝与微信支付实战](../../examples/online-payment.md)：把线程、锁、金额精度和幂等用到下单与重复通知场景。
15. [JVM 与垃圾回收](./jvm.md)：解决服务进程中的内存、GC 和线程问题。
16. [IO：流、编码与文件处理](./io.md)：理解字节/字符流、资源关闭、短读和大文件内存边界。
17. [NIO：Buffer、Channel 与 Selector](./nio.md)：掌握缓冲区状态、网络就绪事件、半包/粘包与背压。
18. [网络库、HTTP 客户端与选型](./network-libraries.md)：比较 JDK HttpClient、OkHttp、Apache、Feign、WebClient 与 Netty。
19. [大文件与分片上传](../../examples/large-file-upload.md)：串起前端切片、有限并发、续传、后端校验、幂等合并和对象存储直传。

## 从前端语法迁移到 Java 的几个关键点

| 前端概念 | Java 阅读时的对应理解 |
|---|---|
| JavaScript 动态值 | Java 变量声明明确类型；编译器会检查大部分赋值和调用 |
| TypeScript interface | Java interface 常用于定义行为契约；DTO 的字段结构则由 class 描述 |
| object literal | Java 对象通常由类、构造方法和 setter 建立；Map 也能表示动态键值 |
| Array | 固定长度数组，或可增删的 List |
| undefined / null | Java 没有 undefined；对象引用可以为 null，基本类型不能 |
| Promise / async | Future、CompletableFuture 等；线程执行、异常和上下文传递都要单独理解 |
| throw Error | Java 异常；框架可能在边界处转换成 HTTP 错误响应 |
| npm package | Maven artifact；pom.xml 决定依赖和版本 |

## 读项目源码的学习方法

这些知识用于把浏览器现象映射到服务端原因：类型和 DTO 帮你核对请求契约，集合帮助你追踪数据有没有被过滤或分组，异常帮助你判断错误是否被转换成空响应，并发、JVM 和 I/O 则帮助你解释服务压力、线程等待和文件/网络延迟。每章都包含一段可独立阅读或编译的中文注释 Java 示例；本页作为目录页，负责导向各个实践章节。

面对一个接口，按以下问题做笔记：

1. Controller 方法的参数类型是什么？由 URL、JSON body 还是文件表单绑定？
2. DTO 的字段哪些必填，哪些包装类型可能为空？
3. Controller 调用了哪个 Service 接口？实现类在哪里？
4. Service 的输入和输出是否经过转换、过滤、分组或降级？
5. 集合的泛型是什么？异常在哪里被抛出或处理？
6. 有没有异步任务、共享 Bean 字段或文件/网络 I/O？

在当前项目中，可以从 DashboardPageController 和 DashboardPageServiceImpl 观察 Controller → Service 的基本分层；从 ChartDataController 观察 `List<Chart>`、页面配置和图表查询如何连接。具体路径随分支变化时，用类名在仓库中搜索。

## 常见误区

- 看到类型声明不代表运行时数据一定符合业务约束；HTTP 输入仍需校验。
- 看到 `List<T>` 不代表列表有序、不可变或线程安全；要看具体实现与使用方式。
- 看到接口类型不代表接口本身在运行时负责创建对象；Spring 容器或调用方负责提供实现。
- 看到 try/catch 不代表错误已妥善处理；继续确认是否记录上下文、返回错误，或静默变为空数据。
- Java 使用垃圾回收不代表不会内存泄漏；仍被静态集合或缓存引用的对象不会被回收。

## 练习

1. 找到一个 Controller 方法，列出方法参数、返回对象和所有注解。
2. 找到它调用的 Service 接口和实现类，画出调用路径。
3. 对一个 DTO，标记基本类型、包装类型、字符串和集合，并判断哪些字段可为空。
4. 在浏览器 Network 中记录响应形状，再对照 Java 的返回对象和前端响应拦截器。
5. 选一处异常处理，解释异常是抛给全局处理器、返回业务错误，还是转成空结果。

下一步：[类、对象与类型](./core.md)。
