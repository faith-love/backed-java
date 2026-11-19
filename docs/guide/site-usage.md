# VitePress 文档站点使用说明

## 环境要求

使用 NVM 切到 Node.js 18 或更高版本，然后根据 package-lock 安装锁定依赖。

~~~powershell
# 切换到已安装的 Node 24 版本；版本号按 nvm list 的结果选择。
nvm use 24.9.0

# 在知识库根目录安装锁定版本的依赖。
npm ci

# 启动本地热更新站点。
npm run docs:dev
~~~

## 构建与预览

~~~sh
# 生成静态 HTML，检查 Markdown 和 Vue/VitePress 编译错误。
npm run docs:build

# 启动本地静态预览服务器。
npm run docs:preview
~~~

## 页面结构

- docs/index.md：站点首页和学习入口。
- docs/.vitepress/config.mts：顶部导航、分组侧栏、本地搜索和页脚。
- docs/guide：知识路线与每个知识领域。
- docs/api：契约查询手册。
- docs/examples：调用链、练习和排错示例。
- docs/project：把基础概念映射到 datacenter-cloud 的实战入口。
- archive/markdown-v1：重构前的 Markdown 快照，供回查。

## 在 Markdown 中添加图片和流程图

适合自己维护、需要跟随文字一起修改的流程，优先用 Mermaid；架构截图、界面截图或复杂示意图可以作为图片文件保存。图片放在 `docs/public/images/` 下后，可用 `/images/文件名.svg` 这样的站点根路径引用；文件名尽量使用英文和短横线，方便部署到不同平台。

### Mermaid 流程图

Mermaid 图直接写在 Markdown 围栏代码块中。关键步骤可以用 `%%` 加中文注释，图外再补一段文字解释图的边界和异常情况。

~~~mermaid
flowchart TD
  %% 流程图展示系统启动时的主要检查顺序。
  A[启动服务] --> B[读取应用配置]
  B --> C{配置有效吗?}
  C -- 否 --> D[查看启动日志并修正配置]
  D --> B
  C -- 是 --> E[连接依赖并注册服务]
  E --> F[健康检查通过]
~~~

### Mermaid 时序图

需要展示谁先请求谁、响应如何返回时，用 `sequenceDiagram` 表达；这类图适合服务发现、登录、支付回调和文件上传等多方交互。

~~~mermaid
sequenceDiagram
  %% 前端提交业务请求，服务端完成校验后返回结果。
  participant UI as 浏览器页面
  participant API as 后端接口
  participant DB as 数据库
  UI->>API: 提交请求和业务参数
  API->>DB: 查询或保存业务数据
  DB-->>API: 返回查询结果
  API-->>UI: 返回状态码和响应数据
~~~

### 插入本地图片

图片文件放到 `docs/public/images/` 后，通过 Markdown 图片语法引用。构建后可以从站点根路径访问文件。

~~~md
<!-- 图片源文件路径为 docs/public/images/service-topology.svg。 -->
![服务调用拓扑图](/images/service-topology.svg)
~~~

新增流程图后运行 `npm run docs:build`，再用本地预览检查中文节点、浅色/深色主题和窄屏布局。Mermaid 语法错误可能不会让 Markdown 构建失败，所以还要在浏览器里确认图已渲染，而不是显示解析错误。

新增页面时，在对应目录创建 Markdown，并把链接加入 config.mts 的 sidebar；页面里用相对链接跳转。完成后运行 build，避免链接路径或代码围栏错误。
