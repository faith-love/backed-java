# 项目实战

这里把后端基础落到 datacenter-cloud 仓库。推荐先读项目地图，然后选一个列表 API 走完请求链路，再读图表数据查询。

## 项目技术地图

- Java 11、Spring Boot 2.7、Spring Cloud Alibaba。
- Maven 多模块：网关、认证、业务服务、共享 API 和公共组件。
- 仪表盘服务：页面配置、图表、数据集和门户相关功能。
- 管理门户与仪表盘设计器位于相邻前端仓库。

## 实战路线

1. [项目与微服务地图](/guide/project-map)
2. [仪表盘 API 参考](/api/dashboard-endpoints)
3. [从前端请求追到后端](/examples/request-walkthrough)
4. [联调与排错](/guide/debugging)
5. [动手练习路线](/examples/learning-path)

文档对应具体 checkout 的源码结构。网关路由、Nacos 配置和目标数据库需要在当前环境单独核验。
