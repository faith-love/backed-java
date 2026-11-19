# 仪表盘 API

以下路径用于帮助读代码。浏览器请求通常还会带网关路由前缀，最终映射规则需以当前环境的 Gateway/Nacos 配置为准。

## 页面与组件列表

| 项目 | 内容 |
|---|---|
| 前端 API 文件 | datacenter-web/src/api/componentList/index.js |
| 前端函数 | componentListQry(params) |
| 浏览器侧 URL | /dashboard/dashboard/design/page |
| HTTP 方法 | GET |
| Controller | gykj-dashboard/.../manage/controller/DashboardPageController.java |
| 服务内路径 | 类级 /dashboard/design + 方法级 /page |
| 参数对象 | DashboardSearchDTO |
| 调用 Service | IDashboardPageService.getByCategory |
| 返回结构 | MixinsResp<PageVO<PageEntity>> |

Controller 查询页面分页列表，Service 组织筛选条件和分页查询，DAO 访问 PageEntity。可从 [请求链路示例](../examples/request-walkthrough.md) 看到具体阅读顺序。

## 图表数据

| 项目 | 内容 |
|---|---|
| 前端 API 文件 | datacenter-web/src/api/componentList/index.js |
| 前端函数 | getChatInfo(params) |
| 浏览器侧 URL | /dashboard/dashboard/chart/data/list |
| HTTP 方法 | POST |
| Controller | gykj-dashboard/.../chart/controller/ChartDataController.java |
| 服务内路径 | 类级 /dashboard/chart/data + 方法级 /list |
| 请求对象 | ChartDataSearchDTO |
| 核心处理 | 查页面配置、按 chartCode 找图表，再由 BaseChartDataService 执行数据集查询 |
| 返回结构 | R<ChartDataVO> |

## 设计器配置与运行时数据

仪表盘页面实体 PageEntity 的 config 字段承载页面配置，经过自定义 type handler 在 JSON 与 BasePageDTO 子类之间转换。仪表盘 DTO 中包含图表列表和页面样式等信息。图表请求带页面标识和图表信息，后端再按数据源配置选择数据集服务。

## 关联源码

- datacenter-web/src/api/componentList/index.js
- datacenter-web/src/utils/request.js
- gykj-dashboard/.../manage/controller/DashboardPageController.java
- gykj-dashboard/.../manage/service/impl/DashboardPageServiceImpl.java
- gykj-dashboard/.../chart/controller/ChartDataController.java
- gykj-dashboard/.../chart/service/BaseChartDataService.java
- gykj-dashboard/.../basic/entity/PageEntity.java
- gykj-dashboard/.../manage/dto/DashboardPageDTO.java
