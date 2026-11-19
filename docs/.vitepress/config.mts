import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
  lang: 'zh-CN',
  title: '后端学习手册',
  description: '面向前端开发者的 Java 后端学习知识库',
  // GitHub Pages 的仓库子路径由 Actions 在构建时注入；本地开发和预览默认使用根路径。
  base: process.env.VITEPRESS_BASE || '/',
  cleanUrls: true,
  lastUpdated: true,
  // 让 Markdown 中的 mermaid 代码围栏渲染为可缩放的流程图和时序图。
  mermaid: {
    theme: 'default'
  },
  vite: {
    // fastdom 是 Mermaid 的 CommonJS 依赖；开发服务器需预打包，避免浏览器按 ESM 导入失败。
    optimizeDeps: {
      include: ['fastdom', 'fastdom/extensions/fastdom-promised.js']
    }
  },
  themeConfig: {
    outline: { label: '本页目录', level: [2, 3] },
    docFooter: { prev: '上一篇', next: '下一篇' },
    search: { provider: 'local' },
    nav: [
      { text: '学习指南', link: '/guide/' },
      { text: '项目实战', link: '/project/' },
      { text: 'API 参考', link: '/api/' },
      { text: '实践示例', link: '/examples/' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '开始学习',
          collapsed: false,
          items: [
            { text: '学习路线', link: '/guide/' },
            { text: '参考站点知识地图', link: '/guide/source-map' },
            { text: '项目与微服务地图', link: '/guide/project-map' },
            { text: '后端核心概念', link: '/guide/backend-concepts' },
            { text: '联调与排错', link: '/guide/debugging' }, { text: '文档站点使用', link: '/guide/site-usage' }
          ]
        },
        {
          text: 'Java 基础',
          collapsed: true,
          items: [
            { text: 'Java 学习入口', link: '/guide/java/' },
            { text: '类、对象与类型', link: '/guide/java/core' },
            { text: '泛型：类型参数、通配符与类型擦除', link: '/guide/java/generics' },
            { text: '日期时间与金额精度', link: '/guide/java/date-time-money' },
            { text: '注解大全：Java、Spring 与常用框架', link: '/guide/java/annotations' },
            { text: '集合：List、Set、Queue 与 Map', link: '/guide/java/collections' },
            { text: '基础容器：常用集合实现与数据结构', link: '/guide/java/containers' },
            { text: '并发容器：线程安全与有界队列', link: '/guide/java/concurrent-containers' },
            { text: 'Stream：过滤、转换与汇总', link: '/guide/java/streams' },
            { text: '异常：分类、传播与处理', link: '/guide/java/exceptions' },
            { text: '并发总览与使用场景', link: '/guide/java/concurrency' },
            { text: '线程创建、生命周期与中断', link: '/guide/java/threads' },
            { text: '锁的类型、优缺点与选择', link: '/guide/java/locks' },
            { text: '线程池、Future 与异步编排', link: '/guide/java/thread-pools' },
            { text: 'JVM 与垃圾回收', link: '/guide/java/jvm' },
            { text: 'IO：流、编码与文件处理', link: '/guide/java/io' },
            { text: 'NIO：Buffer、Channel 与 Selector', link: '/guide/java/nio' },
            { text: '网络库、HTTP 客户端与选型', link: '/guide/java/network-libraries' },
            { text: '大文件与分片上传', link: '/examples/large-file-upload' }
          ]
        },
        {
          text: 'Web 与 Spring',
          collapsed: true,
          items: [
            { text: 'Spring 总览', link: '/guide/spring/' },
            { text: 'IoC 与依赖注入', link: '/guide/spring/ioc' },
            { text: 'Servlet 与 Servlet 容器', link: '/guide/spring/servlet' },
            { text: 'Spring MVC 与 Controller', link: '/guide/spring/mvc' },
            { text: 'Java 对象与 JSON 契约', link: '/guide/spring/json-contracts' },
            { text: 'Spring Boot 配置', link: '/guide/spring/boot' },
            { text: 'AOP 与代理', link: '/guide/spring/aop' },
            { text: '认证与授权', link: '/guide/spring/security' },
            { text: '数据访问与事务', link: '/guide/spring/data-access' },
            { text: '测试：单元、切片与集成', link: '/guide/spring/testing' },
            { text: '异步、事件、缓存与服务集成', link: '/guide/spring/integration' },
            { text: '可观测性与生产运行', link: '/guide/spring/observability' },
            { text: 'WebFlux 与响应式编程', link: '/guide/spring/webflux' },
            { text: 'Spring Cloud 微服务', link: '/guide/spring/cloud' }
          ]
        },
        {
          text: '数据库与持久层',
          collapsed: true,
          items: [
            { text: '数据层学习入口', link: '/guide/data/' },
            { text: 'JDBC：SQL 执行、连接与事务', link: '/guide/data/jdbc' },
            { text: 'JDBC、MyBatis 与 ORM', link: '/guide/data/persistence' },
            { text: '关系型、缓存型与时序数据库', link: '/guide/data/database-types' },
            { text: 'SQL 查询与写入实战', link: '/guide/data/sql-practice' },
            { text: 'MySQL 与 Redis 基础', link: '/guide/data/storage' },
            { text: 'MySQL 生产机制与运维', link: '/guide/data/mysql-operations' },
            { text: 'Redis 运维与消息语义', link: '/guide/data/redis-operations' }
          ]
        },
        {
          text: '工程与分布式',
          collapsed: true,
          items: [
            { text: '工程与分布式学习入口', link: '/guide/engineering/' },
            { text: '工程工具链总览', link: '/guide/engineering/tools' },
            { text: 'Git 版本管理', link: '/guide/engineering/git' },
            { text: 'Maven 构建与依赖', link: '/guide/engineering/maven' },
            { text: 'Docker 与 Compose', link: '/guide/engineering/docker' },
            { text: 'CI/CD 与可靠发布', link: '/guide/engineering/cicd' },
            { text: '运行部署总览', link: '/guide/engineering/operations' },
            { text: 'Linux 运维', link: '/guide/engineering/linux' },
            { text: 'Nginx 反向代理', link: '/guide/engineering/nginx' },
            { text: 'Nacos 注册与配置中心', link: '/guide/engineering/nacos' },
            { text: 'Gateway、Sentinel、Seata 与 API 工具', link: '/guide/engineering/microservice-tools' },
            { text: '微服务与消息系统', link: '/guide/engineering/distributed' },
            { text: '计算机网络基础', link: '/guide/engineering/network' },
            { text: '设计模式', link: '/guide/engineering/patterns' }
          ]
        }
      ],
      '/project/': [{
        text: '数据中台项目实战',
        collapsed: false,
        items: [
          { text: '项目实战入口', link: '/project/' },
          { text: '仪表盘 API 参考', link: '/api/dashboard-endpoints' },
          { text: '请求调用链', link: '/examples/request-walkthrough' },
          { text: '动手练习路线', link: '/examples/learning-path' },
          { text: '支付宝与微信支付实战', link: '/examples/online-payment' },
          { text: '大文件与分片上传', link: '/examples/large-file-upload' }
        ]
      }],
      '/api/': [{
        text: 'API Reference',
        collapsed: false,
        items: [
          { text: '接口参考首页', link: '/api/' },
          { text: '仪表盘 API', link: '/api/dashboard-endpoints' },
          { text: '响应与参数约定', link: '/api/response-contracts' }
        ]
      }],
      '/examples/': [{
        text: '实践示例',
        collapsed: false,
        items: [
          { text: '示例首页', link: '/examples/' },
          { text: '请求链路', link: '/examples/request-walkthrough' },
          { text: '练习路线', link: '/examples/learning-path' },
          { text: '支付宝与微信支付实战', link: '/examples/online-payment' },
          { text: '大文件与分片上传', link: '/examples/large-file-upload' }
        ]
      }]
    },
    footer: {
      message: '持续学习，按源码验证。',
      copyright: '内容基于公开笔记目录整理，并结合 datacenter-cloud 项目源码编写。'
    }
  }
}))
