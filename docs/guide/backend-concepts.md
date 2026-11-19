# 后端核心概念：把 API Contract 追到数据层

前端开发者已经熟悉 HTTP contract。本章将 Java 后端的层映射到熟悉的请求和响应，并说明每一层解决什么问题。

## 一条请求的层次

~~~
HTTP 请求
  -> Controller：解析 Web 协议和参数
  -> DTO：定义请求数据形状
  -> Service：执行业务规则和编排
  -> Mapper/Client：访问数据库或其他服务
  -> Entity/VO：持久化模型与返回模型
  -> HTTP 响应
~~~

分层的价值是把“如何接 HTTP”“业务该怎么做”“数据怎样取”分开，避免一个 Controller 同时校验、拼 SQL、调外部接口、拼 JSON，最终无法单独测试。

## Controller：解决 HTTP 如何落到代码

Controller 使用映射注解定义 URL 和 HTTP 方法，还声明参数来源与返回对象。它通常做：

- 参数绑定：把 query/path/body 映射成 Java 类型。
- 参数校验：检查必填、格式、长度和组合条件。
- 权限声明：让安全组件知道哪些接口需要什么权限。
- 调用 Service：把业务处理委托出去。
- 响应包装：遵守模块约定的响应对象。

Controller 不应把复杂规则都写在接口方法中，否则复用和单元测试会变困难。

## 参数来源：解决数据从请求哪部分读取

| HTTP 内容 | Java 写法 | 前端 Axios 常见写法 | 解决什么问题 |
|---|---|---|---|
| URL 查询字符串 | @RequestParam / Java Bean | params | 筛选、排序、分页等可分享的查询条件 |
| URL 路径段 | @PathVariable | 拼 URL path | 对指定资源操作，如按 id 查询 |
| JSON body | @RequestBody DTO | data | 复杂查询、创建或更新对象 |
| multipart 表单 | MultipartFile/表单字段 | FormData | 上传文件与文件元信息 |

路径和值都要有编码、长度和权限校验。不要仅靠前端组件验证；请求可以被脚本直接构造。

## DTO、Entity、VO：解决模型混用

- **DTO**定义接口输入，便于校验、默认值和版本演进。
- **Entity**描述数据库记录与字段映射，受表结构和 ORM 影响。
- **VO**描述页面/调用方真正需要的返回字段。
- **Domain**表达业务概念，命名和范围要看项目约定。

将请求 DTO 直接持久化，可能让前端控制本不应修改的数据库字段；直接返回 Entity，可能泄漏内部字段、造成表结构与 API 强耦合。因此常用显式转换或 Bean 映射。

## Service：解决业务规则散落

Service 通常承载校验后的业务流程。例如“修改用户”可能需要检查权限、确认对象存在、验证状态、更新记录并写审计日志。Service 是跨 Controller 复用规则、编写业务单测的主要位置。

追 Service 时依次问：

1. 输入字段如何影响业务分支？
2. 调用了哪些 Mapper、Feign Client 或数据集执行器？
3. 事务边界在哪里？
4. 异常如何传播/降级？
5. 是否返回空列表或默认对象掩盖了下游失败？

## Mapper/DAO：解决对象操作如何变成 SQL

MyBatis Mapper 将 Java 方法与 SQL 关联。MyBatis-Plus 的 BaseMapper 提供常见 CRUD；复杂查询可能在 XML、注解或 QueryWrapper 中。

DAO 查询本身正确不代表业务正确。还需要看租户/数据权限、逻辑删除、动态数据源、排序和分页是否改变了最终结果。

## 响应对象：解决响应格式的一致性

当前项目模块会使用 Result、R、MixinsResp 等不同响应封装。封装可以统一 code、message、data、分页信息等，但每个模块契约可能不完全一致。

完整检查要跨三处：

1. 后端方法的泛型返回类型。
2. HTTP Network 中实际 JSON。
3. 前端 response interceptor 对 code 的判断和解包规则。

HTTP 状态 200 表示 HTTP 层成功，不保证业务 code 成功；下载接口还可能返回 blob，而不是 JSON。

## JSON 配置与 TypeHandler：解决复杂页面配置的存储

仪表盘页面的图表和布局结构复杂，不适合拆成大量固定字段；项目将页面配置作为 JSON 存储，并通过 type handler 在数据库 JSON/文本与 Java DTO 之间转换。

出现“保存成功但刷新丢字段”时，沿序列化边界逐层检查：

1. 浏览器请求 body 是否含该字段，命名是否正确。
2. 请求 DTO 是否声明字段，JSON 反序列化是否接收它。
3. type handler/子类类型识别是否将它保存。
4. 数据库原始配置是否包含字段。
5. 查询时能否反序列化并在 VO 中返回。
6. 前端组件是否读取了正确层级。

这类问题往往不是单纯“数据库没保存”，而可能发生在任一转换边界。

## 权限：解决越权访问

前端权限控制解决显示体验，服务端授权解决绕过 UI 后的直接调用。Token 通常表示身份；角色/权限码决定功能访问，数据权限进一步限定记录范围。

检查权限问题时区分：

- 401：通常是缺失或失效身份凭证。
- 403：通常是身份存在但无操作权限。
- 返回空数据：可能是数据范围过滤，不一定是 SQL 没查到。
- 前端按钮消失：不证明后端接口已经安全。

## Feign 与服务间 API：解决服务地址和契约耦合

gykj-api 中的契约接口可供其他微服务用 Feign 调用。调用方不直接依赖被调服务的实现代码，而依赖接口、DTO 和服务名。Fallback factory 提供远程服务失败时的回退行为。

浏览器一般只访问 Gateway；如果业务 Service 注入了 Remote...Service/Feign client，那是后端服务到服务的第二跳。

## 事务：解决多次写库的部分成功

事务让一组数据库操作在满足条件时一起提交或回滚。例如订单和明细必须同时保存，否则数据不完整。读代码时要确认：

- @Transactional 标在什么方法/类上。
- 异常是否真的向事务代理抛出。
- 是否跨线程或跨服务，单机事务还覆盖不到另一个服务。
- 是否存在外部 HTTP/消息操作导致数据库回滚但外部副作用已发生。

事务是边界设计，不是“加了注解就所有操作都原子”。

## 用完整注释示例理解职责

~~~java
// 声明一个返回 JSON 的接口控制器。
@RestController
// 类级路径参与最终 URL 拼接。
@RequestMapping("/example")
public class ExampleController {
    // 让 Spring 注入业务服务，Controller 不负责自己创建实现类。
    private final ExampleService service;

    // 构造器注入必需依赖，便于测试时提供替身。
    public ExampleController(ExampleService service) {
        this.service = service;
    }

    // GET /example/detail?id=abc：查询条件来自 URL。
    @GetMapping("/detail")
    public ExampleView detail(
            // 将 query 参数 id 绑定为 Java String。
            @RequestParam("id") String id) {
        // 业务规则由 Service 执行，返回专用于接口的 View。
        return service.getDetail(id);
    }

    // POST /example：JSON body 由框架反序列化为 DTO。
    @PostMapping
    public ExampleView create(
            // DTO 隔离外部请求结构与数据库实体结构。
            @Valid @RequestBody CreateExampleRequest request) {
        // Service 校验业务规则并完成写入。
        return service.create(request);
    }
}
~~~

## 建议练习

从仪表盘列表 API 开始，分别画出 URL、DTO、Controller、Service、DAO、Entity 和返回 JSON。每一层都写一句“它解决什么问题”，这比背注解更容易建立完整心智模型。
