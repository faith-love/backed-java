# API Reference｜接口参考

本部分按调用方可见的接口路径、HTTP 方法、参数和响应来组织。实际环境还会由 Gateway/Nacos 路由配置决定外部 URL 如何映射到服务内部路径。

## 模块

- [仪表盘 API](./dashboard-endpoints.md)：页面列表和图表数据的代表性接口。
- [响应结构与参数约定](./response-contracts.md)：Query、JSON Body、分页及响应 wrapper。

## 查接口的顺序

1. 找前端 API wrapper，确认它传出的 URL、method、params/data。
2. 将 Controller 类上的 RequestMapping 和方法上的映射拼起来。
3. 查看 DTO 和校验注解，确认字段、类型与必填规则。
4. 查看响应类型，并检查前端 Axios interceptor 是否会解包。
5. 如果浏览器路径与 Controller 路径不同，检查当前环境的网关路由配置。
