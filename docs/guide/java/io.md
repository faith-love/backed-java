# I/O：字节、字符、文件与资源生命周期

I/O 是 Input/Output，即程序从外部读入数据、向外部写出数据。读上传请求、写图片、读取 CSV、下载报告、调用 HTTP 服务都属于 I/O。学习它要回答四个问题：**数据按什么单位处理、等待时线程在做什么、一次占用多少内存、失败后谁关闭资源和清理半成品**。

本章以 Java 11 API 为基线，完整示例使用 JDK 17 的 `javac --release 11` 编译。建议顺序：[本章](./io.md) → [NIO：Buffer、Channel 与 Selector](./nio.md) → [网络库选型](./network-libraries.md) → [大文件与分片上传](../../examples/large-file-upload.md)。集合的 [Stream](./streams.md) 是另一套数据处理 API，不是文件输入输出流。

## 1. 先建立数据流的方向

### 解决什么问题

“上传为什么还要 InputStream，上传不是输出吗？”方向要站在**当前 Java 程序**看：浏览器把文件传给后端，对后端是输入；后端把收到的字节写入磁盘，对后端是输出。

~~~text
浏览器 File / Blob
  → HTTP 请求体
  → 后端 InputStream      // 程序从请求中读入字节
  → 有限大小的 byte[]     // 只保存正在处理的一小块
  → 后端 OutputStream     // 程序向目标文件写出字节
  → 文件系统 / 对象存储

下载时反过来：磁盘 InputStream → 缓冲块 → HTTP 响应 OutputStream → 浏览器。
~~~

| 分类维度 | 常见类型 | 解决的问题 | 典型使用场景 |
|---|---|---|---|
| 数据单位 | 字节流、字符流 | 是否解释字符编码 | 图片用字节；日志、CSV 用明确编码的字符 |
| 数据来源/去向 | 文件、内存、Socket、HTTP body | 数据从哪里来、到哪里去 | 保存上传、生成下载、发送请求 |
| 工作方式 | 阻塞、非阻塞；同步、异步 | 等待是否占住调用线程、结果如何返回 | Servlet 文件上传、NIO 网络连接 |
| 包装能力 | 缓冲、数据类型、压缩、摘要 | 在原始流上增加功能 | 批量小写入、ZIP、SHA-256 |
| 读取方式 | 全量、逐块、逐行 | 峰值内存由什么决定 | 小配置全量读；大视频逐块复制 |

这些是不同维度。同一条流可以是“来自文件的、带缓冲的、阻塞字节输入流”；不能把 `BufferedInputStream` 和“异步”当成互斥选项。

### 把一条流拆成“来源、转换、去向”

刚接触 Java I/O 时，看到类名容易以为有几十种互不相关的 API。多数代码可以拆成三段：**来源流**负责从哪里读，**转换/包装流**按需增加缓冲、解压、解码、统计等行为，**目标流**负责写到哪里。包装层可以零个、一个或多个；每一层的输入和输出单位要对应。

| 层次 | 常见类 | 它回答的问题 | 示例 |
|---|---|---|---|
| 来源/目标 | `Files.newInputStream`、`Files.newOutputStream`、Socket body、`ByteArrayInputStream` | 数据从哪来、写到哪去？ | 文件、内存、HTTP 请求/响应 |
| 缓冲 | `BufferedInputStream`、`BufferedOutputStream`、`BufferedReader`、`BufferedWriter` | 是否要减少小块系统调用？ | 逐字节解析、逐行读取、批量文本写出 |
| 解释/转换 | `InputStreamReader`、`OutputStreamWriter`、`GZIPInputStream`、`GZIPOutputStream` | 如何解码字符或转换压缩格式？ | UTF-8 文本、gzip 网络响应 |
| 类型化格式 | `DataInputStream`、`DataOutputStream`、`ObjectInputStream` | 字段边界和类型怎样编码？ | Java 内部二进制格式、受信任历史文件 |
| 观测/校验 | `DigestInputStream`、`DigestOutputStream`、自定义计数流 | 是否要计算摘要、计数或审计？ | 上传长度、SHA-256 校验 |

读取链一般从外向内看，关闭时沿包装关系传到被包裹资源；构造链时则从底层来源往外包装。压缩和字符解码的顺序必须符合协议：先解压得到原始文本字节，再按 UTF-8 解码。若把顺序反过来，就会把 gzip 字节当文本处理。

```java
// 完整方法片段：把 gzip 压缩的 UTF-8 文本文件解压后逐行处理。
// source 和 processor 由调用方传入；本方法负责关闭自己打开的文件流和包装流。
static void readCompressedUtf8(Path source, Consumer<String> processor) throws IOException {
    // 先登记底层文件资源；即使后续 gzip 包装器初始化失败，也会由 try-with-resources 关闭。
    try (InputStream fileBytes = Files.newInputStream(source)) {
        // gzip 解压后仍是字节；不能在解压之前把压缩字节当 UTF-8 文本解码。
        InputStream decompressedBytes = new GZIPInputStream(fileBytes);
        // 按 UTF-8 把解压后的字节转换为字符，避免使用系统默认编码。
        Reader decodedCharacters = new InputStreamReader(decompressedBytes, StandardCharsets.UTF_8);
        // 关闭最外层缓冲器会沿链关闭 Reader、GZIP 和文件流；外层再关闭一次文件流是安全兜底。
        try (BufferedReader lines = new BufferedReader(decodedCharacters)) {
            // 每次只保留当前一行；processor 不应把所有行再无限累积到内存。
            String line;
            while ((line = lines.readLine()) != null) {
                // 把当前行交给调用方处理；处理异常会向外传播，资源仍会关闭。
                processor.accept(line);
            }
        }
    }
}
```

一个底层流只应有一个明确的所有者负责关闭。上例先登记文件流，是为了后续 `GZIPInputStream` 构造失败时也能关闭文件；成功时关闭最外层 Reader 会沿链关闭 GZIP 和文件流，外层 try 再关闭一次文件流作为兜底。标准 JDK 文件流重复关闭没有副作用，但一般代码应避免让多个业务对象争夺关闭责任。不要把关闭框架响应流的责任误交给业务代码。

## 2. 字节流：保持文件原样

### InputStream / OutputStream 是能力接口的基础

`InputStream` 和 `OutputStream` 是抽象类。代码可依赖“能读字节/写字节”的能力，不必绑定文件、网络还是内存。

| 方法 | 含义 | 必须注意 |
|---|---|---|
| `in.read()` | 读取一个字节，返回 `0..255`；结束返回 `-1` | 返回 `int`，让 EOF 与合法字节值可区分 |
| `in.read(buffer)` | 最多读满数组，返回这次实际读到的数量 | 不保证一次填满；必须使用返回值 |
| `out.write(buffer, 0, n)` | 只写本次有效的前 `n` 个字节 | 写整个数组会把上次残留数据一起写出去 |
| `in.readNBytes(n)` | 尝试读取指定数量，到 EOF 可能不足 | 仍要验证实际长度；大 `n` 会占大量内存 |
| `in.skip(n)` | 尝试跳过若干字节 | 返回实际跳过数，不能假设等于 `n` |
| `in.available()` | 估计无需阻塞即可读到的字节数 | 不是文件/HTTP body 总大小，也不是 EOF 判断 |
| `in.transferTo(out)` | 从当前位置复制到输出流 | 不自动关闭任意一端；没有业务大小限制 |
| `close()` | 释放该流持有的资源 | 应明确资源所有者，不能靠 GC 及时关闭 |

### 最基础也最容易写错的复制循环

~~~java
// 片段：in/out 由调用方提供并负责关闭；适用于阻塞 InputStream。
// 这里只分配固定大小数组，不把整个文件装进堆内存。
byte[] buffer = new byte[16 * 1024];
long total = 0; // 文件可能超过 2 GiB，累计长度使用 long。
int count;
while ((count = in.read(buffer)) != -1) {
    // count 是本次有效长度。最后一次、网络分批到达时都可能小于数组长度。
    out.write(buffer, 0, count);
    total += count;
}
// total 表示读到的字节数；是否等于上传会话声明的大小还需业务校验。
~~~

`read()` 没读满不等于结束；只有该 API 定义的 EOF 才是结束。阻塞 `InputStream.read(byte[])` 在数组长度大于 0 时会至少读到一个字节、抛异常或返回 `-1`；NIO 非阻塞 `Channel.read()` 还可能返回 `0` 表示暂时无数据，见 [NIO 专题](./nio.md)。不要把两套循环生搬硬套。

### 字节流家族与选型

| 类/工具 | 解决的问题 | 场景与取舍 |
|---|---|---|
| `FileInputStream` / `FileOutputStream` | 读写本地文件字节 | 经典 API；新代码通常用 `Files.newInputStream/newOutputStream` 明确打开选项 |
| `ByteArrayInputStream` / `ByteArrayOutputStream` | 把内存数组当作流 | 小数据单测、协议组包；后者会增长，不适合装整个大文件 |
| `BufferedInputStream` / `BufferedOutputStream` | 减少频繁的小读写调用 | 逐字节解析、小块输出时有用；不会变成异步，也不保证所有场景更快 |
| `DataInputStream` / `DataOutputStream` | 按约定读写 Java 基本数据类型 | 私有二进制格式；写入/读取字段顺序、类型、字节序要匹配 |
| `ObjectInputStream` / `ObjectOutputStream` | Java 对象序列化 | 特定 Java 内部存量协议；不要把不可信网络输入直接交给对象反序列化 |
| `FilterInputStream` / `FilterOutputStream` | 装饰已有流，增加处理逻辑 | 缓冲、计数、摘要等包装器的基础 |
| `DigestInputStream` / `DigestOutputStream` | 在读写时增量计算摘要 | 大文件校验，避免一次加载所有字节；摘要不等于身份认证 |
| `GZIPInputStream` / `GZIPOutputStream` | 流式压缩或解压 | 传输日志、归档；限制解压后大小，防止小压缩包展开成巨量数据 |
| `ZipInputStream` / `ZipOutputStream` | 顺序处理 ZIP 条目 | 归档导入导出；条目名不能直接拼磁盘路径，需防目录穿越 |
| `SequenceInputStream` | 顺序拼接多个输入流 | 少量数据段串接；大量分片更适合逐个打开、关闭，不一次打开所有文件 |
| `PipedInputStream` / `PipedOutputStream` | 同一 JVM 内线程间字节管道 | 特定流适配；同线程写满再读可能死锁，普通任务协作用队列通常更清晰 |

`DataOutputStream.writeUTF()` 使用**修改版 UTF-8**并有长度限制，不能把它当成任意长度标准 UTF-8 文本格式。跨语言协议应明确字段长度、字节序、字符集和版本，例如长度前缀加标准 UTF-8 字节。

`ObjectOutputStream` 也不是通用 JSON 替代品：它与 Java 类型和类版本耦合，不适合作为浏览器上传接口的任意对象格式。`serialVersionUID` 不能让不可信反序列化自动安全。

## 3. 字符流：编码是转换规则，不是文件属性标签

### Reader / Writer 处理文本

磁盘和网络最终传递的是字节；字符流帮助你按编码把字节解码成字符，或把字符编码回字节。Java `char` 是 UTF-16 代码单元；一个 emoji 可能占两个 `char`，UTF-8 下的字节数又不同。`text.length()` 不能当成上传的字节长度或 HTTP `Content-Length`。

| 类/方法 | 解决的问题 | 典型场景与细节 |
|---|---|---|
| `Reader` / `Writer` | 按字符读写 | 文本处理能力的抽象，不适用于任意二进制 |
| `InputStreamReader` | 字节 → 字符，桥接编码 | HTTP/文件字节按 UTF-8 读取 |
| `OutputStreamWriter` | 字符 → 字节 | 将文本写到某个字节输出流 |
| `BufferedReader` | 缓冲读取、`readLine()` | 逐行日志/文本；一行极长时仍会占很多内存 |
| `BufferedWriter` | 缓冲写入、`newLine()` | 批量生成文本；换行符使用当前平台规则 |
| `StringReader` / `StringWriter` | 内存字符串与字符流互转 | 小模板、小文本；所有内容已在内存 |
| `FileReader` / `FileWriter` | 文件字符读写便利类 | 无字符集构造方式依赖运行环境；跨平台代码显式使用 UTF-8 |
| `PrintWriter` / `PrintStream` | `print/println/printf` 格式化输出 | 日志或展示；部分写入异常需用 `checkError()` 检查，不会像普通 Writer 一样全部向上传播 |
| `Files.newBufferedReader/newBufferedWriter` | 使用 Path 打开带缓冲文本流 | 配置、CSV、日志常见入口，显式传编码更容易阅读 |

~~~java
// 片段：输入明确使用 UTF-8；离开代码块自动关闭 reader。
try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
    String line;
    while ((line = reader.readLine()) != null) {
        // readLine() 去掉行结束符；空行是 ""，文件结束才是 null。
        // 这里按行处理，不把每一行永久加入 List，否则仍是全量驻留内存。
        processLine(line);
    }
}
~~~

**CSV 不一定能简单逐行拆分。** 引号内可能包含换行、逗号和转义；真实导入使用合适的 CSV 解析器，并设置字段、记录、文件大小上限。`split(",")` 不能正确解析所有 CSV。

### 乱码、半个字符与非法文本

- UTF-8 的一个字符可能跨两个缓冲块。不要每读一块就独立 `new String(buffer, 0, count, UTF_8)` 再拼接，否则多字节字符边界可能被拆断。使用有状态的 `InputStreamReader` 或 `CharsetDecoder`。
- `new String(bytes, UTF_8)` 遇到非法编码可能采用替换字符。严格导入可用 `CharsetDecoder.onMalformedInput(CodingErrorAction.REPORT)`，让坏数据明确失败。
- 读成字符串再转回字节不保证保持原文件，尤其是图片、PDF、ZIP。复制、上传、摘要应直接操作字节。
- UTF-8 BOM 是否允许、换行符是否保留、是否规范化 Unicode，需要文件协议约定；不要无意识改动用于签名/摘要的原始数据。

## 4. Path / Files：现代文件 API 怎样选

`Path` 表达路径，`Files` 提供文件操作。它们属于 NIO.2 文件 API，但 `Files.readAllBytes()`、`Files.copy()` 等普通文件方法仍可能阻塞；**包名含 nio 不等于非阻塞**。

| API | 什么时候用 | 资源/行为细节 |
|---|---|---|
| `Path.of(...)`、`resolve(...)` | 组合服务端控制的路径 | `resolve` 不会自动防 `..`；绝对路径还可能替换根路径 |
| `Files.newInputStream` / `newOutputStream` | 流式读写文件 | 输出默认行为涉及创建/截断；业务中显式选择 `CREATE_NEW` 等选项 |
| `Files.newBufferedReader` / `newBufferedWriter` | 按指定编码读写文本 | 流必须关闭，输入最大行长要另行控制 |
| `Files.readAllBytes` / `readString` / `readAllLines` | 已有严格大小上限的小文件 | 返回值包含全部内容，文件越大内存越多 |
| `Files.lines` | 惰性产生文本行 | 返回 `Stream<String>` 也持有文件资源，必须 try-with-resources |
| `Files.list` / `walk` | 枚举目录 | 返回的流要关闭；大量文件需限制遍历范围和数量 |
| `Files.size` | 查看当前文件长度 | 是一个时间点的观察，不是对未来读取的大小保证 |
| `Files.copy` | 简单复制文件或流 | 复制失败可能留下不完整目标；不提供业务上的原子发布 |
| `Files.createTempFile/Directory` | 生成服务端临时路径 | 放受控目录，设置配额、过期清理和访问权限 |
| `Files.move(..., ATOMIC_MOVE)` | 在支持的文件系统中原子发布名字 | 可能不支持或跨文件系统失败；不能静默假定降级仍具原子性 |
| `Files.deleteIfExists` | 删除自己拥有的临时文件 | 只对已验证归属的路径操作，注意并发和占用中的文件 |

~~~java
// 片段：Stream 的惰性不意味着资源可不关闭。
try (java.util.stream.Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
    // count() 遍历文本行；不要先 collect(toList()) 再统计。
    long count = lines.filter(line -> !line.isBlank()).count();
    System.out.println("非空行数=" + count);
}
~~~

### 用户文件名不是服务端存储路径

浏览器文件名、ZIP 条目、`../secret.txt` 都是不可信输入。存储键应由服务端生成，例如数据库文件 ID/随机 ID；原始文件名只作为经过长度与字符约束的显示元数据。

`root.resolve(userName).normalize().startsWith(root)` 可以检查一部分路径逃逸，但**不能单独解决符号链接和检查后替换的竞态**。教学上传应使用服务端生成目录名、限制根目录写入者和路径权限；生产存储按文件系统/对象存储能力设计。不要把“做了 normalize”写成完整权限隔离。

## 5. 关闭、刷新和落盘：三个不同层次

### try-with-resources 解决异常路径泄漏

文件描述符、Socket、数据库连接不是“内存不够时再让 GC 收”的普通业务对象。`try-with-resources` 在正常结束和异常时都会调用 `close()`，按声明顺序的逆序关闭资源。

~~~java
// 片段：由当前方法打开的两个资源由当前方法负责关闭。
try (InputStream in = Files.newInputStream(source);
     OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
    in.transferTo(out); // 不设大小上限的便利复制，仅用于已验证边界的输入。
} // 先关闭 out，再关闭 in；BufferedOutputStream 的关闭会尝试刷新缓冲。
~~~

如果初始化第二个资源就失败，第一个已打开资源也会关闭。主体和关闭过程都抛异常时，主体异常通常是主要异常，关闭异常挂在 `getSuppressed()`；不要只打印一行消息把根因丢掉。

包装流通常会把 `close()` 传给内层流。例如关闭 `BufferedInputStream` 会关闭它包装的文件流。方法只“借用”调用方的流时，应在注释约定谁关闭；框架拥有的 HTTP 响应流要遵守框架生命周期，不能关闭后继续写 JSON。

### flush 不等于断电后一定存在

| 操作 | 主要效果 | 不能据此推断 |
|---|---|---|
| `write(...)` | 数据进入当前流/下一层 | 不代表客户端已收到或磁盘已持久化 |
| `flush()` | 将适用的应用缓冲尽量推向下一层 | 不等于磁盘设备完成持久写入，不等于远端业务处理成功 |
| `close()` | 关闭资源；输出包装器通常尝试刷新 | 不替代业务提交、完整性校验、可靠存储协议 |
| `FileChannel.force(...)` / `FileDescriptor.sync()` | 请求将相关文件更新同步到存储设备 | 不能自动形成数据库事务，也不涵盖所有路径/目录/远端存储的持久化保证 |

缓冲区每写一个小块就 `flush()` 会削弱缓冲收益。文本交互协议可能需要按消息刷新，批量文件复制通常在结束/检查点按设计处理。不要为“保险”在每字节之后刷新。

原子重命名主要保证观察者看到哪个文件名/版本，文件内容的持久性是另一个问题。上传完成还需校验长度与摘要、提交元数据、定义崩溃恢复；详见[分片上传](../../examples/large-file-upload.md)。

## 6. 大文件为什么要流式处理

### 解决的问题：峰值内存与并发放大

假设一个 2 GiB 文件被 `readAllBytes()` 读成一个数组，还会转成字符串或 JSON/Base64，请求内可能同时存在多份副本。10 个并发请求会放大内存压力，甚至在文件远未上传完时触发 OOM。

固定缓冲复制的应用字节缓冲约为 `并发数 × 每个任务的缓冲大小`，而不是 `并发数 × 文件大小`。但这**不是整个系统的总内存公式**：Servlet/HTTP 客户端、multipart 解析器、TLS、对象存储 SDK、压缩器、队列和操作系统也有缓冲。

| 做法 | 内存形态 | 场景 |
|---|---|---|
| `readAllBytes()` / `MultipartFile.getBytes()` | 整个内容进入数组 | 有严格大小上限的小头像或小配置 |
| 固定块读写 | 主要随缓冲与并发数增长 | 大文件上传、下载、复制、摘要 |
| 逐行读取但全部加入 `List` | 最终仍保留全文件 | 不适合作为“大文件流式”的实现 |
| 逐行解析、分批写库 | 由行长、批次大小、并发数控制 | CSV 导入；需定义批次失败和断点策略 |
| 一次打开全部分片流 | 内存之外还耗尽文件句柄 | 分片很多时改为逐片打开、顺序复制、立即关闭 |

### 大小限制必须边读边验证

`Content-Length`、`MultipartFile.getSize()`、客户端上报文件大小都不能替代实际读取计数。预检查可快速拒绝，实际复制时仍须执行上限检查；若上传会话声明精确长度，还要拒绝不足和超出。

~~~java
// 片段：最大字节数已由服务端确定，total 初始为 0。
// 用差值比较避免 total + count 在极端输入下发生 long 溢出。
if (count > maximumBytes - total) {
    throw new IOException("文件超过服务端允许大小");
}
out.write(buffer, 0, count);
total += count;
~~~

超出上限时，前面可能已写入一部分数据。应先写到私有临时文件；失败关闭并删除该半成品，成功校验后再发布。不要边上传边覆盖一个所有用户都能下载的正式文件。

## 7. 普通上传、下载：Java 流放在哪一层

### Spring MVC 普通 multipart 上传

`MultipartFile` 是框架解析后的上传表示。HTTP `multipart/form-data` 把表单字段与文件作为多个 part 传输，**不等于业务上的大文件分片协议**。框架可能已把较大的 part 暂存到磁盘，再把访问能力交给 Controller。

~~~java
// Spring MVC 集成片段，展示资源边界；完整 API 还需要鉴权、限额与服务层实现。
// 不把这个片段当成已经可以部署的完整 Controller。
public void saveUpload(MultipartFile upload, Path privateTempFile) throws IOException {
    // getBytes() 会申请整个文件大小的数组；这里改为按流读取。
    // privateTempFile 必须由服务端生成并保证当前用户的会话归属。
    try (InputStream in = upload.getInputStream();
         OutputStream out = Files.newOutputStream(privateTempFile,
                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        // copyWithLimit 表示你要实现的“逐块复制 + 实际字节计数 + 上限校验”。
        // 它不是 Spring 自带函数，完整 Java 示例中有对应实现思路。
        copyWithLimit(in, out, allowedBytes);
    }
    // 此后还应校验完整长度/摘要，并通过受控流程发布；失败清理由拥有者处理。
}
~~~

该片段中的 `MultipartFile`、`allowedBytes`、`copyWithLimit` 属于待集成的框架类型/业务能力；下面提供的独立 Java 程序才是本章可直接编译运行的示例。真实 Spring 工程还需同时检查代理 body 限制、multipart 单文件/总请求限制、临时盘位置、请求超时和存储配额。

Controller 结束后，框架可能清理 multipart 临时文件。把 `MultipartFile` 引用直接交给后台线程，稍后读取可能失败；需要先把输入可靠保存到自己管理的持久位置，再提交任务 ID。

### 文件下载

- 后端可使用框架的 `Resource` 或流式响应支持，不必先 `readAllBytes()` 再返回 `byte[]`。
- 下载文件仍需检查当前用户对文件 ID 的权限。不要让前端传一个任意磁盘绝对路径。
- 已确定且不会变化的完整文件可以返回正确 `Content-Length`；动态压缩、传输编码和 Range 请求要按 HTTP 规则处理，不能把字符数当长度。
- 文件名需通过框架正确编码 `Content-Disposition`，不要直接拼接用户输入到响应头。
- 浏览器断开时，写响应可能抛出 `IOException`；这时应关闭本次读取，不能向已经写了一半的文件响应再追加 JSON 错误。
- 前端 `response.blob()` 通常要等整个响应体完成并形成 Blob。超大下载可优先使用经过鉴权的浏览器下载导航/受控签名 URL；服务端流式并不自动让所有前端代码也流式。

Spring MVC 的异步响应可以释放原请求线程，但实际磁盘读取和写响应仍可能占用异步执行器线程。它与非阻塞事件循环不是同一件事，见[网络库专题](./network-libraries.md)。

## 8. 完整可运行示例：短读、流式复制、摘要和中文

### 场景与结果

这个程序创建自己独占的临时目录，在里面生成 65,573 字节的小文件；用每次最多返回 7 字节的输入包装器，主动制造合法的“短读”，验证复制循环没有把缓冲区残留写进去。随后重新流式读取目标计算 SHA-256，并验证 UTF-8 中文文本的逐行读取。最后清理本程序创建的文件和目录。

示例规模很小，是为了快速运行；复制方法的内存使用不会随文件大小线性增长。它不演示 HTTP 服务、磁盘掉电恢复或生产存储事务。

<a href="/examples/io/StreamingFileDemo.java" download>下载完整中文注释 StreamingFileDemo.java</a>

~~~powershell
# 将下载的源码放进练习目录，以 Java 11 API 为基线编译。
javac -encoding UTF-8 --release 11 StreamingFileDemo.java
# 使用 JDK 17 运行；Windows 下可显式指定控制台编码。
java '-Dfile.encoding=UTF-8' StreamingFileDemo
~~~

预期出现：

~~~text
PASS bytes=65573 shortReadLimit=7 sha256Matches=true utf8Lines=3
~~~

<<< ../../public/examples/io/StreamingFileDemo.java{java}

## 9. 按现象定位与选型

| 现象/需求 | 首先检查 | 合理做法 |
|---|---|---|
| 图片上传后损坏 | 是否转过 String、写了整个数组、错误追加文件 | 全程按字节复制，使用有效长度，验证长度与摘要 |
| 中文乱码 | 两端字符集、分块边界、非法编码 | 显式 UTF-8，有状态解码，必要时严格报错 |
| 运行久后无法打开文件 | 流、目录 Stream、HTTP body 是否关闭 | try-with-resources，明确框架与业务的资源归属 |
| 大文件上传内存暴涨 | getBytes/readAllBytes、Base64、副本、并发队列 | 流式读取、限制并发/队列、按层检查缓冲 |
| 上传到一半失败留文件 | 临时文件状态与失败清理 | 私有临时路径、成功后发布、过期回收与恢复记录 |
| 文件复制慢 | 磁盘、网络、缓冲、加密/摘要 CPU、竞争 | 测量瓶颈，再决定缓冲、并发或 NIO；不要默认多线程更快 |
| 大量慢连接占线程 | 运行模型与线程/连接数 | 有界阻塞线程池，或评估 NIO/Netty；别在事件循环里读大文件 |
| 需要中断续传 | 是否保存会话、分片状态与校验信息 | 采用应用层分片协议，见[分片上传](../../examples/large-file-upload.md) |

### 练习

1. 把示例中的 `out.write(buffer, 0, count)` 临时改成写整个 buffer，观察长度/摘要检查如何暴露错误，再恢复正确代码。
2. 把 `maximumBytes` 调小，确认程序失败并清理临时目录，而不是把不完整数据当作成功。
3. 为导入接口列出单文件大小、总请求大小、最大行长、批次大小、最大并发五个边界，解释各自保护什么资源。
4. 用“流从哪里打开、由谁关闭、异常留什么、何时对外可见”四个问题检查一个上传接口。
5. 解释为什么 `Files.lines(path).collect(toList())` 不再是低内存方案，以及为什么 `flush()` 不等于业务提交完成。

## 官方 API 参考

- [Java 11 InputStream](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/InputStream.html)：read、EOF、readNBytes、transferTo 的契约。
- [Java 11 Reader](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/Reader.html) 与 [InputStreamReader](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/io/InputStreamReader.html)：字符读取与编码桥接。
- [Java 11 Files](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/Files.html)：流、目录遍历、复制/移动与打开选项。
- [Java 11 FileChannel](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/channels/FileChannel.html)：文件通道、force 与文件锁。

继续学习：[NIO 的 Buffer、Channel、Selector](./nio.md)。
