# 学习指南

本知识库面向熟悉 Vue、JavaScript 和 HTTP 的前端开发者。学习重点不是背 Java 术语，而是知道一个后端概念解决了什么工程问题、它在请求链路的哪个位置，以及出问题时该去哪一层找证据。

## 按问题选择学习区域

| 知识区域 | 主要解决的问题 | 推荐入口 |
|---|---|---|
| Java 语言与对象模型 | 让业务数据有明确类型和职责，减少随意传值；帮助读懂 Controller/Service 中的类、接口、泛型与异常 | [Java 基础](./java/)、[泛型](./java/generics) |
| API 时间与金额 | 在跨语言传输、数据库持久化和支付计算中明确时区、精度与舍入规则 | [日期时间与金额](./java/date-time-money) |
| Java 基础与并发容器 | 按顺序、唯一性、哈希、排序和并发要求选择 ArrayList、HashMap、ConcurrentHashMap、BlockingQueue 等实现 | [集合总览](./java/collections)、[基础容器](./java/containers)、[并发容器](./java/concurrent-containers) |
| Java Stream | 以流水线方式过滤、映射、排序、分组和汇总集合数据 | [Stream 操作](./java/streams) |
| Java 异常 | 表达、传播和处理失败，避免错误被吞掉或暴露内部细节 | [异常处理](./java/exceptions) |
| 并发与线程安全 | 判断何时开线程、保护共享状态、限制任务数量和等待时间 | [总览](./java/concurrency)、[线程](./java/threads)、[锁](./java/locks)、[线程池](./java/thread-pools) |
| 线上支付实战 | 串起支付宝/微信下单、通知、幂等更新与可靠后续任务 | [支付示例](../examples/online-payment) |
| JVM 与垃圾回收 | 解释 Java 程序如何运行、内存为何增长以及服务为什么可能停顿或退出 | [JVM 与垃圾回收](./java/jvm) |
| IO、NIO 与网络库 | 理解字节/字符流、资源关闭、缓冲区、网络就绪事件、超时与连接管理 | [IO 流](./java/io)、[NIO](./java/nio)、[网络库](./java/network-libraries) |
| 大文件与分片上传 | 限制内存与并发，处理重试、断点续传、摘要校验、合并竞争与文件发布 | [上传实战](../examples/large-file-upload) |
| Spring IoC 与 AOP | 管理对象依赖，并把日志、事务、权限等横切逻辑集中处理 | [Spring 总览](./spring/) |
| Spring MVC、Boot 与安全 | 把 HTTP 请求绑定到 Java 方法，管理应用配置，并验证身份和操作权限 | [Web 与 Spring](./spring/) |
| Servlet 与 Servlet 容器 | 理解 Tomcat 如何接收 HTTP 请求、调用 Filter/Servlet，并管理请求线程和 Session | [Servlet 专题](./spring/servlet)、[Spring MVC](./spring/mvc) |
| 数据层与持久层 | 从 JDBC 追到关系型、缓存型与时序数据库，理解 SQL、事务、索引、缓存和数据恢复 | [数据层入口](./data/)、[JDBC](./data/jdbc)、[数据库类型全景](./data/database-types) |
| 工程与分布式 | 管理源代码变更、构建交付、发布回滚、网络入口和跨服务失败恢复 | [学习入口](./engineering/)、[Git](./engineering/git)、[Maven](./engineering/maven)、[Docker](./engineering/docker)、[CI/CD 发布](./engineering/cicd)、[Linux](./engineering/linux)、[Nginx](./engineering/nginx)、[Nacos](./engineering/nacos)、[微服务工具](./engineering/microservice-tools)、[分布式系统](./engineering/distributed)、[设计模式](./engineering/patterns) |
| 项目实战 | 将抽象概念对应到当前仓库里的目录、类、接口和配置 | [项目与微服务地图](./project-map) |

每个专题页都会围绕“解决的问题、关键机制、在当前项目中的位置、排错线索和练习”展开。示例中的账号、Token、密码和连接信息应使用占位符；项目配置是否适用于本地环境需要单独确认。

## 推荐学习顺序

| 阶段 | 学习目标 | 检查自己是否掌握 |
|---|---|---|
| 1. Java 阅读基础 | 看懂类、接口、DTO、集合、异常和泛型 | 能指出一个接口方法的输入、输出和可能异常 |
| 2. 运行时基础 | 理解线程、内存、I/O 和资源生命周期 | 能解释请求并发、超时或文件流问题可能发生在哪一层 |
| 3. Web 服务 | 理解 Controller、依赖注入、拦截器和请求绑定 | 能从 URL 找到 Controller，并区分 query、path 和 body 参数 |
| 4. 持久层与数据 | 理解 Mapper、SQL、事务、分页和缓存 | 能把一个筛选字段从 DTO 追到 SQL 和响应 |
| 5. 工程运行 | 理解 Maven 构建、环境配置、容器与反向代理 | 能区分编译失败、服务未启动、路由失败和业务错误 |
| 6. 分布式协作 | 理解 Gateway、服务发现、Feign、消息和降级 | 能区分浏览器到网关的一跳与服务间调用的下一跳 |
| 7. 项目实战 | 用真实前后端代码验证以上概念 | 能画出完整请求链并为关键行为找到代码或运行证据 |

## 前端开发者的阅读方法

不必先精通 Java 才读业务代码。选一条自己熟悉的页面请求：

1. 找到页面事件和 API wrapper，确认触发条件、URL 和 HTTP 方法。
2. 阅读统一请求层，确认 Token、baseURL、错误和响应解包。
3. 找到后端 Controller，核实参数绑定、权限声明和返回类型。
4. 顺着 Service 到 Mapper 或 Feign Client，辨别业务规则和数据来源。
5. 用 Network、日志、断点或测试验证，区分代码事实与环境假设。

可以从[项目地图](./project-map.md)了解目录，再用[请求调用链](../examples/request-walkthrough.md)看完整实例；遇到接口联调问题时查[响应与参数约定](../api/response-contracts.md)和[联调排错](./debugging.md)。
