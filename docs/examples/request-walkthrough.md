# 01｜从前端请求追到后端

这章用真实代码演示如何从 URL 一层层找到服务端逻辑。后端源码路径相对于 E:\p\project\datacenter-cloud，前端路径相对于相邻前端仓库。

## 链路 A：仪表盘列表

### 1. 从前端 API 函数开始

位置：E:\p\project\datacenter-web\src\api\componentList\index.js

    /**
     * 查询仪表盘页面和组件的分页列表。
     * params 一般包含分页、分类等查询条件。
     */
    export function componentListQry(params) {
      return request({
        // /dashboard 是浏览器请求进入后端时使用的网关前缀。
        // 第二段 /dashboard/design/page 对应仪表盘服务内的 Controller 路径。
        url: '/dashboard/dashboard/design/page',
        // 列表查询使用 GET，查询条件放在 URL 参数中。
        method: 'get',
        // Axios 会将 params 序列化到请求 URL。
        params
      })
    }

这里要区分 API 函数和页面组件：API 函数定义 URL 和 HTTP 方法；页面组件负责何时调用、传什么参数、如何显示结果。继续搜索 componentListQry，可以找到它的调用页面。

### 2. 看统一请求层做了什么

位置：E:\p\project\datacenter-web\src\utils\request.js

这个项目创建了一个 Axios 实例，baseURL 来自 apiBaseUrl。请求拦截器会读取 Token 并添加 Authorization: Bearer ...；响应拦截器会把业务成功响应 res.data 返回给页面，并集中处理 401 和错误提示。

因此浏览器 Network 面板看到的完整 URL，通常由 baseURL 与 API 函数里的 url 拼接而来。排查时分别确认这两部分，不要只看 API 文件里的相对路径。

### 3. 从 URL 找 Controller

位置：gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/module/manage/controller/DashboardPageController.java

Controller 类级别的路径是 /dashboard/design，列表方法级别的路径是 /page，因此服务内部的组合路径是 /dashboard/design/page。它接收 DashboardSearchDTO，调用 dashboardPageService.getByCategory，然后把分页结果装入 MixinsResp 返回。

注意：浏览器 URL 还多一层 /dashboard 网关前缀。它如何映射到服务内部路径由 Gateway/Nacos 配置决定；不同环境可能有不同的路由或路径改写。

### 4. 接着看 Service 和 DAO

位置：gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/module/manage/service/impl/DashboardPageServiceImpl.java

getByCategory 会根据查询参数组织数据库筛选条件，并调用分页能力。实现类继承 MyBatis-Plus 的 ServiceImpl，因此部分常用数据库操作由父类提供。

位置：gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/module/manage/dao/IDashboardPageDao.java

DAO 接口继承 BaseMapper&lt;PageEntity&gt;。如果方法没有手写实现，MyBatis-Plus 会按实体映射生成常见 CRUD；复杂 SQL 可能出现在注解或 resources/mapper 下的 XML。

相关模型：

- DashboardSearchDTO：列表查询条件。
- PageEntity：仪表盘页面持久化实体，对应 dashboard_page。
- PageVO：分页返回结构。
- MixinsResp：该模块使用的响应封装之一。

## 链路 B：读取图表数据

前端同一 API 文件中的 getChatInfo 请求图表详情数据，URL 为 /dashboard/dashboard/chart/data/list。后端 Controller 的服务内路径是 /dashboard/chart/data/list。

位置：gykj-modules/gykj-dashboard/src/main/java/com/gykj/dashboard/module/chart/controller/ChartDataController.java

处理过程：

1. 读取 ChartDataSearchDTO 中的 pageCode。
2. 通过页面 Service 找到页面实体，并取出 JSON 配置反序列化后的页面 DTO。
3. 在 chartList 中按 chartCode 找到图表配置。
4. 将页面配置、图表配置和查询参数交给 BaseChartDataService。
5. Service 根据图表的数据源配置解析数据集，并通过数据集服务执行查询，最终组装 ChartDataVO。

PageEntity.config 使用 BasePageDTOTypeHandler 处理 JSON 字段；DashboardPageDTO 则包含 chartList、style、pageConfig 等前端设计器保存的配置。看这两个类有助于理解页面设计配置如何变成运行时图表。

一个排查注意点：BaseChartDataService 对部分查询异常会记录日志并返回空的 ChartDataVO，所以前端可能只看到空图表。遇到这种情况，要同时看浏览器响应和 dashboard 服务日志，不能仅凭 UI 判断查询成功。

## 画成时序

    Vue 页面或图表
       | 调用 API 函数
       v
    request.js 加 Token、序列化参数
       | HTTP 请求
       v
    gykj-gateway 按路由转发
       v
    Controller 接收 DTO
       v
    Service 组织业务逻辑
       v
    Mapper / 数据集服务查询
       |
       v
    Service 返回 VO / 分页对象
       v
    Controller 响应 JSON
       v
    Axios interceptor 处理后交给页面

## 前端改接口时的检查清单

- 请求方法是否与后端注解一致：GET 对 GetMapping，POST 对 PostMapping。
- Query 参数是否应放 params，JSON body 是否应放 data。
- API 路径是否包含网关前缀；Controller 类级和方法级路径拼接后是否正确。
- 后端 DTO 字段名、字段类型、枚举值与前端实际 payload 是否一致。
- 返回值是 R、MixinsResp 还是其他包装，前端 request 拦截器是否已解包。
- 是否需要 Authorization，以及是否同时受前端菜单/按钮权限和后端接口权限控制。
