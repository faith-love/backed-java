# 00｜学习地图：先认清项目，再找入口

## 一句话理解

这是一个数据中台后端。浏览器请求通常先进入 API 网关，再由网关按配置转给认证服务或某个业务服务；业务服务负责权限和业务规则，最后访问数据库、缓存或外部数据源。

    浏览器 / Vue 页面
       |
       v
    gykj-gateway 统一 API 入口
       |                 \
       v                  v
    gykj-auth       gykj-system / gykj-dashboard / gykj-cloud 等业务服务
                              |                         |
                              v                         v
                       应用配置数据库             配置的数据源

服务发现和配置由 Nacos 集成。网关到具体服务的 route、path rewrite 规则来自配置中心或部署配置。仓库能看到服务名和 Nacos 接入方式，但不能据此断定每个环境的实际路由规则。

## 仓库结构

| 目录 | 职责 | 前端开发者什么时候看 |
|---|---|---|
| gykj-gateway | 浏览器 API 的统一入口、微服务路由相关能力 | 请求 404、转发异常、跨服务调用前先确认网关配置 |
| gykj-auth | 登录、Token 和认证相关服务 | 登录失败、Token 失效、认证接口异常 |
| gykj-modules | 业务服务的聚合 Maven 模块 | 大部分业务接口从这里开始找 |
| gykj-api | 服务间调用的 Feign/API 契约和共享 DTO | 一个服务调用另一个服务、接口契约不一致 |
| gykj-common | 安全、日志、数据源、Redis 等共享能力 | Controller 上的注解或公共配置行为不明显时 |
| gykj-visual | 监控服务 | 服务监控问题 |
| sql、docs、docker、bin | 数据库脚本、设计说明、部署与启动资料 | 查迁移、部署结构和本地运行方式 |

根 pom.xml 定义了 Java 11、Spring Boot 2.7.18、Spring Cloud 2021.0.8 和 Spring Cloud Alibaba 2021.0.5.0，并聚合 auth、gateway、visual、modules、api、common。业务模块清单见 gykj-modules/pom.xml。

## 常见业务服务

| 服务 | 代码目录 | 用途示例 |
|---|---|---|
| gykj-system | gykj-modules/gykj-system | 用户、角色、菜单、字典、组织等平台管理 |
| gykj-dashboard | gykj-modules/gykj-dashboard | 仪表盘、图表、数据集、门户和自动化报告 |
| gykj-cloud | gykj-modules/gykj-cloud | 数据源、库表结构采集以及 ChatBI 相关能力 |
| gykj-file | gykj-modules/gykj-file | 文件相关能力 |
| gykj-job | gykj-modules/gykj-job | 定时任务 |
| gykj-gen | gykj-modules/gykj-gen | 代码生成 |
| gykj-dataroom、gykj-report | 各自业务模块 | 数据应用和报告相关功能；按具体接口继续追踪 |

这些是按模块命名和当前代码组织总结的导航，不代表服务间没有共享或交叉调用。

## 前端仓库对应关系

- E:\p\project\datacenter-web：Vue 2 管理门户，常见页面、路由、权限、API wrapper 在 src 下。
- E:\p\project\data-board-ui：Vue 仪表盘设计器组件库和设计/预览能力。
- E:\p\project\datacenter-cloud：本文主要分析的后端仓库。

两个前端都可能调用 gykj-dashboard。门户页面和仪表盘画布是不同的前端入口，但接口最终可能落到同一业务服务。

## 从目录猜模块，从类名找链路

Java 业务代码常见结构：

- controller：HTTP 路由入口，做参数接收、校验和响应封装。
- service / service.impl：业务规则和流程编排。
- dao / mapper：数据库访问；MyBatis XML 可能提供自定义 SQL。
- entity / domain：持久化模型或领域数据。
- dto：请求参数或服务间传输对象。
- vo：面向接口调用方的返回模型。
- config、constant、permission：配置、常量和授权逻辑。

别只凭文件夹名判断。最可靠的办法是从 Controller 的调用一路点进去，直到具体数据库或外部服务操作。

## 本地启动线索

- 根 pom.xml 是 Maven 聚合构建入口。
- gykj-modules/gykj-dashboard/src/main/resources/bootstrap.yml 显示仪表盘服务名为 gykj-dashboard、默认端口为 9305，并从 Nacos 获取注册和配置所需信息。
- gykj-gateway/src/main/resources/bootstrap.yml 显示网关默认端口 9000，同样接入 Nacos。
- bin/startup.sh 展示部署包会启动网关、认证和多个业务服务。

本地能否完整启动还取决于 JDK/Maven、Nacos、数据库、Redis、配置项和外部服务。不要直接使用 application-dev.yml 中的服务器地址或凭据；先确认它们是否属于可访问的开发环境，也不要把凭据写入笔记。
