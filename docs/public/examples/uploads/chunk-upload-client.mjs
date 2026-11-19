/**
 * 浏览器分片上传核心示例（ES module，可在 Vue 中导入）。
 * 依赖本章定义的 /api/uploads 商户业务接口；知识库本身没有实现这些 HTTP 路由。
 * fullSha256 需由调用方用 Worker + 增量 SHA-256 算出，不能用整个大文件 arrayBuffer。
 */

class HttpFailure extends Error {
  constructor(status) {
    super(`上传接口失败：HTTP ${status}`)
    this.status = status
  }
}

/** 可取消的退避/轮询间隔，完成后清理监听器。 */
function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('已暂停', 'AbortError'))
    const onAbort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', onAbort)
      reject(new DOMException('已暂停', 'AbortError'))
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * 有界重试：本章三个写入端点都必须按约定实现幂等，才允许这样重试。
 * 网络错误/408/429/5xx 可暂时重试；401、403、413、422、409 等交给用户/状态查询处理。
 * 生产应进一步解析 Retry-After、错误码及加入随机抖动，避免很多客户端同步重试。
 */
async function request(path, options, outerSignal) {
  for (let attempt = 0; attempt < 3; attempt++) {
    outerSignal.throwIfAborted()
    const current = new AbortController()
    const onAbort = () => current.abort()
    outerSignal.addEventListener('abort', onAbort, { once: true })
    const timeout = setTimeout(() => current.abort(), 45_000)
    try {
      const response = await fetch(path, {
        ...options,
        credentials: 'same-origin', // 按项目实际认证/CSRF 方案接入统一请求层。
        signal: current.signal,
      })
      if (!response.ok) {
        // 错误体不作为业务成功；不在日志中输出可能含敏感信息的整个响应。
        if (response.body) await response.body.cancel()
        throw new HttpFailure(response.status)
      }
      // 本例约定所有成功响应（包括分片 ACK、202）都带小型 JSON。
      return await response.json()
    } catch (error) {
      if (outerSignal.aborted) throw error
      const retryable = error instanceof HttpFailure
        ? error.status === 408 || error.status === 429 || error.status >= 500
        : error instanceof TypeError || error.name === 'AbortError'
      if (!retryable || attempt === 2) throw error
    } finally {
      clearTimeout(timeout)
      outerSignal.removeEventListener('abort', onAbort)
    }
    await delay(300 * (2 ** attempt), outerSignal)
  }
}

/** 这里只读取一个分片，服务端 chunkSize 会先经过本地上限检查。 */
async function chunkSha256(blob, signal) {
  signal.throwIfAborted()
  const bytes = await blob.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  signal.throwIfAborted() // digest 不能直接取消；计算后再检查，不发送过期请求。
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
}

/** 不只靠 TypeScript 类型；续传前检查服务端会话是否仍对应当前文件。 */
function validateSession(session, file, fullSha256) {
  const states = ['OPEN', 'MERGING', 'COMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED']
  if (!session || !/^[a-zA-Z0-9-]{1,80}$/.test(session.uploadId)
      || session.totalSize !== file.size || session.wholeSha256 !== fullSha256
      || !Number.isSafeInteger(session.chunkSize) || session.chunkSize < 1
      || session.chunkSize > 4 * 1024 * 1024
      || session.chunkCount !== Math.ceil(file.size / session.chunkSize)
      || session.chunkCount > 10_000
      || !states.includes(session.state) || !Array.isArray(session.uploadedIndexes)) {
    throw new Error('上传会话与当前文件不匹配，或服务端响应格式错误')
  }
  for (const index of session.uploadedIndexes) {
    if (!Number.isInteger(index) || index < 0 || index >= session.chunkCount) {
      throw new Error('已上传分片下标无效')
    }
  }
  if (session.state === 'COMPLETE' && typeof session.fileId !== 'string') {
    throw new Error('完成响应缺少文件标识')
  }
}

/**
 * 主入口：同一 uploadId + index 的同内容 PUT 必须幂等；冲突内容返回 409。
 * requestId 由页面创建并保存，以便初始化响应丢失时用相同幂等键找回会话。
 * onProgress 是“后端已确认字节数”，不是 TCP 实时发送进度，也不是文件已合并。
 */
export async function uploadFile(file, {
  fullSha256,
  requestId,
  uploadId,
  signal,
  concurrency = 3,
  onSession = () => {},
  onProgress = () => {},
}) {
  if (!Number.isSafeInteger(file.size) || file.size <= 0
      || !/^[a-f0-9]{64}$/.test(fullSha256)
      || !/^[a-zA-Z0-9-]{1,80}$/.test(requestId)
      || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 4) {
    throw new Error('文件、摘要、请求标识或并发数不合法')
  }
  // 本例拒绝空文件；生产可提供独立空文件流程，不制造“零个分片也能合并”的歧义。
  const group = new AbortController()
  const stop = () => group.abort()
  signal.addEventListener('abort', stop, { once: true })
  if (signal.aborted) group.abort()
  const workers = []
  let mergeTimeout
  let mergeTimedOut = false
  const startMergeDeadline = () => {
    // 覆盖 complete、重试、轮询间隔和正在等待的响应，不只是轮询前检查时间。
    mergeTimeout = setTimeout(() => {
      mergeTimedOut = true
      group.abort()
    }, 120_000)
  }
  try {
    let session = uploadId
      ? await request(`/api/uploads/${encodeURIComponent(uploadId)}`, {}, group.signal)
      : await request('/api/uploads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
          // name 只是展示元数据，后端不能直接拼接成磁盘路径。
          body: JSON.stringify({ name: file.name, totalSize: file.size, wholeSha256: fullSha256 }),
        }, group.signal)
    validateSession(session, file, fullSha256)
    onSession(session.uploadId) // 保存 ID；刷新后仍需重新选择/授权访问本地文件。
    const base = `/api/uploads/${encodeURIComponent(session.uploadId)}`
    const confirmed = new Set(session.uploadedIndexes)
    const sizeOf = index => Math.min(session.chunkSize, file.size - index * session.chunkSize)
    let confirmedBytes = Array.from(confirmed).reduce((sum, index) => sum + sizeOf(index), 0)
    onProgress({ confirmedBytes, totalBytes: file.size, state: session.state })
    if (session.state === 'COMPLETE') return session
    if (!['OPEN', 'MERGING'].includes(session.state)) throw new Error(`会话不可继续：${session.state}`)

    if (session.state === 'OPEN') {
      // 不为所有分片同时创建 Promise；固定数量 worker 逐个从游标取任务。
      let cursor = 0
      const worker = async () => {
        while (cursor < session.chunkCount) {
          const index = cursor++ // 此处没有 await；JS 单事件循环内领取不同下标。
          if (confirmed.has(index)) continue
          group.signal.throwIfAborted()
          const start = index * session.chunkSize
          const chunk = file.slice(start, Math.min(start + session.chunkSize, file.size))
          const sha256 = await chunkSha256(chunk, group.signal)
          await request(`${base}/parts/${index}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha256 },
            body: chunk, // 原始二进制，不转 Base64，也不把整个 File 读入内存。
          }, group.signal)
          confirmed.add(index)
          confirmedBytes += chunk.size // 仅服务端确认后累计，同一 worker 不重复确认同下标。
          onProgress({ confirmedBytes, totalBytes: file.size, state: 'OPEN' })
        }
      }
      for (let i = 0; i < concurrency; i++) workers.push(worker())
      await Promise.all(workers)
      startMergeDeadline()
      session = await request(`${base}/complete`, { method: 'POST' }, group.signal)
      validateSession(session, file, fullSha256)
    } else {
      startMergeDeadline() // 从已有 MERGING 会话恢复时，也限制本次页面的等待时间。
    }

    // complete 可直接 COMPLETE，也可返回 202 + MERGING。等待后端校验/发布结束。
    while (session.state === 'MERGING') {
      await delay(1000, group.signal)
      session = await request(base, {}, group.signal)
      validateSession(session, file, fullSha256)
    }
    if (session.state !== 'COMPLETE') throw new Error(`文件未完成：${session.state}`)
    onProgress({ confirmedBytes: file.size, totalBytes: file.size, state: 'COMPLETE' })
    return session // 仅此时 fileId 可交给后续业务，具体可见性仍由后端扫描/审核规则决定。
  } catch (error) {
    group.abort() // 一个 worker 失败，停止继续调度和取消其他请求。
    await Promise.allSettled(workers) // 等待其退出，避免 UI 报错后还持续推进本地进度。
    if (mergeTimedOut) throw new Error('合并结果待确认，请稍后查询同一上传会话')
    throw error
  } finally {
    clearTimeout(mergeTimeout)
    group.abort()
    signal.removeEventListener('abort', stop)
    // abort 只停止浏览器等待，不代表服务端已经回滚；下次仍以 status 为准。
  }
}
