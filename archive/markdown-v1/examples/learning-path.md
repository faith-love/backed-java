# 04｜动手练习路线

练习都从仪表盘列表接口开始，先只读理解，再做低风险改动。每次开始前查看 git status，使用本地或隔离的开发环境，不要直接修改共享数据库。

## 第 1 关：画出接口路径

从 datacenter-web 的 componentListQry 出发，写下：

- 页面组件在哪里调用它？
- params 从哪里产生？
- 请求拦截器加了哪些公共信息？
- 后端 Controller 的最终路径是什么？
- /dashboard 网关前缀对应什么配置？

完成标准：能解释浏览器 URL、网关路径和服务内 Controller 路径的区别。

## 第 2 关：解释响应结构

在 Network 里观察列表响应。找到后端 MixinsResp、PageVO、PageEntity 的定义，比较原始 HTTP JSON 与页面最终拿到的对象。

完成标准：说清楚 HTTP status、业务 code、Axios interceptor 返回值和组件中的 data 分别是什么。

## 第 3 关：追分页查询

从 DashboardPageController.page 进入 DashboardPageServiceImpl.getByCategory，再追到分页和 Mapper。记录每个筛选条件来源于哪个 DTO 字段。

完成标准：能预测修改某个筛选条件会影响哪些层，以及如何避免把分页逻辑重复写在前端和后端。

## 第 4 关：追图表数据

从前端 getChatInfo 找到 ChartDataController.getChartDataByCode，再检查 PageEntity.config、chartList 和 BaseChartDataService。

完成标准：能讲出页面配置、图表配置、数据集配置和最终数据之间的关系，并能定位图表空白时的后端日志。

## 第 5 关：理解权限

检查页面路由守卫、按钮权限指令，以及 Controller 上的 @ApiPermission。分别记录前端和后端做的检查。

完成标准：理解隐藏按钮不能替代服务端鉴权，能说明 401 与 403 的常见区别。

## 第 6 关：做一个兼容性小改动

在本地分支中为现有查询增加一个可选筛选字段，依次检查 DTO → Service query condition → API wrapper → 页面传参。若涉及外部数据或公共环境，先使用 Mock/测试替身验证。

完成标准：旧请求不传新字段时仍然可用；新请求能按筛选条件工作；错误参数有清晰反馈。

## 第 7 关：补测试与提交说明

优先为业务规则编写 Service 单元测试，或为 Controller 参数/权限编写 Web 层测试。测试应验证真实行为，不要只断言方法调用了自己刚写的代码。

完成标准：记录运行过的命令、结果和未覆盖的外部依赖；提交说明描述触发条件、变化和验证证据。

## 每次读代码可以问自己的问题

1. 谁发起请求，参数从页面哪里来？
2. 请求如何通过 Axios、Gateway 和认证？
3. 哪个 Controller 接收它，参数绑定方式是什么？
4. 业务规则在哪个 Service？
5. 数据来自哪个 Mapper、Feign Client 或动态数据源？
6. 返回对象如何序列化和被前端拦截器处理？
7. 哪些错误会向前端抛出，哪些被转换为空结果？
8. 有哪些权限、日志、事务、缓存或数据范围规则？
