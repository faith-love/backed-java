---
layout: home

hero:
  name: 后端学习手册
  text: 从 Vue 页面一路读到 Java 服务
  tagline: 为前端开发者整理的 Java、Spring、数据库与分布式学习路线
  actions:
    - theme: brand
      text: 从学习指南开始
      link: /guide/
    - theme: alt
      text: 看真实项目链路
      link: /examples/request-walkthrough

features:
  - title: 先建立全局地图
    details: 从 Java 基础、Spring Web、持久层到分布式系统，循序渐进地认识后端知识。
    link: /guide/source-map
    linkText: 查看知识地图
  - title: 用熟悉的前端请求入门
    details: 从 Axios API wrapper 出发，追踪 Gateway、Controller、Service 和 DAO。
    link: /examples/request-walkthrough
    linkText: 追一条真实请求
  - title: 回到真实业务代码
    details: 用 datacenter-cloud 仓库中的仪表盘服务，把概念映射到真实模块和接口。
    link: /project/
    linkText: 进入项目实战
---

## 推荐学习顺序

**Java 基础 → Web 与 Spring → JDBC / MyBatis → 数据库与网络 → 微服务 → 项目实战**

如果你已经熟悉 Vue，可以先读 [参考站点知识地图](/guide/source-map)，再用 [请求调用链](/examples/request-walkthrough) 把熟悉的 HTTP 请求映射到 Java 服务端。

## 关于资料来源

知识主题参考 [JAVA 学习笔记](https://mrjokersince1997.github.io/My-Notes/#/)，并针对前端开发者重新分组、提炼和补充。参考站点适合作为主题索引；涉及版本和生产安全的结论，请以当前框架官方文档与项目实际配置为准。
