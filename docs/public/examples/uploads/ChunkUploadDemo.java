import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Java 11 分片上传核心教学：模拟两个客户端竞争、丢包重试、续传与合并。
 * 不启动 HTTP、不连接数据库。上传会话只在内存，不能跨进程或重启续传。
 * 生产适配层还须负责认证、租户隔离、请求超时、配额、持久化状态和恢复任务。
 */
public final class ChunkUploadDemo {
    private static final int BUFFER_SIZE = 8192;

    /** 只有服务端完成大小和整文件摘要校验后，才进入 COMPLETE。 */
    enum State { OPEN, MERGING, COMPLETE, CANCELLED }

    /** 已接受的分片元数据；本例对象发布后不再变化。 */
    static final class Part {
        final Path path;
        final String sha256;

        Part(Path path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }
    }

    /** 查询结果是快照，调用方不能修改内部 Map 或状态。 */
    static final class Snapshot {
        final State state;
        final List<Integer> uploaded;
        final long confirmedBytes;

        Snapshot(State state, List<Integer> uploaded, long confirmedBytes) {
            this.state = state;
            this.uploaded = Collections.unmodifiableList(uploaded);
            this.confirmedBytes = confirmedBytes;
        }
    }

    /** 一次上传固定大小、分片规则和全文摘要，不能由每次 PUT 任意重写。 */
    static final class Session {
        final String owner;
        final long totalSize;
        final int chunkSize;
        final int count;
        final String wholeSha256;
        final Path directory;
        final ReentrantLock lock = new ReentrantLock();
        final Map<Integer, Part> parts = new HashMap<>();
        State state = State.OPEN;
        Path result;

        Session(String owner, long totalSize, int chunkSize, String wholeSha256,
                Path directory) {
            this.owner = owner;
            this.totalSize = totalSize;
            this.chunkSize = chunkSize;
            // 避免 totalSize + chunkSize - 1 溢出的写法；本例已经拒绝空文件。
            this.count = Math.toIntExact(1 + (totalSize - 1) / chunkSize);
            this.wholeSha256 = wholeSha256;
            this.directory = directory;
        }

        long sizeOf(int index) {
            if (index < 0 || index >= count) {
                throw new IllegalArgumentException("分片下标越界");
            }
            return Math.min(chunkSize, totalSize - (long) index * chunkSize);
        }
    }

    /** 本例只有一个 JVM：真实多实例服务必须改用共享状态、约束和存储。 */
    static final class UploadStore {
        private final Path root;
        private final Map<String, Session> sessions = new ConcurrentHashMap<>();

        UploadStore(Path root) {
            this.root = root;
        }

        String begin(String owner, long size, int chunkSize, String fullSha) throws IOException {
            // 教学上限控制磁盘与分片数量；生产还需要租户总额度、会话数量和 TTL。
            if (owner == null || owner.isBlank() || size <= 0 || size > 64L * 1024 * 1024
                    || chunkSize < 1024 || chunkSize > 4 * 1024 * 1024
                    || 1 + (size - 1) / chunkSize > 10_000) {
                throw new IllegalArgumentException("大小、分片配置或身份无效");
            }
            validateHash(fullSha);
            String id = UUID.randomUUID().toString();
            // 路径只由服务端 UUID 组成，不使用用户文件名或客户端传来的路径。
            Path directory = Files.createDirectory(root.resolve(id));
            sessions.put(id, new Session(owner, size, chunkSize, fullSha, directory));
            return id;
        }

        private Session authorized(String owner, String id) {
            Session s = sessions.get(id);
            // owner 必须由真实服务端登录态提供，不能直接信任 body 中的 owner。
            if (s == null || !s.owner.equals(owner)) {
                throw new SecurityException("上传会话不可访问");
            }
            return s;
        }

        Snapshot status(String owner, String id) {
            Session s = authorized(owner, id);
            s.lock.lock();
            try {
                List<Integer> indexes = new ArrayList<>(s.parts.keySet());
                Collections.sort(indexes);
                long bytes = 0;
                for (int index : indexes) bytes += s.sizeOf(index);
                return new Snapshot(s.state, indexes, bytes);
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * 返回 true 表示首次接受，false 表示内容相同的重复分片。
         * 本方法拥有传入流，结束时关闭。HTTP 层还应有读超时和单请求大小限制。
         */
        boolean put(String owner, String id, int index, String expectedSha,
                    InputStream requestBody) throws IOException {
            // 把校验也放在资源作用域中；即便身份/下标错误，也关闭传入流。
            try (InputStream in = requestBody) {
                Session s = authorized(owner, id);
                validateHash(expectedSha);
                long expectedLength = s.sizeOf(index);
                s.lock.lock();
                try {
                    if (s.state != State.OPEN) throw new IllegalStateException("当前状态不接受分片");
                } finally {
                    s.lock.unlock();
                }

                // 每个并发请求使用独立临时文件，不让两个请求互相截断同一个 part。
                Path temporary = Files.createTempFile(s.directory, "receiving-", ".tmp");
                try {
                    MessageDigest digest = sha256();
                    long received = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try (OutputStream out = Files.newOutputStream(temporary)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            // 不相信 Content-Length；流式累计实际长度，超过上限立即拒绝。
                            if (read > expectedLength - received) throw new IOException("分片过大");
                            out.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            received += read;
                        }
                    }
                    if (received != expectedLength) throw new IOException("分片被截断");
                    if (!hex(digest.digest()).equals(expectedSha)) throw new IOException("分片摘要不匹配");

                    // 网络读写在锁外。发布分片前再检查状态，防止取消/合并竞态。
                    s.lock.lock();
                    try {
                        if (s.state != State.OPEN) throw new IllegalStateException("会话状态已变化");
                        Part old = s.parts.get(index);
                        if (old != null) {
                            if (!old.sha256.equals(expectedSha)) throw new IllegalStateException("同下标内容冲突");
                            return false; // 超时重传相同分片：确认成功，但不重复累计字节数。
                        }
                        Path partPath = s.directory.resolve(index + ".part");
                        // 同一文件系统内原子发布；不支持时让调用失败，不悄悄降级为非原子覆盖。
                        Files.move(temporary, partPath, StandardCopyOption.ATOMIC_MOVE);
                        s.parts.put(index, new Part(partPath, expectedSha));
                        return true;
                    } finally {
                        s.lock.unlock();
                    }
                } finally {
                    // 只清理本次请求的临时文件；成功 move 后原路径已不存在。
                    Files.deleteIfExists(temporary);
                }
            }
        }

        /** 单进程同步合并：持有本会话锁，因此取消/重复 complete 不会交叉发布。 */
        Path complete(String owner, String id) throws IOException {
            Session s = authorized(owner, id);
            s.lock.lock();
            try {
                if (s.state == State.COMPLETE) return s.result; // 响应丢失后的重试返回同一结果。
                if (s.state != State.OPEN) throw new IllegalStateException("当前状态不能合并");
                if (s.parts.size() != s.count) throw new IllegalStateException("仍有缺片");
                s.state = State.MERGING;
                Path merged = null;
                try {
                    merged = Files.createTempFile(s.directory, "merging-", ".tmp");
                    MessageDigest digest = sha256();
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long total = 0;
                    try (OutputStream out = Files.newOutputStream(merged)) {
                        // 按数字下标合并，不依赖目录顺序，也不按字符串排序 1、10、2。
                        for (int index = 0; index < s.count; index++) {
                            Part part = s.parts.get(index);
                            long partBytes = 0;
                            try (InputStream in = Files.newInputStream(part.path)) {
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    if (read > s.sizeOf(index) - partBytes) throw new IOException("磁盘分片大小改变");
                                    out.write(buffer, 0, read);
                                    digest.update(buffer, 0, read);
                                    partBytes += read;
                                }
                            }
                            if (partBytes != s.sizeOf(index)) throw new IOException("磁盘分片不完整");
                            total += partBytes;
                        }
                    }
                    if (total != s.totalSize || !hex(digest.digest()).equals(s.wholeSha256)) {
                        throw new IOException("整文件大小或 SHA-256 不匹配");
                    }
                    try (FileChannel channel = FileChannel.open(merged, StandardOpenOption.WRITE)) {
                        channel.force(true); // 请求刷盘；不等于数据库和目录状态的跨资源事务。
                    }
                    Path result = s.directory.resolve("result.bin");
                    Files.move(merged, result, StandardCopyOption.ATOMIC_MOVE);
                    s.result = result;
                    s.state = State.COMPLETE;
                    // 为让重试读取元数据，本例保留分片，最后由测试统一清理。
                    // 生产中由可重试清理任务删分片；清理失败不能撤回已确认的 COMPLETE。
                    return result;
                } catch (IOException | RuntimeException ex) {
                    s.state = State.OPEN; // 本地实验可修复后重试；生产需要持久失败原因/恢复规则。
                    throw ex;
                } finally {
                    if (merged != null) Files.deleteIfExists(merged);
                }
            } finally {
                s.lock.unlock();
            }
        }

        void cancel(String owner, String id) {
            Session s = authorized(owner, id);
            s.lock.lock();
            try {
                if (s.state == State.COMPLETE) throw new IllegalStateException("完成文件应走独立删除流程");
                s.state = State.CANCELLED; // 正在接收的请求发布前会再检查并拒绝。
            } finally {
                s.lock.unlock();
            }
        }
    }

    /** 用小文件确定性验证逻辑，无需上传数 GB 才验证分片算法。 */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("chunk-upload-learning-");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            UploadStore store = new UploadStore(root);
            byte[] source = new byte[10_243]; // 尾片不足一个完整分片，故意覆盖边界。
            for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31);
            int chunkSize = 4096;
            String fullSha = hash(source);
            String id = store.begin("learner", source.length, chunkSize, fullSha);
            expectFailure(SecurityException.class, () -> store.status("another-user", id));
            expectFailure(IllegalArgumentException.class,
                    () -> store.put("learner", id, 3, fullSha, new ByteArrayInputStream(new byte[0])));

            byte[] first = Arrays.copyOfRange(source, 0, chunkSize);
            // 请求 body 太短、太长或 checksum 错误，都不能被记录为已上传。
            expectFailure(IOException.class, () -> store.put("learner", id, 0, hash(first),
                    new ByteArrayInputStream(Arrays.copyOf(first, first.length - 1))));
            expectFailure(IOException.class, () -> store.put("learner", id, 0, hash(first),
                    new ByteArrayInputStream(Arrays.copyOf(first, first.length + 1))));
            byte[] corrupt = first.clone();
            corrupt[0] ^= 1;
            expectFailure(IOException.class, () -> store.put("learner", id, 0, hash(first),
                    new ByteArrayInputStream(corrupt)));
            check(store.status("learner", id).uploaded.isEmpty(), "拒绝请求不能留下已接受分片");

            // 两个请求并发发送相同下标：恰好一次首次接受、一次重复确认。
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Boolean>> duplicateRequests = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                duplicateRequests.add(pool.submit(() -> {
                    if (!gate.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("等待闸门超时");
                    return store.put("learner", id, 0, hash(first), new ByteArrayInputStream(first));
                }));
            }
            gate.countDown();
            int accepted = 0;
            for (Future<Boolean> future : duplicateRequests) {
                if (future.get(5, TimeUnit.SECONDS)) accepted++;
            }
            check(accepted == 1, "重复请求只计一次");
            expectFailure(IllegalStateException.class, () -> store.put("learner", id, 0,
                    hash(corrupt), new ByteArrayInputStream(corrupt)));
            expectFailure(IllegalStateException.class, () -> store.complete("learner", id));

            // 模拟页面暂停后查询服务端状态：只补缺失分片，不从本地进度猜测。
            Snapshot resumed = store.status("learner", id);
            check(resumed.uploaded.equals(Collections.singletonList(0)), "续传应只看到第 0 片");
            check(resumed.confirmedBytes == chunkSize, "重复上传不能把进度翻倍");
            List<Future<Boolean>> pending = new ArrayList<>();
            for (int index : new int[] {2, 1}) { // 故意乱序提交。
                int current = index;
                pending.add(pool.submit(() -> {
                    byte[] bytes = Arrays.copyOfRange(source, current * chunkSize,
                            Math.min(source.length, (current + 1) * chunkSize));
                    return store.put("learner", id, current, hash(bytes), new ByteArrayInputStream(bytes));
                }));
            }
            for (Future<Boolean> future : pending) check(future.get(5, TimeUnit.SECONDS), "缺片首次接受");

            Future<Path> mergeA = pool.submit(() -> store.complete("learner", id));
            Future<Path> mergeB = pool.submit(() -> store.complete("learner", id));
            Path result = mergeA.get(5, TimeUnit.SECONDS);
            check(result.equals(mergeB.get(5, TimeUnit.SECONDS)), "重复合并返回同一文件");
            // 只有这里是测试的小数据断言；核心上传/合并不使用 readAllBytes。
            check(Arrays.equals(source, Files.readAllBytes(result)), "最终内容必须逐字节一致");
            check(store.status("learner", id).state == State.COMPLETE, "完成状态");

            String wrongFullHash = store.begin("learner", first.length, chunkSize, hash(corrupt));
            store.put("learner", wrongFullHash, 0, hash(first), new ByteArrayInputStream(first));
            expectFailure(IOException.class, () -> store.complete("learner", wrongFullHash));
            check(store.status("learner", wrongFullHash).state == State.OPEN, "全文校验失败不能发布");
            check(!Files.exists(root.resolve(wrongFullHash).resolve("result.bin")), "无失败成品");

            String cancelled = store.begin("learner", first.length, chunkSize, hash(first));
            store.cancel("learner", cancelled);
            expectFailure(IllegalStateException.class, () -> store.put("learner", cancelled, 0,
                    hash(first), new ByteArrayInputStream(first)));
            System.out.println("PASS chunks=3 duplicateAcceptedOnce=true resumedMissing=2 finalBytes=10243");
            System.out.println("PASS rejected=owner,index,short,long,checksum,conflict,missing,wholeHash,cancelled");
            System.out.println("LOCAL CORE ONLY: no HTTP, no database, no restart recovery.");
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("工作线程未停止");
            // root 由本程序唯一创建；不接收外部路径，不跟随符号链接。
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @FunctionalInterface
    interface CheckedAction { void run() throws Exception; }

    private static void expectFailure(Class<? extends Exception> type, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (Exception ex) {
            if (type.isInstance(ex)) return;
            throw ex;
        }
        throw new IllegalStateException("预期抛出 " + type.getSimpleName());
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message); // 无需 -ea，也不忽略断言。
    }

    private static void validateHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("SHA-256 格式错误");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行时不支持 SHA-256", ex);
        }
    }

    private static String hash(byte[] bytes) { return hex(sha256().digest(bytes)); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
