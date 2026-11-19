# 响应结构与参数约定

接口是前后端之间的长期契约。这里每个约定都在解决具体问题：减少歧义、让校验可复用、让组件可靠渲染。

## 参数放在哪里

| Axios 参数 | HTTP 位置 | Spring 接收方式 | 适合解决的问题 |
|---|---|---|---|
| params | URL query string | @RequestParam / Java Bean | 可书签化、适合 GET 的筛选和分页 |
| data | JSON body | @RequestBody DTO | 结构复杂的创建、更新和组合查询 |
| FormData | multipart/form-data body | MultipartFile/表单字段 | 文件与文本元信息一起上传 |

放错位置的直接后果是服务器拿到 null 或默认值。调试时看 Network 的 Query String Parameters/Request Payload，不要只看调用处的 JS 对象。

## DTO 校验与兼容性

DTO 是外部输入边界。必填、长度、格式和枚举值应在服务端校验，因为浏览器校验容易绕过。为已有 API 新增字段时，若要求旧客户端兼容，通常把字段做成可选并给合理默认；修改字段语义可能要做版本或迁移策略。

服务端校验能减少坏数据进入业务层，但错误消息也应指出字段和约束，避免前端只能显示“请求失败”。

## 响应分三层

1. **HTTP status**：传输协议层状态，例如 200、401、403、404、500。
2. **业务 response body**：模块可能用 Result、R、MixinsResp 等封装 code、msg、data 或分页字段。
3. **前端 interceptor 返回值**：request.js 可能解包到 res.data，或者在错误时 reject。

这个拆分解决“HTTP 成功但业务失败”时的误判。验证接口时要从 Network 原始响应开始，再看 interceptor 和组件最终收到的对象。

## 分页契约

`PageVO<T>` 表示泛型分页结果；具体字段和起始页要看当前接口实现。前后端共同确认：

- 页码从 0 还是 1 开始。
- pageSize/size 使用哪个名字、上限是多少。
- total 表示过滤后的总数还是当前页数量。
- 排序字段允许值及默认排序。
- 翻页期间数据变化时，是否需要稳定排序。

如果服务只返回当前页而没有 total，前端无法正确计算分页器；如果 total 未应用相同过滤条件，页数就会错。

## 错误处理契约

服务端应区分用户可纠正的参数/权限错误与内部异常。内部异常应在日志中保留诊断信息，并返回安全、稳定的错误结构；不要把 SQL、堆栈或敏感值直接暴露给浏览器。

前端 interceptor 要把业务 code 与 HTTP status 分开处理。401 常触发重新登录；403 应展示权限不足；校验错误可以指出具体字段。项目的实际 code 需查看 errorCode 和响应类。

## 文件传输

- 下载：Blob/ArrayBuffer 用于避免把字节流按 JSON 解析；文件名从 Content-Disposition 或约定响应头读取。
- 上传：multipart/form-data 让浏览器生成 boundary；手动固定错误的 Content-Type 可能导致 boundary 丢失。
- 大文件：检查客户端代理与服务端 multipart 大小限制、超时、分片或断点续传策略。

文件流经常不走普通 JSON wrapper，所以需要单独测试下载与上传路径。
