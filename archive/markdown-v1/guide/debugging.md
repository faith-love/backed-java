# 03｜本地联调与排错

## 先看实际请求

打开浏览器开发者工具的 Network，记录：

- Request URL：包括 baseURL 和 API 相对路径。
- Request Method：GET、POST、PUT 等。
- Query String Parameters 或 Request Payload。
- Authorization 是否存在，是否为过期 Token。
- HTTP status、业务 code、response body。
- Initiator：哪个页面/API 文件发起请求。

管理门户的统一请求层在 E:\p\project\datacenter-web\src\utils\request.js。请求拦截器加 Token，响应拦截器处理 401、业务错误和文件流。

## 常见现象与排查方向

| 现象 | 优先检查 |
|---|---|
| 404 | 前端 API path、网关前缀、Nacos 中的 Gateway route、path rewrite、Controller 类级与方法级路径 |
| 401 | Token 是否存在/过期、登录状态、认证服务、网关是否传递 Authorization |
| 403 | 前端按钮权限、后端接口权限注解、角色配置、数据权限 |
| 400 / 参数校验错误 | JSON 字段名、字段类型、必填规则、@RequestBody 与 params/data 的使用 |
| 业务 code 失败但 HTTP 200 | 统一响应对象、响应拦截器读取的 code/msg、后端日志 |
| 列表为空 | 查询条件、项目/角色数据范围、数据库环境、逻辑删除或状态过滤 |
| 图表空白 | 图表配置、数据集 businessKey、数据源连接、查询参数、BaseChartDataService 日志 |
| 浏览器跨域错误 | devServer proxy、网关 CORS、响应头；先确认是浏览器 CORS 而非服务端 4xx |
| 下载文件异常 | responseType、Content-Disposition、文件流拦截分支、文件大小限制 |

## 从前端定位后端类

PowerShell 示例：

    # 在门户前端里定位 API 路径。
    rg -n "dashboard/design/page" E:\p\project\datacenter-web\src

    # 再去后端搜索 Controller 中的完整路径片段。
    rg -n "dashboard/design|class DashboardPageController" E:\p\project\datacenter-cloud\gykj-modules\gykj-dashboard\src\main\java

    # 沿 Controller 调用的方法继续找 Service 实现。
    rg -n "getByCategory|class DashboardPageServiceImpl" E:\p\project\datacenter-cloud\gykj-modules\gykj-dashboard\src\main\java

把前端路径拆成三段检查：

1. apiBaseUrl：开发环境可能由代理配置提供。
2. API wrapper 的 url：例如以 /dashboard 开头。
3. Controller 的类级路径 + 方法级路径：例如 /dashboard/design + /page。

服务内部路径与浏览器完整路径可能不同，因为 Gateway 会做路由或路径改写。最终规则要查看当前环境 Nacos 配置，不能只根据 URL 猜。

## 在 IDE 中下断点

建议从外向内设置：

1. Controller 方法入口：确认请求是否到达后端、绑定后的 DTO 是什么。
2. Service 关键分支：确认业务条件和查询范围。
3. Mapper/数据集查询点：确认 SQL、数据源和查询参数。
4. Controller 返回前：确认结果包装和序列化前数据。

如果 Controller 断点没有命中，先看 URL、网关、服务注册和服务日志；如果命中了但结果不对，再追 Service 和数据访问。

## 环境配置和安全

- bootstrap.yml 中的占位符通常依赖构建或配置中心。
- application-dev.yml 可能保留服务器地址或账号信息。不要复制到聊天、仓库或共享笔记；也不要在未确认的情况下运行会连生产或共享数据库的命令。
- 运行 SQL migration 前先确认目标库、备份、执行顺序和回滚方式。
- 只读查代码不会验证目标环境部署状态。构建成功也不等于服务已经部署或数据库迁移已执行。
- 修改前先运行 git status，保留已有本地改动。

## 有效的排错记录模板

    功能：
    前端仓库 / 页面：
    API 文件与函数：
    Request URL / Method：
    脱敏后的参数摘要：
    HTTP status / 业务 code：
    后端 Controller：
    已检查的 Service / Mapper：
    复现步骤：
    预期行为：
    实际行为：

贴日志和请求时先去掉 Token、密码、用户个人信息、医院业务数据和真实数据库地址。
