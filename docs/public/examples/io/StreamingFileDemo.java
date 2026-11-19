import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Java 11 I/O 教学：固定缓冲复制、处理短读、增量摘要与 UTF-8 文本。
 *
 * 只在自己创建的临时目录中写入小型样例，不访问网络、不覆盖用户文件。
 * 编译：javac -encoding UTF-8 --release 11 StreamingFileDemo.java
 * 运行：java -Dfile.encoding=UTF-8 StreamingFileDemo
 */
public final class StreamingFileDemo {
    // 故意不是缓冲大小的整数倍，让最后一次读取不足一个完整块。
    private static final long FILE_BYTES = 65_573L;
    private static final int BUFFER_BYTES = 8 * 1024;
    private static final int SHORT_READ_LIMIT = 7;

    private StreamingFileDemo() {
        // 工具演示类不需要实例化。
    }

    /**
     * 所有断言失败都会抛出异常，不依赖 JVM 的 -ea 开关。
     * 异常继续传到入口，避免输出“成功”但实际上复制失败。
     */
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("java-io-learning-");
        Path source = directory.resolve("source.bin");
        Path target = directory.resolve("copied.bin");
        Path text = directory.resolve("notes.txt");

        try {
            createSample(source);

            CopyResult copied;
            // 方法打开资源就负责关闭；最外层输入包装器会关闭内层文件流。
            try (InputStream in = new ShortReadInputStream(
                    new BufferedInputStream(Files.newInputStream(source)));
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                         target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                // 复制函数只借用 in/out，不负责关闭；最大长度由服务端业务确定。
                copied = copyWithLimit(in, out, FILE_BYTES);
            }

            // 离开上面的 try 后，输出缓冲已在关闭时刷新，再读取目标文件校验。
            require(copied.bytes == FILE_BYTES, "复制长度与声明长度不一致");
            require(Files.size(target) == FILE_BYTES, "目标文件含多余或缺失字节");
            byte[] sourceDigest = sha256(source);
            byte[] targetDigest = sha256(target);
            require(MessageDigest.isEqual(sourceDigest, copied.digest), "复制期间摘要不一致");
            require(MessageDigest.isEqual(sourceDigest, targetDigest), "目标文件摘要不一致");

            int lines = verifyUtf8Text(text);
            System.out.println("PASS bytes=" + copied.bytes
                    + " shortReadLimit=" + SHORT_READ_LIMIT
                    + " sha256Matches=true utf8Lines=" + lines);
        } finally {
            // 只清理本程序创建的三个文件，不遍历或递归删除用户提供的路径。
            // 这是教学规模的清理；生产上传还要保存失败状态并由回收任务处理残留。
            Files.deleteIfExists(text);
            Files.deleteIfExists(target);
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    /**
     * 按固定小块生成确定性二进制数据，不构造一个与整个文件同大的 byte[]。
     */
    private static void createSample(Path source) throws IOException {
        byte[] block = new byte[BUFFER_BYTES];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            long written = 0;
            while (written < FILE_BYTES) {
                int count = (int) Math.min(block.length, FILE_BYTES - written);
                for (int i = 0; i < count; i++) {
                    // 包含不可打印字节，不能把这个文件当成字符串复制。
                    block[i] = (byte) ((written + i) % 251);
                }
                out.write(block, 0, count);
                written += count;
            }
        }
    }

    /**
     * 从当前位置复制至 EOF，边复制边检查上限并计算 SHA-256。
     *
     * 不关闭调用方传入的流。若超限，out 可能已经包含之前的部分数据：
     * 调用者应只传入私有临时输出，失败清理，成功校验后再对外发布。
     */
    private static CopyResult copyWithLimit(InputStream in, OutputStream out, long maximumBytes)
            throws IOException, NoSuchAlgorithmException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes 必须非负");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            // InputStream 的合法短读不等于 EOF；只复制 count 个有效字节。
            // 差值比较避免 total + count 在极端输入下溢出。
            if (count > maximumBytes - total) {
                throw new IOException("输入超过允许大小：" + maximumBytes);
            }
            out.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            total += count;
        }
        return new CopyResult(total, digest.digest());
    }

    /** 对文件重新逐块计算摘要；不是先 readAllBytes 再一次性计算。 */
    private static byte[] sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    /**
     * 中文文本显式使用 UTF-8。逐行读取只持有当前行；readLine 会移除换行符。
     */
    private static int verifyUtf8Text(Path file) throws IOException {
        // 这三个固定小字符串仅是测试预期，不是收集整个用户文件的容器。
        List<String> expected = Arrays.asList("前端上传", "后端流式落盘", "无需一次读入整个文件");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (String line : expected) {
                writer.write(line);
                writer.newLine();
            }
        }

        int index = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                require(index < expected.size(), "文本含多余行");
                require(expected.get(index).equals(line), "第 " + index + " 行编码或内容错误");
                index++;
            }
        }
        require(index == expected.size(), "文本行数不足");
        return index;
    }

    /** 不依赖 -ea 的确定性检查，失败使程序非正常退出。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 复制结果：记录实际字节数和本次流式摘要。 */
    private static final class CopyResult {
        private final long bytes;
        private final byte[] digest;

        private CopyResult(long bytes, byte[] digest) {
            this.bytes = bytes;
            this.digest = digest;
        }
    }

    /**
     * 测试替身：每次最多返回 7 字节，模拟合法的短读。
     * 它不是网络实现，只用于暴露“假设每次一定填满 buffer”的错误。
     */
    private static final class ShortReadInputStream extends FilterInputStream {
        private ShortReadInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return in.read(buffer, offset, Math.min(length, SHORT_READ_LIMIT));
        }
    }
}
