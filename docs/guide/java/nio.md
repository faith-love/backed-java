# NIO：Buffer、Channel、Selector 与异步 I/O

先记住三个分工：**Buffer 保存一次读写要处理的字节；Channel 连接文件或网络；Selector 观察多个非阻塞连接何时值得继续操作。** 它们解决的是数据分批处理和连接等待的成本，不会自动让磁盘、网络或数据库变快。

前置知识见 [I/O 与文件处理](./io.md)。实际项目使用哪种封装见 [网络库与 HTTP 客户端](./network-libraries.md)，完整业务场景见 [大文件与分片上传](../../examples/large-file-upload.md)。本章 Java 代码兼容 Java 11；完整示例可用 JDK 17 的 `javac --release 11` 编译。

## 从前端经验理解阻塞、非阻塞和异步

浏览器 `await fetch()` 会让当前异步函数等待，但 JavaScript 事件循环还能处理其他任务。Java 中的 `socket.getInputStream().read()` 若尚无数据，则可能直接阻塞当前 Java 线程。两者在“我需要等结果”的业务表达上相似，在底层资源占用上不同。

| 维度 | 含义 | Java 例子 | 不能得出的结论 |
|---|---|---|---|
| 阻塞 | 当前调用可能一直等到有进展、结束或错误 | 阻塞 `Socket`、普通文件 `FileChannel.read` | 阻塞不等于整个 JVM 都停住 |
| 非阻塞 | 当前调用即使暂时无法处理数据，也可以立即返回 | `SocketChannel.configureBlocking(false)` 后 `read` 返回 `0` | 返回不等于已经拿到完整业务消息 |
| 同步调用 | 调用方直接得到这次操作的结果或进度 | `read` 返回读到的字节数 | 同步不必然等于阻塞；非阻塞 `read` 也直接返回 |
| 异步完成 | 提交操作后，由 Future 或回调告知完成 | `AsynchronousFileChannel.read` | 不保证底层没有工作线程或阻塞系统调用 |

“同步/异步”有时也用于描述业务任务的调用关系，阅读文档时先确定它讨论的是 I/O 操作还是业务工作流。`CompletableFuture.supplyAsync(() -> blockingRead())` 是把阻塞操作交给别的线程；并没有把这个文件 API 改造成非阻塞 API。

### BIO、NIO、AIO 怎么选

| 常用叫法 | 核心方式 | 解决什么问题 | 优点 | 代价与场景 |
|---|---|---|---|---|
| BIO | 阻塞流、阻塞 Socket | 按顺序处理一条连接/文件 | 编码直观、异常路径清晰 | 大量空闲连接可能占很多平台线程；常规文件处理和普通 Servlet 业务很常见 |
| NIO | Buffer + Channel；网络可配 Selector | 少量线程管理大量网络等待 | 减少空闲连接的线程成本，精确控制背压 | 状态机、半包、写队列和资源管理更复杂；高并发网络服务常由 Netty 等封装 |
| AIO | 异步通道 + Future/CompletionHandler | 让提交方不必原地等待操作完成 | 便于组织完成回调 | 实现因操作系统/provider 而异，也可能使用线程池；要管理完成顺序和缓冲区生命周期 |

**NIO 是一组 API，不是“全部非阻塞”的保证。** `Files.readAllBytes` 会把文件读到内存，`Files.copy` 是同步调用，普通 `FileChannel` 不能注册到 Selector。给这些调用外面套一个响应式返回值，也不会消除其中的阻塞。

## Buffer：先掌握三个下标

以 `ByteBuffer.allocate(8)` 为例，它分配 8 字节缓冲区：

| 属性 | 含义 | 写入 3 字节后 | 调用 `flip()` 后 |
|---|---|---|---|
| `capacity` | 容量，上限不随读写变化 | 8 | 8 |
| `position` | 下一次相对读/写的位置 | 3 | 0 |
| `limit` | 这次允许访问到哪里，右边界不包含 | 8 | 3 |
| `remaining()` | `limit - position` | 5 个可写位置 | 3 个可读字节 |

保持 `0 <= position <= limit <= capacity`。所谓“读模式/写模式”是我们对这些下标约定的叫法，Buffer 没有一个自动切换的模式开关。

~~~java
import java.nio.ByteBuffer;

// 分配后：position=0，limit=8，capacity=8，可继续写入。
ByteBuffer buffer = ByteBuffer.allocate(8);
buffer.put((byte) 'A');
buffer.put((byte) 'B');
buffer.put((byte) 'C'); // position=3，只写入了 3 个有效字节。

buffer.flip(); // limit=3、position=0：把“刚写过的区域”交给读取方。
byte first = buffer.get(); // 读到 A，position=1，B/C 仍未消费。

buffer.compact(); // 将 B/C 搬到开头，position=2、limit=8，可从末尾追加。
buffer.put((byte) 'D'); // 现在有效内容是 B/C/D。
buffer.flip(); // 再次切换后，读取方只看到 B/C/D。
~~~

### 每个操作到底解决什么问题

| 操作 | 下标/数据变化 | 什么时候用 | 容易踩的坑 |
|---|---|---|---|
| `flip()` | `limit = position`，`position = 0`，丢弃 mark | 写了数据后交给读方 | 连续 flip 两次会把 limit 变成 0 |
| `clear()` | `position = 0`，`limit = capacity`，丢弃 mark；不擦除原字节 | 所有有效数据已处理，整块可重用 | 还有半包就 clear 会逻辑上丢掉它；也不是敏感数据清零 |
| `compact()` | 把未读部分移到开头，position 位于其末尾，limit 回到容量 | 还有未完整处理的数据，要继续追加 | 数据搬移有成本；只读 Buffer 不支持 compact |
| `rewind()` | position 回 0，limit 不变，丢弃 mark | 重新读取当前有效区域 | 不把容量全部开放；与 clear 不同 |
| `mark()/reset()` | 记住/恢复 position | 试读长度头，发现正文不完整则回退 | mark 被 clear/flip 或某些下标调整丢弃后 reset 会报错 |
| `slice()` | 创建共享原存储的区间视图，下标独立 | 在同一块数据上处理子区域 | 修改共享字节仍互相可见；共享不等于线程安全 |
| `duplicate()` | 共享存储，复制当前位置/上限等初始状态 | 同一数据需要独立读指针 | 只隔离下标，不隔离底层内容 |
| `asReadOnlyBuffer()` | 禁止通过该视图修改 | 暴露只读访问入口 | 原可写 Buffer 改动仍会影响这个视图 |
| `get(index)/put(index, value)` | 绝对访问，不推进 position | 看报文某个固定字段 | 别和会推进位置的相对 get/put 混用 |

`ByteBuffer` 不是线程安全对象。同一缓冲区的 position 和字节内容都可能竞争。若把 buffer 交给另一个线程异步写盘，原线程不能立即 `clear()` 后重用；要等完成、复制数据，或通过严格的所有权转移管理。

### `wrap`、`array`、字节序和文本

- `ByteBuffer.wrap(bytes)` **共享**传入数组，通常不会复制整个数组。外部改数组会改变 Buffer 的内容。
- `array()` 并不代表“当前有效消息”。普通 heap buffer 的底层数组可能还含未用容量；slice 还涉及 `arrayOffset()`，direct/只读 buffer 也可能不允许访问数组。传输时以 `position/limit/remaining` 为准。
- 长度头、整数等多字节字段要约定字节序。`ByteBuffer` 默认大端，示例仍显式 `order(ByteOrder.BIG_ENDIAN)`；创建视图后也应明确确认/设置其字节序。
- UTF-8 汉字可能跨两次读取。`new String(每次读到的字节)` 可能产生乱码；应在完整业务帧上解码，或使用保持状态的 `CharsetDecoder` 做增量解码。
- 解码器要决定非法编码如何处理。业务协议一般应明确拒绝非法序列，不要默默替换后还当作原始消息。

## Channel：访问哪一种资源

| Channel | 对应资源 | 解决的问题 | 特别注意 |
|---|---|---|---|
| `FileChannel` | 文件 | 定位读写、文件锁、映射、通道传输 | 不是 SelectableChannel；文件位置和线程间共享要管理 |
| `SocketChannel` | TCP 连接 | 双向字节流读写 | TCP 没有应用消息边界；可设阻塞/非阻塞 |
| `ServerSocketChannel` | TCP 监听端 | 接受新连接 | 非阻塞 accept 暂时无连接时返回 null |
| `DatagramChannel` | UDP 数据报 | 按数据报发送和接收 | UDP 可能丢失、重复、乱序；接收缓冲太小会截断报文 |
| `Pipe.SourceChannel/SinkChannel` | JVM 内管道 | 连接生产者和消费者 | 是一种通信工具，不代替业务持久队列 |
| `AsynchronousFileChannel` | 文件 | 提交定位读写并等待异步完成 | 通过显式位置读写，没有共享隐式 position；未完成前别复用 Buffer |
| `AsynchronousSocketChannel` | TCP 连接 | 连接和读写的异步完成 | 仍要处理协议边界、超时、缓冲区生命周期与关闭 |

### 一次 read/write 并不等于完成

`channel.read(buffer)` 返回的是**这一次读入的字节数**。常见含义如下：

| 返回值/状态 | 含义 | 下一步 |
|---|---|---|
| `> 0` | 有字节进入 buffer，position 已前移 | 解析可处理部分；不足一帧则保留 |
| `0` | 这次没有读入字节，可能暂无数据或 buffer 没有剩余空间 | 检查 buffer；非阻塞 Socket 返回事件循环等待 |
| `-1` | 输入结束 | 检查是否有截断帧，按协议关闭或处理半关闭 |
| 抛异常 | I/O 失败或通道状态不对 | 记录原因，清理通道、注册键及应用资源 |

`channel.write(buffer)` 也可能只写了一部分；position 前进多少就表示本次接受了多少字节。Socket 写成功通常只表示数据进入本机协议栈，不代表对方已经持久化或处理。应用层确认仍需自己的协议。

对于阻塞文件，可循环直到本段 `hasRemaining()` 为 false，并明确处理无进展/异常。对于非阻塞网络，**不能用忙循环一直等 write 变成非零**；应把未写完的 buffer 放进有上限的连接写队列，关注 OP_WRITE，下一次可写时继续。

文件的相对读写推进通道 `position`；`read(buffer, offset)`、`write(buffer, offset)` 使用显式位置、不改变该共享位置。并行分片写文件可考虑定位写，但必须防止重叠区间、校验每片长度，并定义完成标记何时可靠落盘。

## TCP 的半包、粘包：为什么需要消息协议

假设发送方先发送 `hello`，再发送 `world`。接收方可能一次读到 `helloworld`，也可能先读到 `he`，再读到 `llowor`，最后读到 `ld`。TCP 保证连接内字节的顺序与可靠传输语义，**不保证一次 write 对应一次 read**。

“半包/粘包”是业务把一串字节恢复成消息时的问题，并不代表 TCP 把业务消息弄坏了。

| 定界方式 | 例子 | 适用场景 | 代价 |
|---|---|---|---|
| 固定长度 | 每条记录 32 字节 | 简单设备协议 | 变长内容不方便，可能浪费空间 |
| 分隔符 | 每行以换行结束 | 文本行协议 | 内容需要转义；必须限制最大行长 |
| 长度前缀 | 4 字节长度 + 正文 | 二进制业务消息 | 校验负数、溢出、超大长度及不完整消息 |
| 自描述协议 | HTTP 等 | 标准应用通信 | 应使用成熟解析器，不重新拼一个不完整 HTTP 协议 |

完整示例采用长度前缀。协议最大正文 64 字节，因此最多只为一条未完成消息保留 68 字节。真实系统还应限制每连接空闲时间、未完成帧停留时间、每次循环处理量和连接总数；单帧有上限不等于全系统内存有上限。

## Selector：让少量线程管理许多连接

Selector 的思路像“登记感兴趣的事件，再统一等待通知”：没有可读数据的连接无需每个都占一个平台线程等待。它是 **就绪通知**，不是“业务操作已完成”的回调。

| 事件 | 含义 | 应做什么 | 常见误区 |
|---|---|---|---|
| `OP_ACCEPT` | 监听通道可能可以接受连接 | accept，设置非阻塞，为新连接注册读事件 | accept 仍可能返回 null；新 SocketChannel 需配置非阻塞 |
| `OP_CONNECT` | 非阻塞连接过程有进展 | 调用 `finishConnect()`，处理成功/失败 | 事件出现不意味着连接一定成功 |
| `OP_READ` | 可以尝试读取，或观察到 EOF/错误 | read，按返回值处理，解析完整帧 | “可读”不保证恰好有完整业务消息 |
| `OP_WRITE` | 可以尝试写入一部分 | 排出有界写队列中的数据 | 长期关注写事件会频繁唤醒，造成 CPU 空转 |

### 一个连接的处理流程

~~~text
创建 Selector，ServerSocketChannel 设为非阻塞并注册 OP_ACCEPT
  -> select 等待就绪键
  -> 从 selectedKeys 移除当前已处理键
  -> accept 时创建连接状态（读 Buffer、解码器、写队列）
  -> read：字节进入 Buffer；解析完整帧，compact 留住半包
  -> 业务结果进入有界写队列；有待发送数据时开启 OP_WRITE
  -> write：发送部分或全部；发送完毕后取消 OP_WRITE
  -> EOF/异常/超时：取消键、关闭连接、释放缓冲和业务资源
~~~

`selectedKeys()` 中的键不会在处理后自动消失，要用迭代器 `remove()` 等方式移除；否则可能重复处理旧的就绪记录。`select()` 也可能返回 0（例如被 wakeup 或达到超时），事件循环要允许这一情况。

工作线程有新结果要送回事件循环时，常见做法是把操作投递到线程安全任务队列，再调用 `selector.wakeup()`，由所属事件循环统一修改连接状态。只调用 wakeup 不会自动执行你的业务代码；还需要真正消费任务队列。

下面只展示**一个非阻塞 Socket 的写事件分支**。其中 `socket`、`key`、`pendingWrites` 均由连接状态管理，队列已在上游施加字节数上限；不是可以直接上线的完整服务器。

~~~java
// 由同一个事件循环独占修改连接的 pendingWrites 和 interestOps。
while (!pendingWrites.isEmpty()) {
    ByteBuffer head = pendingWrites.peek();
    int written = socket.write(head); // 可能只写一部分，或返回 0。
    if (head.hasRemaining()) {
        // 剩余数据原样保留；等待下次可写事件，不在这里忙等。
        // 生产实现还应按本轮字节数/处理次数限额，让其他连接获得机会。
        break;
    }
    pendingWrites.remove(); // 只移除确实完全写出的 Buffer。
}

if (pendingWrites.isEmpty()) {
    // Socket 常常一直“可写”；无待发送数据就取消关注，避免空转。
    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
} else {
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
}
~~~

若这个分支抛异常，外层连接生命周期管理必须取消 key、关闭 socket 并丢弃/通知失败的待发送任务。若需要严格公平，还要限制每轮最多写多少字节；示例注释中的限额并未在片段实现。

### 事件循环里哪些工作不能直接做

同一循环可能负责许多连接。阻塞 JDBC、远程阻塞 HTTP、读取大文件、压缩视频、`Thread.sleep`、等待 `Future.get()` 都可能让其他连接一起卡住。

处理方案不是“每条消息 new Thread”，而是将慢业务交给有界工作池，并定义队列满时的拒绝/降速行为。结果投递回连接所属事件循环；若消息顺序重要，要按连接/业务序列恢复顺序。工作池转移了等待位置，但也新增排队、取消、超时、上下文和生命周期问题，参见 [线程池](./thread-pools.md)。

### 背压：下游慢时不要无限缓存

例如接收端每秒只能消费 1 MB，而你每秒产生 20 MB 响应；就算非阻塞写不会占住线程，剩下的 19 MB 仍需要放到某个地方。无限写队列最终会耗尽内存。

应限制单连接/全局待发送字节数，达到高水位后暂停生产或减缓读取，降到低水位再恢复；同时限制在途业务任务、设置慢连接超时和过载策略。暂停读取也要结合协议设计，避免阻断双方必须交换的控制消息。框架提供的 writable 信号是执行这些策略的依据，不会代替业务主动处理它。

## Heap、Direct、Mapped：内存放在哪里

| 类型 | 创建方式 | 可能解决的问题 | 成本与限制 |
|---|---|---|---|
| Heap Buffer | `ByteBuffer.allocate` | 普通 Java 内存计算和小型缓冲 | 堆内存受 GC 管理；底层 I/O 路径可能需要额外拷贝 |
| Direct Buffer | `ByteBuffer.allocateDirect` | 某些本地 I/O 路径减少中间拷贝 | 分配/释放成本较高，占用堆外内存；不是 GC 完全无关，也不是越多越好 |
| Mapped Buffer | `FileChannel.map` | 以映射内存方式随机访问文件 | 缺页仍可能阻塞；地址空间、映射生命周期与平台文件锁定要考虑 |

Direct Buffer 适合较大、长时间复用并经测量收益明确的缓冲；每次小请求都重新 allocateDirect 可能更慢。Java 11 没有通用公开 API 让任意 `MappedByteBuffer` 随时确定性 unmap；尤其 Windows 上映射仍存活时删除/替换文件可能受影响，不要依靠内部 Unsafe 技巧作为初学者默认方案。

`ByteBuffer` 的下标/容量使用 `int`，不能为一个超大文件创建“同样大小的单个 ByteBuffer”。使用固定大小缓冲或分窗口处理；文件位置和总字节数用 `long`。映射大文件也需要窗口划分与明确的生命周期管理。

### “零拷贝”到底少了什么

`FileChannel.transferTo/transferFrom` 可能利用操作系统更高效的数据传输路径，减少经过用户态缓冲区的复制。它们**不承诺每个平台、每种通道、每次调用都真的零拷贝**。TLS 加密、压缩、校验转换等还可能要求用户态处理。

每次 transfer 返回实际传输量，也可能为 0；必须更新偏移、循环处理并提供无进展时的等待/缓冲复制回退策略。不能写成 `transferTo(0, size, target)` 后直接宣布整文件成功，更不能在返回 0 时无休止自旋。

大文件上传通常需要校验与持久化语义，“少一次拷贝”和“字节完整、校验通过、可恢复”是不同问题。`flush()`、通道关闭和 `FileChannel.force()` 也不能代替数据库业务事务；force 是否满足故障持久化要求仍受文件系统、设备及元数据发布策略影响。

## AIO：异步文件操作还要管理什么

`AsynchronousFileChannel` 可以通过 `Future<Integer>` 或 `CompletionHandler<Integer, A>` 得到某次读写结果。多个操作可能以不同于提交的顺序完成，仍可能短读/短写，需要正确推进偏移和 Buffer。

~~~java
// 教学片段：把读操作提交出去；buffer 和 channel 必须活到完成之后。
// Java 11：channel 是已经打开的 AsynchronousFileChannel。
ByteBuffer buffer = ByteBuffer.allocate(8192);
Future<Integer> pending = channel.read(buffer, 0L); // 显式文件偏移。

// 这里可以做与 buffer 无关的其他工作；不能 clear/reuse 这个 buffer。
int read = pending.get(); // get 自身会阻塞当前线程，此处展示如何取得结果。
if (read > 0) {
    buffer.flip(); // 操作完成后，才读取实际写进来的那一段。
    consume(buffer); // 业务方法占位：本次结果不一定等于完整文件。
}
~~~

该片段省略了超时、中断、异常、EOF、后续读取和资源关闭，完整任务应定义这些路径。若使用回调，回调内也不要长期占住完成线程池。是否使用 OS 异步设施由 provider/系统决定，不能仅从类名推断。

Java 21 的虚拟线程可以降低许多阻塞网络任务的线程成本，是另一个选项；本知识库示例兼容 Java 11，因此不使用虚拟线程。虚拟线程也不会无限增加磁盘、数据库连接和远端服务容量。

## 完整可运行例：半包保留与文件读写

这个程序按同一协议发送两条消息，先只送两字节帧头，再送部分正文，最后一次送入其余两帧内容。解码器通过 `mark/reset/compact` 留下未完成数据；再把这些字节用 `FileChannel` 写入临时文件，并每次最多读取 5 字节验证同一解码逻辑。

它还拒绝超大长度和 EOF 截断，最后清理自己创建的临时文件。这里的文件很小，拼接数组仅用于构造教学输入；真实大文件采用固定缓冲区，不能复制整个文件到数组。

<a href="/examples/io/NioBufferChannelDemo.java" download>下载完整中文注释示例 NioBufferChannelDemo.java</a>

~~~powershell
# 在练习目录保存源码；使用 JDK 17 编译成 Java 11 兼容字节码。
javac -encoding UTF-8 --release 11 NioBufferChannelDemo.java

# 运行本地文件与协议实验，不访问网络。
java '-Dfile.encoding=UTF-8' NioBufferChannelDemo
~~~

预期输出：

~~~text
PASS messages=2 fragmented=true fileRoundTrip=true oversizedRejected=true truncatedRejected=true
~~~

::: details 展开完整源码
<<< ../../public/examples/io/NioBufferChannelDemo.java{java}
:::

示例验证缓冲状态和文件读回，不是实际 Socket 压测，也没有验证选择器在目标平台的性能。不要据此声称 NIO 必然比普通流更快。

## 真实场景怎么选

| 场景 | 优先考虑 | 原因与边界 |
|---|---|---|
| 普通业务附件上传、CSV 导入 | 框架上传接口 + 流式读写 | 主要问题是大小上限、存储、校验和失败恢复，通常不用手写 Selector |
| 合并分片、定位写文件 | Files/流或 FileChannel | 能按块处理大文件；是否并行写要看磁盘与一致性设计 |
| 大量长连接、设备 TCP 协议 | Netty 等成熟框架 | 复用事件循环和编解码器，仍需长度上限、背压、超时 |
| 普通外部 HTTP 调用 | HttpClient、OkHttp、Apache 等 | 协议、TLS、连接池已有封装，无需自己拼 Socket |
| 大文件随机访问索引 | FileChannel 或窗口映射，经基准测试再选 | 需要权衡缺页、堆外内存和文件生命周期 |
| 少量命令行文件脚本 | 阻塞流/Files | 清晰可靠，NIO 状态机复杂度通常不值得 |

## 常见问题与排查入口

| 现象 | 优先检查 |
|---|---|
| CPU 空转但没有业务流量 | 长期注册 OP_WRITE、read/write 返回 0 后忙循环、selectedKeys 未移除 |
| 偶发少字节、文件损坏 | 未处理短写、写出了整个容量而非有效区、提前 clear/reuse 缓冲 |
| 中文偶发乱码 | UTF-8 被 read 边界切断却逐块转字符串、编解码字符集不一致 |
| 只有第一条消息正常 | 解析只取一帧、未继续消费同次输入里的其他消息 |
| 客户端一直等待 | 消息长度不一致、未 flip 导致写 0、EOF 半包未检测、未发送应用确认 |
| 内存持续增长 | 单帧/连接/写队列/在途任务无上限，Direct Buffer 或映射生命周期过长 |
| 所有连接一起变慢 | 在事件循环中读大文件、做 JDBC、等待 Future 或执行长时间 CPU 工作 |

## 动手练习与官方资料

1. 把完整示例的 `compact()` 改成 `clear()`，说明哪一次输入会丢字节，并观察失败；练习后恢复。
2. 增加空正文、多个连续消息、恰好最大长度、负数长度、非法 UTF-8 等用例，解释预期结果。
3. 对照文件写循环，写出非阻塞 Socket 返回 0 时“保存剩余内容并等待事件”的状态转换。
4. 给每个连接设计最大帧长、最大写队列字节数、任务并发数和超时，说明它们分别保护什么资源。
5. 阅读分片上传专题，指出哪些地方使用文件流即可，哪些地方真正需要考虑网络库的非阻塞线程模型。

- [Java 11 Buffer](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/Buffer.html)：position、limit 与状态操作。
- [Java 11 ByteBuffer](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/ByteBuffer.html)：视图、字节序和直接缓冲。
- [Java 11 FileChannel](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/channels/FileChannel.html)：定位读写、传输和映射契约。
- [Java 11 Selector](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/channels/Selector.html)：选择键和就绪集合。
- [Java 11 AsynchronousFileChannel](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/channels/AsynchronousFileChannel.html)：异步文件操作与线程池语义。

下一步：[网络库与 HTTP 客户端](./network-libraries.md)，或进入 [大文件与分片上传](../../examples/large-file-upload.md)。
