# 项目与微服务地图

本文把 datacenter-cloud 当作学习样本。目标不是记住所有模块，而是能判断“一个请求应该从哪里开始追，为什么经过这些层”。

## 先用一个问题理解分层

假设管理页面需要显示仪表盘列表：

- **Gateway** 解决浏览器只需访问统一入口、而不必知道每个业务服务的地址。
- **认证服务/安全过滤器**解决服务器需要知道调用者身份的问题。
- **Controller**解决 HTTP URL 和 Java 方法之间的映射。
- **DTO**解决前端请求参数需要明确结构并经过校验的问题。
- **Service**解决业务规则不应散落在 HTTP 接口和 SQL 中的问题。
- **Mapper/DAO**解决业务代码如何执行数据库读写的问题。
- **Entity/VO**分别解决持久化结构和接口返回结构的表达问题。

~~~
Vue 页面
  -> Axios request.js
  -> Gateway
  -> gykj-dashboard Controller
  -> Service
  -> Mapper / 数据集服务
  -> JSON 响应
~~~

图中是逻辑路径。Gateway 的实际路由、过滤和路径改写配置来自 Nacos/部署环境，不能只凭仓库里的 URL 推断生产行为。

## 仓库目录及其职责

| 目录 | 负责什么 | 它解决的问题 | 什么时候读 |
|---|---|---|---|
| gykj-gateway | 统一入口与路由能力 | 统一处理入口，隔离浏览器和内部服务地址 | 404、转发、Header、跨域问题 |
| gykj-auth | 认证和 Token 相关流程 | 确认请求来自哪个用户/服务 | 登录和 401 问题 |
| gykj-modules | 业务服务 | 按业务域拆分代码与部署单元 | 找具体 Controller/Service |
| gykj-api | 服务间契约、远程调用客户端和共享模型 | 避免服务调用方与提供方对接口理解不一致 | 追 Feign/跨服务调用 |
| gykj-common | 安全、日志、数据源等公共能力 | 多服务复用相同横切能力 | 某个注解/拦截行为来源不明 |
| gykj-visual | 监控服务 | 暴露运行状态与监控能力 | 服务观测问题 |
| sql/docs/docker/bin | 脚本、设计资料、容器和启动文件 | 支持数据库演进和部署 | 查建表、迁移、打包和运行 |

根 POM 将这些模块聚合构建；业务模块列表在 gykj-modules/pom.xml。仓库版本的技术基线是 Java 11、Spring Boot 2.7.18、Spring Cloud 2021.0.8 和 Spring Cloud Alibaba 2021.0.5.0。

## 常见业务模块解决什么问题

- **gykj-system**：管理用户、角色、菜单、字典和组织。它解决平台身份与管理权限的基础数据问题。
- **gykj-dashboard**：管理页面设计、组件、数据集和图表响应。它解决配置式仪表板如何保存、授权和运行的问题。
- **gykj-cloud**：处理数据源与表结构采集、ChatBI 等能力。它解决业务如何接入不同来源的数据。
- **gykj-file**：文件上传、下载和元信息管理，解决大文件和共享存储问题。
- **gykj-job**：定时/异步调度，解决周期任务不应依赖用户一直开着页面的问题。
- **gykj-gen**：代码生成，解决 CRUD 样板代码重复编写的问题。
- **gykj-dataroom / gykj-report**：各自的数据应用和报告功能，具体职责要从对应接口确认。

## 与前端仓库的对应关系

- E:\p\project\datacenter-web 是管理门户，常从 src/views 找页面、src/api 找请求。
- E:\p\project\data-board-ui 是仪表盘设计器，重点看画布、预览和数据集配置组件。
- E:\p\project\datacenter-cloud 是本文对应的后端服务端。

同一个后端模块可能同时服务管理门户和设计器。判断接口归属要看 API 路径和 Controller，不能只看页面所在仓库。

## 新手追接口的实际顺序

以仪表盘列表为例：

1. 前端 componentListQry 声明 URL 和 GET 方法。
2. Axios interceptor 添加 Token，并处理响应/错误。
3. Gateway 将请求路由到 gykj-dashboard。
4. DashboardPageController.page 绑定查询 DTO。
5. DashboardPageServiceImpl.getByCategory 组织筛选和分页条件。
6. IDashboardPageDao/MyBatis-Plus 查询 PageEntity。
7. Controller 返回分页响应，前端根据列表字段渲染表格。

每层都解决不同问题。分层让责任可以独立变化：URL 绑定改变时看 Controller，筛选规则改变时看 Service，SQL 和索引问题看 DAO/数据库。

继续读数据库细节时，先看[数据层与持久层入口](./data/)，再按问题进入[JDBC/MyBatis 映射](./data/persistence.md)、[MySQL/Redis](./data/storage.md)或[Spring 数据访问与事务](./spring/data-access.md)。当前仓库还可用 `DashboardApplication` 验证 Mapper 扫描和 MyBatis-Plus 分页插件；用 cloud 模块的 `CollectDatasourceSchemaServiceImpl`、Mapper/XML 和 `@DS` 验证动态数据源、分页结果以及本地元数据与外部 DDL 的事务边界。

## 本地运行意味着什么

dashboard bootstrap.yml 给出服务名和默认监听端口，并接入 Nacos。它不包含一个可脱离环境运行的完整开发配置。完整启动还需要 Java/Maven、Nacos、数据库、Redis、对象存储或外部数据源等依赖。启动服务前先核实当前 profile 和目标环境，避免连接共享或生产资源。

## 学完自测

- 浏览器 URL 和 Controller 内部 URL 为什么可能不同？
- 一个筛选字段从页面到数据库，依次经过哪些类型/文件？
- 前端显示空列表时，哪几层可能导致 0 行？
- 为什么接口 DTO 不一定直接就是数据库 Entity？
