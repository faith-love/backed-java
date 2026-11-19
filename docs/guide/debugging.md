# 联调与排错：从可观察证据开始

排错的目标是缩小故障边界。一个请求可能在浏览器、代理、Gateway、认证、Controller、Service、数据库或外部数据源中的任意一层失败；不要一看到 UI 报错就改业务代码。

## 第一步：记录浏览器证据

Network 面板中保留：

- Request URL 与 Method：定位 API wrapper、baseURL、代理和网关路径。
- Query / Payload：确认实际送出的字段和值，而不是只看页面变量。
- Request Headers：核对 Authorization、Content-Type、租户/语言等约定。
- HTTP status 与 Response body：分清传输层错误和业务 code 错误。
- Initiator：确认触发请求的页面/API 函数。
- Timing：区分等待连接、服务处理慢、下载慢。

排查共享环境前应先脱敏 Token、账号、医疗业务数据和连接地址。

## 第二步：分清四类错误

| 错误 | 通常说明 | 优先查哪里 |
|---|---|---|
| 浏览器 Network error / CORS | 浏览器未能读取成功响应，可能是连接或响应头问题 | 请求是否抵达服务、代理/Gateway 日志、CORS 头 |
| HTTP 4xx/5xx | HTTP 层失败 | Gateway/Controller 异常、请求方法、认证、服务可用性 |
| HTTP 200 + 业务 code 失败 | 应用返回了业务错误对象 | response wrapper、拦截器 code 判断、业务错误消息 |
| HTTP 200 + 空数据 | 链路可能成功，但筛选或下游结果为空/被降级 | DTO、数据权限、SQL、数据源、异常降级日志 |

这四种表面现象可能相似，但修复位置完全不同。

## 常见现象的因果链

### 404：先判断在哪个路由层消失

路径可能由 baseURL、前端 API path、Gateway prefix/rewrite、Controller class mapping、method mapping 多段拼接。逐段展开完整路径。如果请求未进入目标服务，检查 Nacos Gateway 配置和注册；若已进入服务才检查 Controller 映射。

### 401 与 403：区分身份和权限

401 通常意味着凭证缺失/无效；403 通常意味着凭证有效但操作被拒绝。确认 Axios 请求拦截器是否加了 Token、网关是否传递、服务端权限注解检查了什么角色/权限码。不要通过移除后端权限来“解决”前端按钮问题。

### 参数错误：比较真实 JSON 与 DTO

JSON key 是大小写敏感的。逐项比较 Network payload、DTO 字段、Java 类型、校验注解和 Controller 的绑定方式。比如前端放 params、后端却使用 @RequestBody，后端就读不到预期字段。

### 列表为空：验证过滤条件

确认分页页码/页长、状态和分类筛选、项目编码、逻辑删除、用户数据权限，以及当前服务连的是哪套数据库。先复现查询参数，再检查 Service query wrapper 和 SQL，不要直接把“空数组”当成 UI bug。

### 图表空白：配置和查询两边都检查

仪表盘图表同时依赖页面 JSON、图表编码、数据集 businessKey、外部数据源连通性、查询参数和结果字段。BaseChartDataService 的特定异常路径可能记录日志并返回空 VO，所以 Network 显示 200 也要查看 dashboard 服务日志。

### 下载/上传失败：文件传输与 JSON 不同

下载时检查 responseType、Content-Disposition、文件名解析和代理响应头；上传时检查 FormData 字段名、multipart boundary 和服务端大小限制。不要让普通 JSON interceptor 把二进制响应当业务对象处理。

## 用源码搜索建立路径

~~~powershell
# 第一步：在管理门户中找到 API 函数及页面调用。
rg -n "componentListQry|dashboard/design/page" E:\p\project\datacenter-web\src

# 第二步：用 Controller 名称和 URL 片段定位后端入口。
rg -n "class DashboardPageController|dashboard/design" E:\p\project\datacenter-cloud\gykj-modules\gykj-dashboard\src\main\java

# 第三步：沿 Service 方法继续找到查询逻辑。
rg -n "getByCategory|class DashboardPageServiceImpl" E:\p\project\datacenter-cloud\gykj-modules\gykj-dashboard\src\main\java
~~~

搜索结果只告诉你“字符串在哪里”，还要确认运行时调用关系和配置是否真的启用了该分支。

## 在 IDE 中从外向内打断点

1. **Controller 入参处**：请求是否到服务、DTO 绑定是否正确。
2. **Service 分支处**：过滤条件和权限上下文是否符合预期。
3. **DAO/数据集执行点**：SQL、数据源名称、分页参数和数据库响应。
4. **异常处理处**：异常是否抛出，是否转空结果或降级。
5. **Controller 返回前**：实际 VO 与序列化字段是否正确。

如果第一个断点不命中，先查网关、服务注册和目标实例；若命中但值错，逐层向内。

## 慢请求排查

用 Timing/后端日志时间戳确认耗时区间。服务端进一步拆分 Controller 总耗时、Feign/HTTP 下游耗时、SQL 耗时和序列化耗时。跨服务日志最好关联 request/trace id；没有关联 ID 时，使用时间、URL 和脱敏参数缩小范围。

不能因为把 timeout 调长就认为问题修好了。要区分慢 SQL、连接池等待、网络延迟、锁等待和大响应序列化。

## 环境安全和配置边界

- bootstrap.yml 的占位符通常要由构建或配置中心解析。
- 开发 profile 也可能指向共享数据库；执行写操作前必须确认目标。
- migration 要确认数据库、备份、顺序、可重跑性和回滚方法。
- 本地构建通过不等于目标服务已经部署，准备 migration 不等于已经执行。
- 改代码前先看 git status，避免覆盖现有工作。

## 形成可复现记录

~~~text
功能/页面：
前端仓库与 API 函数：
脱敏后的 URL、Method、参数摘要：
HTTP status / 业务 code：
预期结果与实际结果：
请求是否到 Gateway/Controller：
已检查的 Service / Mapper / 数据源：
关键日志时间和关联 ID：
复现步骤：
~~~

有这些证据，协作者可以从同一层开始复现，不必靠猜测 UI 截图。
