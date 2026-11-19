# 响应结构与参数约定

## HTTP 参数位置

| 前端 Axios 参数 | 常见 HTTP 位置 | Spring 参数形式 | 常见用途 |
|---|---|---|---|
| params | URL query string | Java Bean、RequestParam | GET 查询、筛选、分页 |
| data | JSON request body | RequestBody DTO | 新增、修改、复杂查询 |
| FormData | multipart/form-data | MultipartFile、表单字段 | 文件上传 |

最终以 Controller 的参数声明和浏览器 Network 实际请求为准。

## 响应包装

项目不同模块使用不同响应类型，例如 Result、R、MixinsResp。理解响应要分三层：

1. HTTP 状态：网络和 HTTP 层面的结果。
2. 业务响应体：常见字段包括业务 code、msg、data。
3. 前端 interceptor 的返回值：门户 request 层通常在业务成功时返回 res.data，页面不一定能看到完整 Axios response。

所以 HTTP 200 并不总是业务成功；也不要假设所有模块的 data 都有同一形状。

## 分页

分页结果常由 PageVO<T> 或同类类型表示。确认页码是从 0 还是 1 开始、字段叫 current/pageNum 还是其他名称、页长和 total 放在哪一层。前后端统一字段及语义，避免 UI 页码看似正常但请求或总数错误。

## 文件接口

下载常设置 responseType 为 blob 或 arraybuffer，请求拦截器应绕开普通 JSON 响应解包；文件名可能来自 Content-Disposition 或自定义响应头。上传通常要使用 multipart/form-data，并检查服务端大小限制。
