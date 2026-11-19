# Spring Boot 配置

Spring Boot 解决的是“Spring 应用需要手动拼装哪些对象、Web 容器、配置和启动步骤”的问题。它不是另一套 IoC 容器，而是用 starter、自动配置、外部化配置和启动器把常见组合变成可运行的应用。

本章以项目实际版本 **Spring Boot 2.7.18、Java 11** 为阅读基线。Spring Boot 3 使用 Jakarta 命名空间和更高 Java 版本，复制代码前先核对项目版本。

## 从前端启动脚本理解 Spring Boot

前端执行 `npm run dev` 时，构建工具读取配置、启动开发服务器并注入环境变量。Spring Boot 的 `main` 方法也承担类似入口职责，但还要创建 `ApplicationContext`、实例化 Bean、绑定配置、启动内嵌 Web 容器并执行启动检查。

~~~text
java -jar app.jar
  -> SpringApplication.run(...)
  -> 读取配置源与 profile
  -> 创建 ApplicationContext
  -> 组件扫描、自动配置、Bean 初始化
  -> 启动 Tomcat/Servlet 或其他 Web 运行时
  -> 执行 Runner
  -> 接受 HTTP 请求
~~~

`run` 返回后应用才进入可服务阶段。某个 Bean 初始化成功，不等于数据库、注册中心或外部服务一定可用；启动探针和业务健康检查要分开设计。

## 知识点地图：把 Boot 当成一条启动流水线

| 知识点 | 解决的问题 | 阅读证据 | 最容易混淆的边界 |
|---|---|---|---|
| 启动器与依赖 | 快速组合 Web、数据源、监控等能力 | `pom.xml`、Maven 依赖树 | 依赖存在不等于功能已启用 |
| 自动配置 | 根据条件创建默认 Bean | 条件注解、启动日志、条件报告 | 自动配置不是无条件覆盖自定义 Bean |
| 组件扫描 | 找到 Controller、Service、Configuration | 启动类包和 `scanBasePackages` | `@Mapper`、Feign 可能由独立注册器处理 |
| 配置绑定 | 把字符串配置变成有类型对象 | prefix、getter/setter、校验结果 | 配置读取成功不等于下游连接成功 |
| Profile/配置中心 | 为环境选择不同参数 | active profile、namespace、group、dataId | profile 是选择开关，不是安全边界 |
| Actuator/探针 | 观察和接收流量前判断 | endpoint 暴露规则、liveness/readiness | 健康接口公开过多会泄露内部信息 |

学习时按这条顺序追：**依赖是否进入 → 启动类是否扫描 → 自动配置条件是否满足 → 配置是否绑定 → Bean 是否创建 → Web 容器是否就绪 → 网关是否把流量送进来**。这样比看到异常后不断添加注解更容易定位根因。

## 重难点一：自动配置为什么“没有写代码却有 Bean”

自动配置通常由一组条件共同决定。以一个 HTTP 客户端为例，可能需要 classpath 有某个类、某个属性为 true、容器里还没有同类型 Bean，最后才执行配置方法：

~~~text
依赖存在（@ConditionalOnClass）
  + 属性打开（@ConditionalOnProperty）
  + 当前没有用户 Bean（@ConditionalOnMissingBean）
  -> 自动配置类生效
  -> 创建默认 Bean
  -> 其他 Bean 注入这个实例
~~~

**排错方法：**

1. 启动参数加 `--debug`，查看 `CONDITIONS EVALUATION REPORT` 中 matched 和 did not match 的原因。
2. 用 `mvn dependency:tree` 确认类来自哪个模块和版本，避免“本地 IDE 有、打包制品没有”。
3. 搜索是否存在同类型自定义 Bean；它可能使 `@ConditionalOnMissingBean` 的默认 Bean 不再创建。
4. 检查条件属性实际值，而不是只看仓库中的 `application.yml`。

不要为了让启动成功就排除自动配置。排除可能只是把失败推迟到第一次请求；先看条件不满足的原因，再决定是补依赖、改配置、提供自定义 Bean，还是明确排除某项能力。

## 重难点二：配置绑定是运行时契约，不是字符串替换

配置绑定要同时考虑名称、类型、默认值、校验和缺失策略：

| 情况 | 例子 | 建议 |
|---|---|---|
| 名称转换 | `connect-timeout-ms` → `connectTimeoutMs` | 使用规范 kebab-case，避免同一键多种拼法 |
| 类型转换 | `2s`、`10MB`、数字字符串 | 用 `Duration`、`DataSize` 等类型表达单位 |
| 缺失值 | 地址为空、超时为 0 | 对必填字段校验，让启动快速失败 |
| 默认值 | 本地 demo 地址 | 只给安全的本地默认值，生产密钥不得有可用默认值 |
| 动态刷新 | 配置中心推送新值 | 先确认哪些 Bean 支持刷新，避免只刷新一半造成不一致 |

~~~java
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** Boot 2.7 + Java 11 集成示意：用类型表达网络客户端配置。 */
@Validated
@ConfigurationProperties(prefix = "remote.catalog")
public class CatalogProperties {
    @NotBlank
    private String baseUrl;

    @NotNull
    private Duration callTimeout = Duration.ofSeconds(3);

    // Boot 2.7 的 JavaBean 绑定需要 getter/setter；真实工程还要注册该类。
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getCallTimeout() { return callTimeout; }
    public void setCallTimeout(Duration callTimeout) { this.callTimeout = callTimeout; }
}
~~~

`Duration` 能避免“3 到底是毫秒还是秒”的误读；配置绑定成功后仍要在客户端构造处把它真正传给连接/读取/调用超时。配置类不应直接作为 Controller 返回对象，以免把内部地址、凭证相关字段暴露给前端。

## Starter 与自动配置：解决什么问题

starter 是依赖组合，自动配置是条件化的 Bean 创建。它们减少样板代码，但会让行为看起来“没有代码却出现了”。

| 机制 | 解决的问题 | 判断依据 | 常见误解 |
|---|---|---|---|
| starter | 一次引入一类能力的常见依赖 | `pom.xml`、依赖树 | 引入 starter 就等于所有功能都安全启用 |
| `@SpringBootApplication` | 组合配置、自动配置和扫描 | 启动类包路径和注解属性 | 会扫描整个磁盘 |
| 自动配置 | 根据 classpath、属性和已有 Bean 提供默认对象 | 条件注解、启动日志 | 默认 Bean 永远优先于自定义 Bean |
| `@ConfigurationProperties` | 把配置绑定为有类型对象 | prefix、字段、校验 | 配置字符串写错会在编译期发现 |
| Actuator | 暴露诊断、健康和指标 | endpoint 暴露与访问控制 | 暴露 `/env` 不会泄露秘密 |

排查“为什么没有这个 Bean”时检查：依赖是否进入当前模块、扫描范围、条件属性、profile、冲突 Bean 和条件评估日志。不要只添加 `@Autowired` 反复试错。

## 启动类与扫描边界

~~~java
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 2.7 + Java 11 的启动类集成示意。 */
@SpringBootApplication(scanBasePackages = "com.example.dashboard")
@MapperScan("com.example.dashboard.mapper")
public class DashboardApplication {
    public static void main(String[] args) {
        // 创建容器、加载配置、初始化 Bean，并启动 Web 服务器。
        // 生产 profile 应由部署参数选择，不要在代码中写死 prod。
        SpringApplication.run(DashboardApplication.class, args);
    }
}
~~~

启动类包路径通常决定默认扫描根。显式 `scanBasePackages` 能解决多模块包不在同一根的问题，但扫描过宽会把测试 Bean、重复配置或不该启动的模块一起装配。`@MapperScan` 只发现 Mapper，不代表数据库连接和 SQL 已经正确。

结合当前项目可先读 `gykj-modules/gykj-dashboard` 的 `DashboardApplication`，再核对 Controller、Service、Mapper 的包名。Bean 能否注入必须以扫描范围和条件为证据。

## 配置来源：同一个键最终取谁

配置可能来自默认值、`application.yml`、profile 文件、环境变量、JVM 系统属性、命令行参数和配置中心。优先级会受 Spring Boot/Spring Cloud 版本及 bootstrap/config data 机制影响；下面是排查模型，不替代项目版本文档：

~~~text
默认值 -> application.yml -> application-{profile}.yml
       -> 环境变量/JVM 属性 -> 命令行参数
       -> 配置中心远程值（按当前接入方式确认）
~~~

同一键出现多次时，记录实际启动命令、active profile、namespace/group/dataId 和最终日志。不要看到仓库值就假定它是运行时值。

### 用类型绑定表达配置契约

~~~java
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 配置绑定示意；实际工程需要注册该 properties 类。 */
@Validated
@ConfigurationProperties(prefix = "payment.gateway")
public class PaymentGatewayProperties {
    @NotBlank
    private String baseUrl;
    @Positive
    private int connectTimeoutMs = 2_000;

    // Boot 2.7 JavaBean 绑定需要 getter/setter；不要把整个对象返回给前端。
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
}
~~~

`@Value` 适合少量简单值；有层级且要校验的一组配置优先使用 `@ConfigurationProperties`。绑定失败应让应用明确失败，避免空地址或 0 超时进入运行时。Boot 2.7 需通过 `@EnableConfigurationProperties` 或属性扫描注册该类。

~~~yaml
payment:
  gateway:
    # 真实 URL 由部署环境提供，示例不包含商户地址或密钥。
    base-url: ${PAYMENT_GATEWAY_URL:https://sandbox.example.invalid}
    connect-timeout-ms: ${PAYMENT_CONNECT_TIMEOUT_MS:2000}
~~~

占位符默认值只适合本地安全环境。生产不应给数据库密码、签名私钥写可用默认值。

## Profile：环境隔离，不是安全隔离

`local`、`dev`、`test`、`prod` 可以切换端口、日志和下游地址。profile 名称本身不是权限，也不能阻止用户通过命令行激活另一个 profile。

~~~yaml
# application.yml：共享安全默认值
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
server:
  shutdown: graceful
---
spring:
  config:
    activate:
      on-profile: local
server:
  port: 8080
logging:
  level:
    root: INFO
~~~

不要把生产地址写进本地默认值或注释；密钥放环境变量、密钥服务或受控配置中心，并限制谁能读取配置响应。

## 重难点三：配置优先级与 Profile 不能靠猜

同一个键可能在多个来源出现。排查时建立一份“来源表”，记录来源、实际值是否敏感、加载时间和覆盖关系：

~~~text
代码默认值
  -> application.yml
  -> application-{activeProfile}.yml
  -> 环境变量 / JVM -D 属性
  -> 命令行 --key=value
  -> Spring Cloud/Nacos 等远程配置（按接入版本确认）
~~~

不同 Spring Cloud 配置加载机制可能改变远程值何时进入环境，不能把上图当作所有版本的绝对排序。用启动日志、配置客户端日志和实际 `Environment` 证据确认。启动命令也属于配置来源：

~~~powershell
# 示例：只修改本次进程的 profile 和端口，不写入仓库文件。
java -jar app.jar --spring.profiles.active=dev --server.port=18080
~~~

Profile 只决定加载哪组配置。它不隔离数据库权限、不阻止用户手动激活 `prod`，也不自动隐藏 Actuator。生产环境要在部署平台限制启动参数、密钥读取权限和网络出口。

## 重难点四：Nacos 让“启动问题”变成多段链路

当前项目使用 Nacos 相关依赖时，应把问题拆成四段：

1. **连接**：服务是否能解析 Nacos 地址、建立网络连接并通过认证。
2. **定位**：namespace、group、dataId、服务名是否和环境一致。
3. **内容**：返回内容是否为当前 profile 的配置，格式是否能被 Boot 绑定。
4. **时机**：配置是在容器创建前加载，还是应用启动后刷新；失败时是 fail-fast 还是使用本地默认值。

仅看到“服务注册成功”不能证明配置读取成功；注册与配置是两条能力。修改 Nacos 后还要确认哪些 Bean 已经读取旧值，哪些组件支持动态刷新。涉及数据库地址、权限和支付密钥时，优先通过灰度环境验证，不在生产直接改值试错。

## 重难点五：liveness、readiness 和业务健康不是一回事

- **liveness**：进程是否仍能被探针调用；把不可恢复的下游故障塞进 liveness 可能造成重启循环。
- **readiness**：当前实例是否接收流量；启动阶段数据库连接未就绪时，可以暂时不接流量。
- **业务健康**：某个功能是否可用，例如报表查询、文件存储或支付渠道；它不一定应该影响整个实例的 readiness。

健康检查应返回稳定、脱敏的状态，不输出内部地址、连接串和异常堆栈。把 Actuator 管理端口置于受控网络，并以认证/网关策略保护。`/env`、`/beans`、`/configprops` 不应因为排错方便就对公网开放。

## 当前项目的 Nacos/bootstrap 配置怎么读

项目根 POM 固定 Spring Boot 2.7.18、Spring Cloud 2021.0.8 和 Spring Cloud Alibaba 2021.0.5.0；Dashboard 的 `bootstrap.yml` 使用服务名和 Nacos 占位符。阅读时记录：

1. 服务名、端口和 profile 从哪里来？
2. Nacos server、namespace、group、dataId 是否由环境注入？
3. 配置中心不可用时应用失败、使用本地值，还是启动后才报错？
4. 哪些来源覆盖仓库文件，日志是否打印敏感连接串？
5. 多实例是否读取同一 namespace，灰度环境是否使用不同 group？

配置中心解决集中管理和分发，增加了启动依赖、权限和排错路径。不要把“在 Nacos”当成“配置一定正确”。

## 配置安全与 Actuator 边界

- Git 只提交变量名、占位符和非敏感默认值；历史泄露密钥也要撤销并轮换。
- 日志、异常、Actuator、监控标签和前端错误提示都不能打印密码、Token、完整连接串或私钥。
- `/env`、`/configprops`、`/beans` 等端点应只在受控管理面开放并脱敏；公开面通常只需 `health,info`。
- 环境变量不是天然安全：其他进程、崩溃转储和错误日志仍可能接触它；高敏感密钥使用受控密钥服务。
- 配置中心客户端使用最小读取权限、TLS、namespace/group 隔离和轮换方案。

~~~yaml
spring:
  datasource:
    # 只展示变量名；真实值由部署环境注入。
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
management:
  endpoints:
    web:
      # 生产按实际运维需求暴露，不能为了排错直接 include '*'.
      exposure:
        include: health,info
~~~

## 启动诊断与健康检查

| 现象 | 先看什么 | 不要直接得出的结论 |
|---|---|---|
| 端口绑定失败 | 端口占用、配置来源、容器映射 | 不一定是 Controller 路由问题 |
| Bean 创建失败 | 根因异常、条件评估、依赖版本 | 不要只看最外层异常 |
| 数据库连接失败 | URL、DNS、凭证、网络、连接池 | 不要把密码写入日志重试 |
| 进程已启动但流量失败 | readiness、Gateway、路由、认证过滤器 | 存活不等于业务可用 |

liveness 表示进程还能工作，readiness 表示是否准备接流量；数据库是否影响 readiness 要按业务决定。健康端点返回下游错误时避免泄露内部地址和凭证。

排查顺序：实际 JVM 参数 → profile → 本地配置 → 配置中心 → 自动配置条件 → 启动根因 → readiness → Gateway/客户端请求。每一步保存非敏感证据。

## 常见误区与练习

- `@SpringBootApplication` 不是“扫描整个项目”，扫描根和显式配置决定边界。
- `application.yml` 不是唯一来源；环境变量和命令行可能覆盖它。
- 配置绑定成功不代表下游连接成功；启动验证和业务健康检查要分开。
- Actuator 不是调试开关；暴露管理信息会扩大攻击面。
- 改配置后先确认构建产物和启动命令，不要只重启 IDE 中一个模块。

练习：

1. 找到 Dashboard 启动类，画出扫描包和 Mapper 扫描范围。
2. 对一个配置键列出仓库、环境变量、JVM 参数和 Nacos 的可能来源。
3. 将数据库密码改为占位符，检查日志、异常和 Actuator 是否仍可能泄露。
4. 为一个外部依赖设计 liveness/readiness 规则，说明依赖不可用时是否阻止流量。

官方参考：[Externalized Configuration](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/features.html#features.external-config)、[Configuration Properties](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/features.html#features.external-config.typesafe-configuration-properties)、[Actuator](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/actuator.html)。

下一步：[认证与授权](./security.md) 或 [IoC 与依赖注入](./ioc.md)。

## 重难点练习：从能启动到能证明

### 基础

1. 找到 Dashboard 启动类，画出扫描包、Mapper 扫描范围和 Web 容器启动位置。
2. 对一个配置键列出仓库、环境变量、JVM 参数和 Nacos 的可能来源，并标注哪一项最终覆盖。

### 进阶

3. 让一个自动配置条件不满足，使用 `--debug` 找到 did not match 原因，再恢复依赖或属性。
4. 将数据库密码改为占位符，检查日志、异常和 Actuator 是否仍可能泄露；记录每个证据位置。
5. 为一个外部依赖设计 liveness/readiness 规则，说明依赖不可用时是否阻止流量，以及为什么。

### 排错

6. 故意让 `Duration` 配置使用错误单位，说明为什么编译不会报错、启动校验和运行日志分别能发现什么。
7. 模拟 Nacos 返回错误 profile，按连接、定位、内容、时机四段列出证据和恢复动作。
