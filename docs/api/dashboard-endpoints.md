# 仪表盘 API：契约、调用方与服务端职责

本页用两个接口示范如何阅读 API contract。浏览器 URL 中的网关前缀如何映射到服务内部路径，必须以当前环境 Gateway/Nacos 配置为准。

## 仪表盘页面列表

| 契约项 | 值 |
|---|---|
| 前端 API | datacenter-web/src/api/componentList/index.js 的 componentListQry |
| 浏览器 URL | GET /dashboard/dashboard/design/page |
| 后端映射 | DashboardPageController：/dashboard/design + /page |
| 输入 | DashboardSearchDTO，由 query 参数绑定 |
| 业务层 | IDashboardPageService.getByCategory |
| 返回 | `MixinsResp<PageVO<PageEntity>>` |

### 这个接口解决什么问题

前端需要按页显示已设计的仪表盘/组件。分页和分类过滤放在服务端可以减少一次传输的数据量，并且让不同调用方共享相同过滤规则。权限和项目范围也可在服务端一致执行。

### 沿链阅读

1. componentListQry(params)：明确 GET 与 params 的使用方式。
2. DashboardPageController.page：确认参数名、权限注解、响应包装。
3. getByCategory：检查分类、状态、项目范围、排序和分页条件。
4. IDashboardPageDao 与 PageEntity：确认实体映射及分页实现。
5. 浏览器 Network：验证前端真实字段是否符合 DTO。

分页不要只看 UI 上的页码。确认第一页的起始值、每页数量、总记录数以及排序稳定性，否则翻页可能重复/漏数据。

## 图表数据

| 契约项 | 值 |
|---|---|
| 前端 API | datacenter-web/src/api/componentList/index.js 的 getChatInfo |
| 浏览器 URL | POST /dashboard/dashboard/chart/data/list |
| 后端映射 | ChartDataController：/dashboard/chart/data + /list |
| 输入 | ChartDataSearchDTO JSON body |
| 配置查找 | pageCode 定位页面，chartCode 定位图表 |
| 数据执行 | BaseChartDataService 与数据集服务 |
| 返回 | `R<ChartDataVO>` |

### 这个接口解决什么问题

仪表盘页面保存的是图表描述与数据集配置。浏览器运行时需要后端将页面编码和图表编码对应到保存的配置，再用数据集配置读取实际数据。这样前端设计器不用把数据库连接和业务 SQL 暴露到浏览器。

### 典型数据流

~~~
pageCode
  -> 查询 PageEntity
  -> 反序列化 config
  -> 在 chartList 按 chartCode 找 Chart
  -> 从 Chart.dataSource 读取 businessKey/参数
  -> 数据集服务执行查询
  -> ChartDataVO
~~~

如果配置没有匹配图表、数据源类型不受支持或业务 key 为空，某些路径可能返回模拟/空数据；要结合当前代码分支和服务日志确认。

## 页面 JSON 配置为何重要

PageEntity.config 由 TypeHandler 在数据库 JSON 与 BasePageDTO 子类间转换。它解决复杂页面配置的灵活存储，但也建立了严格的序列化契约：

- 前端字段名、大小写和嵌套层级需要兼容后端 DTO。
- 类型标记和子类选择会决定反序列化到哪个配置类。
- 保存与查询两条方向都要验证，单向成功不足以说明配置可往返。
- 大 JSON 需要关注日志脱敏、大小限制和版本兼容。

## 最小联调清单

- 检查请求是否 GET/POST 正确、params/body 位置正确。
- 用 Network 保存脱敏的请求和响应。
- 确认是否携带 Token，服务端要求什么权限。
- 对照 DTO 字段与响应类型，避免猜测 wrapper。
- 对空数据同时查数据库/数据源和错误日志。
- 若涉及网关路径，核实对应环境 route 和 rewrite。
