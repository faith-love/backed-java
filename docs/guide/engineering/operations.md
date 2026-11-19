# 运行与部署：Linux 与 Nginx

运行与部署章节帮助区分代码构建成功和用户实际访问成功。[Linux 专题](./linux.md)讲进程、日志、健康检查和发布；[Nginx 专题](./nginx.md)讲静态站点、反向代理、路径改写和浏览器可见错误。

## 排错路线

~~~text
浏览器 Network
  -> DNS / TCP / TLS
  -> Nginx access/error log
  -> Gateway route 与服务发现
  -> Linux 进程/容器、监听端口、readiness
  -> 应用日志和下游依赖
~~~

| 现象 | 优先阅读 |
|---|---|
| Connection refused、服务反复重启、磁盘满 | [Linux 服务运行](./linux.md) |
| 404、502、504、上传体积受限、页面缓存旧 bundle | [Nginx 反向代理](./nginx.md) |
| 网络错误、TLS 错误、CORS | [计算机网络基础](./network.md) |
| Gateway 能接入但下游超时、消息积压 | [微服务与消息系统](./distributed.md) |

## 当前项目线索

仓库 bin/startup.sh 展示多个 JAR 的启动方式；各服务 bootstrap.yml 展示 Nacos 注册与配置入口。这些体现仓库的运行预期，不保证目标环境采用完全相同的启动器或参数；实际情况需查部署平台。

Docker 部署入口可从 `docker/docker-compose.yml` 和 `docker/docker-compose-base.yml` 入手，再顺着 `docker/gykj/gateway`、`docker/gykj/auth` 和 `docker/gykj/modules` 下的 Dockerfile 看构建上下文、端口映射与容器内启动命令。示例文件可能含环境专用配置；阅读时只记录变量名称和服务关系，不复制凭据。

## 常见问题定位

- 502/503：代理或网关连接不到上游，检查实例、端口和网络。
- 404：检查 Nginx location、网关 route、path rewrite 与 Controller 路径。
- 504：请求超过代理或客户端超时，区分慢 SQL、下游慢调用和超时设置。
- 上传失败：核对代理 body 限制、Spring multipart 限制和磁盘空间。
- 仅大请求失败：逐层比较代理、网关与应用的 body size 限制。
- 页面生成错误链接：核对 Host、协议和可信代理头。

## 安全与变更边界

只在授权的开发/测试环境试运行部署命令。生产重启、数据库迁移和文件清理可能中断业务或丢数据；需要确认目标、备份和回滚方案。日志和 dump 可能含 Token、个人信息或业务数据，分享前要脱敏。

## 练习

在本地用 Nginx 把静态页面代理到 VitePress 开发服务器，改变 location 和 proxy_pass 尾部斜杠并观察路径变化。再对照项目启动脚本，画出网关、认证和业务服务的启动关系。

下一步：[Git、Maven 与 Docker](./tools.md)、[工程与分布式学习入口](./index.md)。
