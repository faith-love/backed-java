# 后端学习手册（VitePress）

面向熟悉 Vue、JavaScript 和 HTTP，想系统理解 Java 后端的前端开发者。

## 本地启动

需要 Node.js 18 或更高版本。首次启动先安装依赖：

~~~sh
npm install
npm run docs:dev
~~~

构建与预览：

~~~sh
npm run docs:build
npm run docs:preview
~~~

## 发布到 GitHub Pages

仓库配置 GitHub Pages 的发布来源为 **GitHub Actions** 后，向默认分支推送会自动构建并发布 `docs/.vitepress/dist`。工作流会读取 GitHub Pages 的 base path，因此既支持 `owner.github.io` 根站，也支持普通仓库的项目站路径。本地开发仍使用 `/`。

首次配置时，在 GitHub 仓库的 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**。工作流文件位于 `.github/workflows/deploy-pages.yml`；`workflow_dispatch` 可在 Actions 页面手动触发默认分支部署。

## 文档结构

- docs/guide：Java、Spring、数据库、工程和分布式学习指南。
- docs/project：datacenter-cloud 项目地图与阅读路线。
- docs/api：仪表盘 API、参数和响应约定。
- docs/examples：真实接口调用链与递进练习。
- docs/.vitepress/config.mts：顶部导航、侧边栏和本地搜索配置。

推荐从站点首页的学习路线开始。想直接看项目代码时，进入“项目实战”或“API 参考”。

知识主题整理自 [JAVA 学习笔记](https://mrjokersince1997.github.io/My-Notes/#/)，并结合当前 datacenter-cloud 源码校准。参考站点中的配置示例不作为可直接复制的配置；本知识库统一使用占位符表示密钥。
