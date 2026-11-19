# 02｜前端开发者需要懂的后端概念

## 1. Controller：HTTP 接口入口

Controller 把 HTTP 方法和 URL 映射到 Java 方法。前端最熟悉的 API contract 在这里体现：路径、方法、参数位置、响应类型和权限注解。

    /**
     * 一个带完整注释的接口结构示意。
     * 实际业务代码请以对应仓库文件为准。
     */
    @RestController
    @RequestMapping("/example")
    public class ExampleController {

        /**
         * GET /example/detail?id=123
         * @RequestParam 从 URL 查询参数取值。
         */
        @GetMapping("/detail")
        public Result detail(@RequestParam("id") String id) {
            // Controller 通常委托给 Service，不应在这里堆大量业务逻辑。
            ExampleVO result = exampleService.getDetail(id);

            // 将业务结果包在项目约定的响应对象中返回。
            return Result.success(result);
        }

        /**
         * POST /example/save
         * @RequestBody 从 JSON 请求体反序列化对象。
         */
        @PostMapping("/save")
        public Result save(@RequestBody ExampleSaveDTO request) {
            // 将参数交给 Service 处理，便于复用和测试。
            exampleService.save(request);

            // 返回明确的成功响应。
            return Result.success();
        }
    }

常见参数绑定：

- @RequestParam：URL 查询参数，如 ?pageNum=1。
- @PathVariable：路径中的变量，如 /user/123。
- @RequestBody：JSON 请求体。
- MultipartFile：上传文件表单。
- Java Bean：Spring 会按参数名绑定 GET 查询参数。

## 2. DTO、Entity、VO：看起来像对象，用途不同

| 名称 | 主要用途 | 你可以类比 |
|---|---|---|
| DTO | 接收前端请求或在服务间传数据 | 表单 payload / API 参数类型 |
| Entity | 数据库表映射对象 | ORM model |
| VO | 返回给接口消费者的视图对象 | API response type |
| Domain | 领域对象或共享业务对象 | 业务核心模型，具体含义看项目 |

不要因为字段相似就假设这些类可以互换。DTO 可能有校验注解；Entity 可能带 MyBatis 映射；VO 可能隐藏内部字段或拼装多个来源的数据。

## 3. Service：业务规则和流程编排

Service 负责把多个动作组成一个业务操作，例如校验权限、查询配置、调用其他服务、处理事务并返回结果。接口常以 I...Service 命名，实现类常在 service/impl 目录。

阅读 Service 时重点问：

- 输入来自哪里、哪些条件可能为空？
- 是否调用其他 Service、Feign Client 或外部数据源？
- 是否有事务注解？
- 异常是抛出、转换成统一响应，还是被记录后降级为空结果？
- 分页和排序在哪里发生？

## 4. Mapper / DAO：数据访问层

MyBatis-Plus 的 BaseMapper 能提供常见 CRUD。自定义 SQL 常见形式：

- Mapper 接口上的注解 SQL。
- resources/mapper 下与接口关联的 XML。
- Service 使用 LambdaQueryWrapper 等对象动态组条件。

调试 SQL 时，不要只找到 Mapper 文件就停止；要确认 Service 调用的具体方法、参数值、数据源选择和逻辑删除/数据权限拦截。

## 5. Entity 与 JSON 字段

仪表盘 PageEntity.config 是一个复杂的页面配置字段。它使用自定义 type handler，把 JSON 文本转为 BasePageDTO 子类。这种设计让后端能把图表布局和组件配置整体保存，但也意味着前后端字段、类型标记和反序列化规则必须匹配。

当页面能保存、刷新后却丢失配置，优先检查：

1. 前端保存 payload 是否包含该字段。
2. DTO 有没有对应字段、Jackson 配置是否能反序列化。
3. Entity 的字段映射/type handler 是否生效。
4. 数据库列内容是否正确。
5. 返回响应时是否把配置字段转回 JSON。

## 6. 响应包装和 HTTP 状态

此仓库不同模块可能使用不同响应类，例如 Result、R、MixinsResp。前端 request interceptor 可能还会统一读取 code、msg，并把 res.data 返回。因此：

- HTTP 200 不一定代表业务成功。
- 业务 code 不一定等同 HTTP status。
- 页面拿到的对象可能已经被拦截器解包。
- 文件下载、blob 和 arraybuffer 响应通常走特殊分支。

接口联调要以真实 Network response 和前端拦截器行为为准。

## 7. 权限分两层看

- 前端按钮或菜单权限主要决定 UI 是否展示入口，属于体验控制。
- 后端 Controller/安全组件的权限检查才是接口访问控制。
- Token 证明用户身份；角色、权限码和数据范围决定能否操作哪些功能或数据。
- 需要分别检查前端动态路由/按钮权限与后端注解、Security 配置。

例如 DashboardPageController 上能看到 @ApiPermission，并在方法上声明 VIEW、ADD、UPDATE 等权限。前端隐藏按钮不代表接口自动安全；服务端仍需验证权限。

## 8. 微服务调用与 Feign

服务之间可能通过 gykj-api 中的契约接口和 OpenFeign 调用。接口模块通常提供 Feign Client、DTO 和 fallback factory，业务模块实现被调用能力。

阅读时从注入的 Client 找到方法，再搜索方法路径和服务名。不要把 Feign 调用误认为浏览器直接请求了那个服务；浏览器一般只知道网关地址。

## 9. 常用注解速查

| 注解 | 直观作用 |
|---|---|
| @RestController | 声明返回 JSON 的 Web Controller |
| @RequestMapping | 定义类级或方法级 URL 前缀 |
| @GetMapping / @PostMapping | 绑定 HTTP 方法和路径 |
| @RequestBody | 将请求 JSON 转为 Java 对象 |
| @Validated / @Valid | 触发参数校验 |
| @Service | 将类注册为 Spring 服务 Bean |
| @Resource / @Autowired | 注入其他 Bean |
| @Transactional | 声明事务边界，具体效果还受调用方式和配置影响 |
| @MapperScan | 告诉 MyBatis 扫描 Mapper 接口 |
| @FeignClient | 声明服务间 HTTP 客户端 |
