# 工程工具与常用命令

工程工具把代码变成可审阅、可构建、可运行和可排查的服务。可以从 [Git](./git.md) 的变更管理开始，接着用 [Maven](./maven.md) 构建 Java 项目、用 [Docker](./docker.md) 运行依赖，再学习 [Linux](./linux.md)、[Nginx](./nginx.md)、[Nacos](./nacos.md) 和 [微服务联调工具](./microservice-tools.md)。

| 工具 | 主要解决的问题 | 核心问题 |
|---|---|---|
| Git | 多人如何并行修改、审阅和回退代码 | 变更在哪个分支、暂存了什么、是否已经共享？ |
| Maven | Java 项目如何解析依赖、测试和构建 | 失败在哪个模块/阶段，运行时缺少的类来自哪个依赖？ |
| Docker | 如何一致地运行应用及其依赖 | 容器监听什么端口，数据放在哪里，服务如何互相访问？ |
| CI/CD | 如何把通过验证的代码构建成可追踪制品，再安全部署和回滚 | 测试的是不是上线的同一镜像，健康门禁和回滚条件是什么？ |
| Linux | 如何检查服务器进程、端口、资源与服务 | 应用是否在运行、哪个进程占用端口、磁盘是否耗尽？ |
| Nginx | 如何接收外部请求并转发到内部服务 | 请求匹配了哪个站点，路径如何改写，上游为何超时？ |
| Nacos | 如何发现服务实例并集中管理配置 | 实例是否注册到正确环境，应用读到了哪份配置？ |
| Gateway、Sentinel、Seata 与 API 工具 | 如何路由、保护流量、协调事务并复现接口 | 请求在哪一跳失败，规则是否命中，事务最终状态是什么？ |

## 从代码到服务

~~~mermaid
flowchart LR
  %% 展示一次代码变更从本地开发到线上请求入口的主要检查点。
  A[编写代码] --> B[Git 检查并提交]
  B --> C[Maven 编译、测试、打包]
  C --> D{构建通过?}
  D -- 否 --> E[查看编译或测试错误]
  E --> A
  D -- 是 --> F[生成 JAR 或镜像]
  F --> G[容器启动并加载配置]
  G --> H[检查服务健康和日志]
  H --> I[Nginx / Gateway 转发请求]
~~~

每一步成功只能证明这一阶段完成。例如 Maven 打包成功不表示镜像已更新；容器启动不表示应用完成初始化；端口映射存在也不表示浏览器请求能通过网关。

## 常用命令从哪里查

命令按“先观察、再定位、最后变更”的顺序使用。刚接手项目时先确认当前分支、构建模块、容器状态和服务日志；只有确认目标环境、配置文件和影响范围后，才执行会提交、重启或发布的命令。

| 想完成的事 | 先用什么命令 | 详细说明 |
|---|---|---|
| 确认代码在哪个分支、有哪些未提交变更 | `git status --short --branch`、`git diff` | [Git 命令速查](./git.md#git-常用命令速查) |
| 只构建一个 Maven 模块并运行测试 | `mvn -pl 模块路径 -am test` | [Maven 命令速查](./maven.md#maven-常用命令速查) |
| 找到服务对应的容器并查看启动错误 | `docker compose ps`、`docker compose logs --tail=100 服务名` | [Docker 命令速查](./docker.md#docker-常用命令速查) |
| 检查进程、端口、磁盘与系统服务 | `ps`、`ss`、`df`、`systemctl status` | [Linux 命令速查](./linux.md#linux-常用命令速查) |
| 检查 Nginx 配置并确认请求到达哪一层 | `nginx -t`、查看 access/error log | [Nginx 命令速查](./nginx.md#nginx-常用命令速查) |
| 验证 Nacos 服务列表、配置和应用健康状态 | Compose 状态/日志、受控 HTTP 检查 | [Nacos 命令与检查](./nacos.md#常用命令与检查) |
| 重放 API、查看网关路由和应用健康 | `curl -i`、Actuator 端点、Compose 日志 | [微服务工具命令与联调](./microservice-tools.md#常用命令与联调) |

### 命令使用的边界

- Shell 示例默认是 Linux/macOS 的 Bash。Windows PowerShell 的变量、引号和管道语法不同；不要把一段 Bash 原样粘贴到 PowerShell。
- `status`、`diff`、`logs`、`nginx -t`、`curl` 通常用于观察或验证；`checkout`、`reset`、`clean`、`restart`、`up`、`down` 会改动工作区或运行状态，执行前确认目标。
- 地址、服务名、端口、模块路径和配置文件都以当前项目为准。先从 `pom.xml`、Compose 文件、Nginx 配置和服务日志确认真实值。
- 命令返回成功只证明该命令完成。例如 HTTP 200 不一定代表业务成功，Docker 容器 `Up` 不一定代表应用健康，Maven `package` 成功也不代表镜像已经重新构建。
- 分享命令输出前检查 Token、Cookie、密码、内部域名、数据库地址和 Nacos 配置内容；不要把真实凭据写进命令行历史或文档。

## 在当前仓库中怎么用

根 pom.xml 是 Maven 聚合入口；业务模块在 gykj-modules 下；docker 目录包含镜像和 compose 配置。先看目标 Dockerfile、Compose 文件和 bin 脚本，确认端口、Nacos、数据库、卷和环境变量，再判断 JAR 如何部署。

下一步：[Linux、Nginx 与部署](./operations.md)、[工程与分布式学习入口](./index.md)。
