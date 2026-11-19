# 大文件与分片上传：从 Vue 切片到后端可靠发布

假设用户上传一个 2 GiB 视频，网络在 80% 时中断。一次请求上传整个文件通常只能重传；把文件拆成 4 MiB 的片段后，只需补传服务端尚未确认的片段。但“调用 `file.slice()` + `Promise.all()`”只完成了很小一部分：还要解决内存、并发、重试、身份校验、断点恢复、完整性、合并竞争和残留文件清理。

本章先给出完整协议，再给 Vue/浏览器侧代码和可独立运行的 Java 核心实验。前置知识：[IO](../guide/java/io.md)、[NIO](../guide/java/nio.md)、[网络库](../guide/java/network-libraries.md)、[线程池](../guide/java/thread-pools.md)。

::: info 示例边界
`ChunkUploadDemo.java` 是已编译运行的单 JVM 文件核心实验，验证流式接收、重试、续传、摘要和合并竞争；没有 HTTP 服务器、数据库或重启恢复能力。浏览器 ES module 与 Spring 片段是本章接口契约的接入示例，需要你在实际工程实现接口、鉴权和存储；知识库的 VitePress 服务不提供这些上传 API。不要把模拟成功理解为真实大文件全链路压测通过。
:::

## 先区分六个容易混淆的概念

| 概念 | 在哪一层 | 解决什么问题 | 不保证什么 |
|---|---|---|---|
| 流式读写 | 浏览器、后端、存储客户端 | 每次只处理有限字节，控制内存 | 请求失败后自动续传 |
| `multipart/form-data` | HTTP 请求体格式 | 一个表单同时携带字段与文件 | 大文件被拆成多个可重试请求 |
| HTTP/1.1 chunked 传输编码 | HTTP 报文传输 | 不预先给 Content-Length 也能发送正文 | 业务分片编号、持久化状态、跨请求断点续传；HTTP/2 另有 DATA 帧 |
| 业务分片上传 | 业务协议 | 每片可重试、查询、并发上传 | 最终文件已完整、扫描通过或业务可见 |
| 对象存储 Multipart Upload | 存储服务协议 | 存储端接收多片并按清单完成对象 | 等同于表单 multipart；各厂商限制和校验规则可能不同 |
| 断点续传 | 会话与状态管理 | 恢复后只补缺片/从已确认偏移继续 | 页面保存一个百分比就足够 |

还有一种按连续偏移上传的协议，例如 tus 的 offset 模型。它和“编号分片可乱序上传”是不同契约，不能把前者的 offset ACK 与后者的分片列表随意混用。

## 普通上传、大文件直传、分片上传怎么选

| 场景 | 优先方案 | 优点 | 代价 |
|---|---|---|---|
| 几 MB 的头像/附件 | 一次表单上传 + 后端流式存储 | 简单，接口容易管理 | 大文件失败可能整份重传 |
| 大文件但网络稳定、可接受整份重试 | 单次流式上传或对象存储直传 | 不必维护业务分片元数据 | 长请求、链路超时和失败成本高 |
| 视频、安装包、弱网文件 | 分片 + 续传 + 有限并发 | 失败只补片，可查询进度 | 会话、清理、合并与一致性更复杂 |
| 高流量附件/视频平台 | 服务端授权 + 对象存储分片直传 | 数据不经过业务应用服务器 | 要管理短期授权、CORS、存储回调/查单和最终确认 |
| 已有成熟可续传客户端/服务端生态 | 采用 tus 等标准协议 | 减少自定义协议成本 | 需匹配部署、存储和授权模型 |

文件“大不大”没有统一的 MB 分界线。要看网速、失败率、网关超时、服务内存、磁盘和业务期望。4 MiB 分片、3 个并发只是教学起点，不是通用最优值。

### IO 怎样决定内存和磁盘压力

后端每个活动请求用 8 KiB 缓冲区流式落盘，100 个请求的复制缓冲约为 800 KiB；实际还包括 TLS/网络缓冲、框架对象、连接、线程栈和 multipart 解析开销。不能把这一数字当总进程内存。

前端若同时对 3 个 4 MiB 分片调用 `arrayBuffer()` 算摘要，仅分片数组就可能占约 12 MiB，摘要实现和请求栈还可能复制。一次 `file.arrayBuffer()` 会把整文件读入内存，2 GiB 文件很容易让页面崩溃。

本地合并时，分片合计约 F 字节，合并临时文件再需 F 字节，峰值可能接近 2F；另有并发重试临时片、保留版本与其他会话。写入前应做额度预留，写入中仍处理磁盘满。流式不会消除磁盘占用。

## 完整链路：每一层分别负责什么

~~~text
浏览器：选择 File -> 增量计算文件标识/摘要 -> 初始化上传会话
后端：鉴权、额度检查 -> 固定大小/分片规则 -> 返回 uploadId
浏览器：查询已确认分片 -> 有限并发上传缺片（可暂停、重试）
后端：每片流式写独立临时文件 -> 核对长度/摘要 -> 发布分片并记录元数据
浏览器：所有分片得到 ACK -> 请求 complete
后端：校验清单 -> 唯一合并任务 -> 流式合并/存储侧完成 -> 整文件校验
后端：发布最终对象 -> 持久化 COMPLETE/fileId -> 清理分片
浏览器：查询 COMPLETE -> 将 fileId 关联到业务表单
~~~

**必须区分“浏览器发送完”“服务端接受所有片”“文件已完成”“业务可使用”四件事。** 如果还要病毒扫描、转码、审核，使用 `PROCESSING/READY/REJECTED` 等独立业务状态，不把合并完成伪装成视频已经可播放。

## 先定会话，再写切片代码

| 字段 | 来源与规则 | 为什么需要 |
|---|---|---|
| `uploadId` | 服务端生成不可预测标识 | 定位一次上传；仍须校验所属用户，知道 ID 不等于有权限 |
| `ownerId/tenantId` | 服务端登录上下文 | 防止查询、覆盖、完成或取消别人的会话 |
| `originalName` | 前端展示元数据，后端限制长度 | 展示下载名，绝不能直接拼磁盘路径 |
| `totalSize` | 初始化声明，后续实际验证 | 限额、尾片长度和合并大小校验 |
| `chunkSize/chunkCount` | 服务端按策略决定并冻结 | 防止每次请求换规则、重叠覆盖或无限创建小片 |
| `wholeSha256` | 客户端增量计算，服务端最终重新计算 | 确认成品与选择的文件一致；不是认证凭证 |
| `state/version/expiresAt` | 服务端维护 | 状态迁移、竞争控制、过期清理和恢复 |
| `fileId/storageKey` | 完成后生成并关联 | 客户端拿业务 ID，不接触内部绝对路径 |

编号分片可使用从 0 开始的下标。对第 i 片：`start = i * chunkSize`，`end = min(start + chunkSize, totalSize)`，期望长度是 `end - start`，区间为 `[start, end)`。总片数是 `ceil(totalSize / chunkSize)`。Java 大小/偏移使用 `long`，避免 2 GiB 边界的 `int` 溢出。

示例拒绝空文件；如果业务支持空文件，定义独立的零长度完成流程。不要让“0 个分片”与“漏传所有分片”共享模糊逻辑。分片下标必须为范围内整数；重复下标不能计入总片数。

### 一个可实现的 HTTP 契约

下面是你的业务 API，不是框架内置路由。上传只发原始分片二进制，元数据在初始化与请求头中，免去每片表单封装。

| 接口 | 请求 | 响应/行为 |
|---|---|---|
| `POST /api/uploads` | JSON 名称、总大小、全文摘要；`Idempotency-Key` | 创建或返回同一会话；同用户同键但不同参数返回 409 |
| `GET /api/uploads/{id}` | 鉴权 | 返回状态、固定分片规则、已持久确认的下标、完成 fileId |
| `PUT /api/uploads/{id}/parts/{index}` | `application/octet-stream` + `X-Chunk-SHA256` | 201 首次接受 / 200 同内容重复；均返回 JSON ACK；同下标异内容 409 |
| `POST /api/uploads/{id}/complete` | 鉴权，可无正文 | 200 已完成，或 202 已可靠记录合并任务；重复请求返回同一任务/结果 |
| `DELETE /api/uploads/{id}` | 鉴权 | 幂等取消未完成会话，后台清理；完成文件使用独立业务删除契约 |

GET 与 complete 的会话 JSON 形状一致，例如：

~~~json
{
  "uploadId": "server-generated-id",
  "totalSize": 10243,
  "chunkSize": 4096,
  "chunkCount": 3,
  "wholeSha256": "替换为实际的64位小写十六进制摘要",
  "state": "OPEN",
  "uploadedIndexes": [0, 2],
  "fileId": null
}
~~~

201/200 分片 ACK 可以是 `{"index": 0, "accepted": true}`。401/403 是身份/权限问题，413 是请求太大，422 可表示大小/摘要不符，409 表示内容或状态冲突，429 表示限流；不要把这些都转成 HTTP 200 后让前端猜测。CORS 跨域时，自定义头与 PUT 会触发预检，要按允许来源与凭证策略配置，不能用任意来源开放敏感会话。

初始化也需要幂等：若 POST 成功但响应丢失，前端用**相同**请求键重试，后端靠 `(owner_id, request_key)` 唯一约束找回会话，并比较参数。新生成一个请求键会新建会话，旧片可能变成孤儿。

## 前端切片、并发、进度与暂停

### `File.slice()` 不会替你上传

~~~js
// file 是用户选择的 File；chunkSize 应使用后端返回并冻结的值。
const start = index * chunkSize
const end = Math.min(start + chunkSize, file.size)
const chunk = file.slice(start, end) // Blob 片段，不等于整份 File 的 arrayBuffer。
// 只读当前分片计算摘要；服务端仍需根据收到的实际字节重新计算。
const digest = await crypto.subtle.digest('SHA-256', await chunk.arrayBuffer())
const sha256 = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')

// 原始 Blob 作为 body，不做 Base64；Base64 会增加体积和复制成本。
// 此处仅展示形状，生产仍需超时、鉴权、重试、异常和取消处理。
await fetch(`/api/uploads/${uploadId}/parts/${index}`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha256 },
  body: chunk,
})
~~~

### 为什么不能一次 `Promise.all(几千个分片)`

`Promise.all` 等待结果，不限制任务启动数量。若先为每片创建 `fetch()`，已经启动了所有任务，会放大浏览器内存、连接排队、服务端并发和磁盘压力。

示例用 3 个 worker 从游标逐个领取缺片；一个 worker 完成一片才领取下一片。失败时停止继续调度，取消其他请求并等待退出。服务端还要有全局/租户并发上限，不能把所有保护交给前端。

### 三种进度应该分开显示

| 进度 | 如何计算 | 前端展示 |
|---|---|---|
| 正在发送 | XHR `upload.onprogress` / 相应 Axios 适配器能力 | 当前分片的传输进度，可能回退或失败 |
| 已确认上传 | 对服务端确认的唯一分片长度求和 / 总长度 | 可续传的进度；重复 ACK 不能重复累计 |
| 合并/处理 | 服务端任务状态或可解释的处理进度 | “校验合并中”“转码中”“可使用”，不要伪造 99% |

原生浏览器 Fetch 没有通用的 XHR 式上传进度事件。本章 Fetch 示例按“服务端确认字节”显示，会按片跳动，这是有意义的可靠进度。发送到 100% 也不能直接调用业务“保存成功”。

暂停只是 `AbortController.abort()`，服务端可能已经写完当前片。恢复时重新 GET 状态，取差集再上传；不能只按浏览器内存的 Set 判断。取消整个会话还要调用 DELETE，并定义与上传/合并竞争的状态规则。

### 可下载的浏览器核心代码

代码实现固定并发、分片摘要、有界重试、请求超时、取消、服务端续传查询和等待完成；它不会在知识库页面自动发起上传。

<a href="/examples/uploads/chunk-upload-client.mjs" download>下载完整注释 chunk-upload-client.mjs</a>

::: details 展开浏览器上传核心代码
<<< ../public/examples/uploads/chunk-upload-client.mjs{js}
:::

把文件复制到 Vue 工程的 `src/utils/` 后可以这样调用。`fullSha256` 必须是本次所选文件算出的摘要，`requestId` 应在开始时创建并保存，不能每次网络重试都换新值。

~~~js
import { onUnmounted, ref } from 'vue'
import { uploadFile } from './utils/chunk-upload-client.mjs'

const progress = ref(0)
const state = ref('尚未开始')
let active // 一个页面只运行一次上传；新的上传会先取消旧任务。
let currentUploadId // 实际工程还应按用户/文件标识保存，并在恢复时核对服务端。
let currentFileKey // 演示当前页面内识别文件切换；登录用户切换时也应清空会话。

async function start(file, fullSha256, requestId) {
  active?.abort()
  const controller = new AbortController()
  active = controller
  const fileKey = `${file.size}:${fullSha256}`
  if (currentFileKey !== fileKey) {
    currentUploadId = undefined // 新文件不能复用旧会话，重新执行初始化接口。
    currentFileKey = fileKey
  }
  try {
    const result = await uploadFile(file, {
      fullSha256,
      requestId,
      uploadId: currentUploadId, // 当前页面中仅恢复同一文件时才存在旧 ID。
      signal: controller.signal,
      concurrency: 3,
      onSession(id) {
        if (active === controller) currentUploadId = id
      },
      onProgress(value) {
        if (active !== controller) return // 防止旧任务迟到的回调覆盖新页面状态。
        progress.value = Math.floor(value.confirmedBytes / value.totalBytes * 100)
        state.value = value.state === 'COMPLETE' ? '上传已完成' : '上传或合并中'
      },
    })
    if (active !== controller) return
    // 将 result.fileId 交给业务表单；服务端仍校验文件归属与可用状态。
    console.log('已完成文件标识：', result.fileId)
  } catch (error) {
    if (active !== controller) return
    state.value = controller.signal.aborted ? '已暂停，可查询后续传' : error.message
  }
}

function pause() { active?.abort() } // 不调用删除接口，不丢弃服务端已经接受的片。
onUnmounted(pause) // 页面卸载时清理请求；不意味着服务端任务自动回滚。
~~~

页面刷新以后，普通 `File` 对象不会因保存文件名而自动恢复。通常让用户重新选择文件并校验大小/全文摘要；支持的浏览器也可使用经用户授权的文件句柄，但权限仍可能需要恢复。不能只比较名称、大小、lastModified 就断言一定是同一内容。

### 大文件摘要也要流式，不能只把卡顿搬到 Worker

`crypto.subtle.digest('SHA-256', await file.arrayBuffer())` 仍读取整文件；放到 Worker 只转移计算线程，不降低这份数组的大小。应使用增量摘要实现逐片 `update()`；Worker 主要解决主线程卡顿。

下面是基于 `hash-wasm` 的 Worker 接入片段，需在你的 Vue 工程安装并固定该依赖版本。本知识库不安装它；片段按其官方 `createSHA256/init/update/digest` 契约编写，未与实际上传接口联调。每次任务创建独立 Worker，暂停时 `worker.terminate()`，Promise 包装层要同时 reject 并移除消息/错误监听器。

~~~js
// file-hash.worker.js：放入 Vue 工程源码，经 Vite 打包；不是 public 下直接裸导入 npm 包。
import { createSHA256 } from 'hash-wasm'

self.onmessage = async ({ data: file }) => {
  try {
    const hasher = await createSHA256()
    hasher.init()
    const step = 4 * 1024 * 1024 // 一次只读取 4 MiB，摘要 update 必须按文件顺序。
    for (let offset = 0; offset < file.size; offset += step) {
      const bytes = new Uint8Array(await file.slice(offset, offset + step).arrayBuffer())
      hasher.update(bytes)
      self.postMessage({ type: 'progress', bytes: Math.min(offset + step, file.size) })
    }
    self.postMessage({ type: 'done', sha256: hasher.digest('hex') })
  } catch (error) {
    self.postMessage({ type: 'error', message: String(error.message || error) })
  }
}
// 主线程 new Worker(new URL('./file-hash.worker.js', import.meta.url), { type: 'module' })。
// 一次 Worker 只处理一个文件；done/error/取消后 terminate，防止并行混用同一消息通道。
~~~

各分片 SHA-256 的拼接或再哈希，**不等于原文件 SHA-256**。如果要用 Merkle Tree/分片清单摘要，必须明确那是另一套协议，双方一致实现。全文 hash 不是用户身份，也不能直接证明此用户有权“秒传”别人上传过的文件。

## 后端接收一片：长度、摘要、独立临时文件

顺序建议：

1. 从登录态确定用户，校验会话归属、状态、过期、配额与下标；按会话计算本片应有长度。
2. 为**本次请求**创建唯一临时文件，使用固定缓冲区读取请求体、累计实际长度并增量计算摘要。
3. 长度超出立即停止；EOF 过早也是失败。不能只相信 Content-Length，不能用 `available()` 判断结束。
4. 关闭流后比较长度/摘要；失败仅清理自己的临时文件，不修改已接受分片。
5. 在竞争保护下再次检查会话状态；同下标同内容返回成功，不重复计数；同下标异内容返回冲突。
6. 发布分片并持久化分片记录，成功后 ACK。文件落盘与数据库提交并非一个事务，需要恢复协议。

为什么要两次检查状态？上传可能很慢；请求开始时 OPEN，读完时另一请求已经取消或合并。只在开头检查会让旧请求在取消之后“复活”会话。

下面是 Spring MVC 接入位置，**不是完整可上线 Controller**。`uploadService` 应实现上述协议，`principal` 来自已认证上下文；错误通过统一异常处理映射成前面的状态码。

~~~java
// Spring Boot 2.7 使用 javax.servlet；Boot 3 则是 jakarta.servlet，别混用包名。
@PutMapping(value = "/api/uploads/{id}/parts/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
public ResponseEntity<PartAck> uploadPart(
        @PathVariable String id,
        @PathVariable int index,
        @RequestHeader("X-Chunk-SHA256") String sha256,
        HttpServletRequest request,
        Principal principal) throws IOException {
    // 不调用 request.getInputStream().readAllBytes()；服务层负责有界流式接收。
    try (InputStream body = request.getInputStream()) {
        PartAck ack = uploadService.accept(principal.getName(), id, index, sha256, body);
        // 只能在可靠接受后确认；不能仅把任务放到内存线程池就返回成功。
        return ResponseEntity.status(ack.isDuplicate() ? 200 : 201).body(ack);
    }
}
~~~

本接口使用原始 `application/octet-stream`，不是 `MultipartFile`。若使用后者，Servlet multipart 解析器可能先把请求放入内存或临时磁盘，Controller 的流式代码并不说明整个请求链路都没有缓冲。

### 并发下不能只用 `if (!exists) { write }`

两个请求都可能看到“不存在”，然后同时覆盖同一个 `0.part`。正确办法是每次请求写不同临时文件，验证完成后竞争“发布某个下标”的资格，并用 `(upload_id, part_index)` 唯一约束保护持久记录。客户端提供的 hash 要和服务端实际计算值匹配。

本地单 JVM 示例用每会话一把 `ReentrantLock`，演示临界区。多个实例的锁彼此独立；本地临时目录也彼此独立。上线时需要共享存储/对象存储与共享元数据，或明确且可恢复的上传路由策略，不能只加一个 static Map。

## 断点续传到底续的是什么

续的是**服务端已可靠接受的数据事实**。服务端应记录每片下标、实际大小、摘要、存储键和接受状态；页面重新查询再计算缺片列表。

| 故障点 | 重试/恢复办法 |
|---|---|
| 读片中断，只剩半个临时文件 | 未发布、没有成功记录；清理临时片，整片重试 |
| 已发布分片，响应丢失 | 同 uploadId/index/内容重试；返回已接受，不重复计数 |
| 文件已写入、数据库记录未提交 | 根据存储键、摘要与状态扫描恢复或清理孤儿，不能直接声称已上传 |
| 数据库标记已接受，但文件不存在 | 检查发布顺序/存储可靠性，标记损坏并补传或恢复；不能仅相信分片数量 |
| 页面关闭/刷新 | 保存会话标识，重新选择同一文件，GET 后补缺片 |
| 服务进程重启 | 从数据库恢复会话和任务；只存在 Map 中的数据会丢 |
| 会话已过期 | 明确返回过期，重新初始化，不往被清理的目录继续写 |

生产可采用 `RECEIVING -> STORED -> ACCEPTED` 一类持久记录/暂存对象协议，明确每个阶段的重放规则；具体实现取决于数据库与存储能力。不要把“磁盘 move + SQL commit”写在一个 Java 方法里就当作跨资源原子事务。

## 合并：先校验清单，再唯一执行，最后发布

### 状态机与重复 complete

~~~text
OPEN --所有片确认且抢到合并资格--> MERGING --大小/摘要/发布成功--> COMPLETE
  |                                  |
  +--> CANCELLED / EXPIRED            +--> FAILED（记录原因，可按规则恢复）
~~~

相同 complete 的返回值应能复用。常见数据库抢占是 `UPDATE upload_session SET state='MERGING', version=version+1 WHERE id=? AND state='OPEN' AND version=?`，只有影响行数 1 的请求获得资格。授权、分片是否齐全与状态检查仍要在定义明确的事务/并发协议内完成；单条 SQL 不是全部实现。

如果返回 202，必须已可靠保存合并任务，例如同事务写入 outbox。普通 `@Async` 任务随进程退出丢失，不能作为可靠接受的依据。后台任务使用租约与代次/令牌，续租失败或超时旧工作者不能覆盖新工作者结果；本主题与[支付 outbox](./online-payment.md)相同。

取消与合并也要竞争同一状态。可以规定 MERGING 不支持即时取消，或由后台任务协作取消；无论哪种，都不能出现取消后旧合并线程又把文件发布成功。清理任务同样必须检查状态、租约/版本，不能只凭目录“看起来很旧”就删除。

### 合并算法

1. 验证下标恰好覆盖 `[0, chunkCount)`，每片大小符合冻结规则；只比较 `COUNT(*)` 不够。
2. 按**数字下标**依次读片，流式写入同文件系统的唯一合并临时文件；同时累计总长度与全文 SHA-256。
3. 任意读写失败、磁盘满、长度/摘要错误，不能发布成品；保留可诊断状态，清理/重试有明确边界。
4. 关闭输出流，按存储可靠性要求刷盘或确认对象写入；原子改名/完成对象，得到最终存储键。
5. 以状态/代次条件提交 `COMPLETE` 与 fileId；失败窗口由恢复任务处理。下载接口只暴露已经确认且有权限的对象。
6. 异步清理分片。清理失败应重试，不把已经完成的上传反向改成失败。

Java 的 `Files.move(..., ATOMIC_MOVE)` 在不支持的文件系统上可能抛异常；跨盘、网络盘、对象存储不能假设相同语义。即便 move 原子，也只说明名称发布，不保证断电持久性和数据库状态一起原子提交。本例要求原子移动可用，不静默降级。

### 可直接运行的 Java 核心实验

<a href="/examples/uploads/ChunkUploadDemo.java" download>下载完整中文注释 ChunkUploadDemo.java</a>

~~~powershell
# 保存源码到练习目录；JDK 17 编译出 Java 11 兼容字节码。
javac -encoding UTF-8 --release 11 ChunkUploadDemo.java

# 只用程序自己创建的临时目录，验证后自动清理，不启动网络服务。
java '-Dfile.encoding=UTF-8' ChunkUploadDemo
~~~

预期输出：

~~~text
PASS chunks=3 duplicateAcceptedOnce=true resumedMissing=2 finalBytes=10243
PASS rejected=owner,index,short,long,checksum,conflict,missing,wholeHash,cancelled
LOCAL CORE ONLY: no HTTP, no database, no restart recovery.
~~~

::: details 展开后端分片核心源码与并发验证
<<< ../public/examples/uploads/ChunkUploadDemo.java{java}
:::

实验用 10,243 字节文件、4,096 字节分片，特意产生短尾片；同一片并发提交两次，再从“只有第 0 片”的状态续传第 2、1 片，最后并发 complete 两次，结果必须相同。大小/hash/权限/下标错误与取消会话均被拒绝。小数组和 `readAllBytes` 仅用于实验构造/断言，上传接收和合并使用固定缓冲区。

**示例的取舍：** 合并时持有会话锁，因此同会话 status/cancel 也会等待；适合观察正确的单进程顺序，不适合作为大型服务的异步合并实现。分片元数据只在内存、完成后保留分片到实验清理，没有重启恢复/TTL/配额总账。生产应使用前述持久任务、状态条件、租约和后台清理。

## 为什么“边传边定位写一个文件”也不能省掉状态

可以预分配最终临时文件，再用 `FileChannel.write(buffer, offset)` 定位写每片，省掉最终拼接的一次复制。但代价包括：

- 同下标写入竞争仍存在，范围/长度不合法可能覆盖别的片。
- `write` 可能短写，offset 要随实际写入推进，不能每次都从原 offset 重写。
- 文件看起来大小正确可能只是稀疏空洞，不表示每片都已写入。
- 完成位图、每片摘要、落盘顺序与崩溃恢复仍要设计；不能只检查 `Files.size()`。
- 多实例必须具有一致的随机写存储语义；对象存储通常不支持当本地随机写文件使用。

因此初学阶段更容易验证的是“独立分片 + 有序合并”。性能优化应基于瓶颈数据，不能跳过正确性协议。

## 对象存储直传：业务服务器不搬运文件

~~~text
Vue -> 业务后端：申请上传（鉴权、大小、配额、对象键）
后端 -> 存储：初始化 Multipart，保存 storageUploadId 与业务 uploadId
后端 -> Vue：有限有效期、有限范围的分片授权
Vue -> 存储：并发上传分片，记录 partNumber/ETag/checksum
Vue -> 后端：请求完成
后端 -> 存储：核验分片清单并 complete/query
后端：确认对象大小/完整性/归属 -> 标记完成 -> 返回 fileId
~~~

预签名 URL 是有有效期的访问凭据，不把永久 AccessKey/SecretKey 交给浏览器。服务端决定 bucket/objectKey、权限与生命周期，限制可操作范围；对象存储要按浏览器来源配置 CORS 并暴露所需 ETag/checksum 响应头。

以 S3 类协议为例，partNumber 通常从 1 开始，和本文 0 起始的业务 index 要做明确映射。完成清单需按存储协议携带每片返回的 ETag 等标识；**ETag 不普遍等于文件 MD5**，Multipart/加密等情况会改变其含义。分片最小大小、最大数量、校验机制、Abort/Complete 幂等与“完成结果未知”的处理必须按实际厂商文档实现。

上传完成后由服务端查证对象，而不是相信客户端说“成功”。超时可能代表对象已完成但响应丢失，应查询同一个会话/对象后恢复；残留 Multipart 需要主动 abort 或存储生命周期规则清理。扫描/转码失败属于另一个业务流程，不要重新扣减上传额度或重复创建文件记录。

## 网关、框架、存储都可能先挡住请求

| 层 | 典型检查 | 解决的问题 |
|---|---|---|
| 浏览器 | 内存、最大并发、取消、CORS、文件授权 | 避免页面卡死、无法续传或预检被拒 |
| CDN/WAF/LB | body 大小、请求时限、速率/策略 | 解释请求为什么没到应用就失败 |
| Nginx | `client_max_body_size`、`client_body_timeout`、`proxy_read_timeout`、请求缓冲与临时目录 | 限制每请求、处理慢连接、控制代理磁盘压力 |
| Spring/Servlet | multipart 限制、请求读取时限、线程/连接上限、临时目录 | 防止解析阶段就占满资源；原始 body 接口需要自己的计数限制 |
| 应用服务 | 用户配额、片数、并发/队列、会话过期、hash CPU、合并数 | 防止单租户占满整个服务 |
| 文件/对象存储 | 剩余空间、IOPS、带宽、错误与一致性语义 | 处理磁盘满、短写、远端失败和临时对象累积 |

Nginx 的读超时常表示相邻读取间隔，并非全部上传的总时长；不同产品需查实际配置语义。分片请求上限应略大于分片 payload 所需（若表单方式还包括封装），总文件上限仍由会话控制，不能因为每片小就允许无限总量。

`spring.servlet.multipart.max-file-size/max-request-size` 主要针对 multipart 解析，不能代替原始字节 PUT 的累计上限。`proxy_request_buffering off` 也不是万能开关；需要结合代理、上游协议、重试与存储策略验证链路是否仍会缓冲。

## 具体使用场景与排错

| 场景/现象 | 推荐处理 | 背后的知识 |
|---|---|---|
| 用户上传视频，刷新后重新选文件 | 校验文件身份，GET 已有片，补缺片，查询合并结果 | 会话持久化与断点续传 |
| 10 MB CSV 导入业务表 | 上传与导入分开；文件先保存，解析逐批处理并报告行错误 | IO 资源管理、事务边界、后台任务 |
| 上传到 100% 一直转圈 | 查 complete 状态、合并队列、磁盘空间、扫描任务 | 传输进度不等于业务完成 |
| 偶发文件内容重复/错位 | 查重复写、偏移推进、短写、排序和有效字节长度 | Channel 与并发控制 |
| 413、连接断开或 502 | 逐层定位代理/应用限制和超时，不能只扩大 JVM 堆 | 网络库与网关边界 |
| 大文件让 Java 内存飙升 | 搜索 getBytes/readAllBytes/ByteArrayOutputStream，检查解析器和存储 SDK 缓冲 | 全链路流式处理 |
| 重启后上传 ID 不存在 | 会话/分片记录是否只放 Map，是否具备恢复扫描 | 持久化与崩溃窗口 |
| 多实例轮流报缺片 | 本地磁盘分散在不同机器 | 共享存储与路由模型 |
| 取消后又出现成品 | 旧工作者是否绕过状态/租约条件发布 | 竞态、版本号与 fencing |

文件安全属于实际业务契约：限制扩展名/内容类型并校验实际内容，上传目录不要作为可执行代码目录，原始文件名只作展示，下载设置合适 Content-Type/Content-Disposition 并校验归属。摘要只验证字节一致，不表示内容可信，也不是“秒传权限”。

## 练习与参考

1. 将实验文件长度改为恰好一片、两片整数倍和多一字节，推导每片大小并验证输出。
2. 在一片写入过程中模拟断流，确认不会出现“已接受”记录；重传后只计数一次。
3. 对同 uploadId/index 上传另一份同长度内容，应是 409 语义而非覆盖。
4. 写出“落盘后 DB 前”“最终对象发布后 COMPLETE 前”的恢复规则；说明加 synchronized 为什么解决不了断电。
5. 设计两实例同时 complete、取消与合并竞争、过期清理与续租竞争的条件更新。
6. 做真实链路压测时分别观察：浏览器内存、网关临时目录、应用堆/线程、磁盘、存储 SDK 缓冲与最终文件摘要。

- [MDN Blob.slice](https://developer.mozilla.org/en-US/docs/Web/API/Blob/slice)：切片边界。
- [MDN SubtleCrypto.digest](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/digest)：该 API 不提供流式输入。
- [hash-wasm](https://github.com/Daninet/hash-wasm)：增量 SHA-256 的 init/update/digest 接口。
- [Java 11 Files](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/Files.html)：临时文件、流与原子移动。
- [Spring Boot 2.7 MultipartProperties](https://docs.spring.io/spring-boot/docs/2.7.18/api/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html)：multipart 配置边界。
- [Amazon S3 Multipart Upload](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)：对象存储多片上传与完成。
- [tus 协议](https://tus.io/protocols/resumable-upload)：基于偏移的可续传上传。

推荐回读：[IO 的流式边界](../guide/java/io.md)、[NIO 的缓冲区与短写](../guide/java/nio.md)、[网络库的超时与背压](../guide/java/network-libraries.md)。
