import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Java 11 兼容的 NIO 教学程序：Buffer 状态、长度前缀解帧与 FileChannel 读写。
 * 本例只处理自己创建的小型临时文件，不连接网络、不修改用户已有文件。
 * FileChannel 是阻塞文件 I/O；采用 NIO API 不会自动变成非阻塞操作。
 */
public final class NioBufferChannelDemo {
    // 协议人为规定：每帧 = 4 字节大端正文长度 + UTF-8 正文，正文最多 64 字节。
    private static final int MAX_FRAME_BYTES = 64;

    /** 缓冲区在方法调用之间保持写模式；只保存尚未处理的半包。 */
    private static final class FrameDecoder {
        private final ByteBuffer pending = ByteBuffer
                .allocate(Integer.BYTES + MAX_FRAME_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        private final List<String> messages = new ArrayList<>();

        /**
         * 接收任意切分的一段字节。一次输入可以是半帧，也可以包含多帧。
         * 本对象只供单线程使用；真实事件循环可让每个连接拥有独立实例。
         */
        void accept(byte[] bytes) throws CharacterCodingException {
            int offset = 0;
            while (offset < bytes.length) {
                int count = Math.min(pending.remaining(), bytes.length - offset);
                if (count == 0) {
                    // 有长度上限的解码器不应无限扩容；无进展要明确失败。
                    throw new IllegalStateException("缓冲区已满，无法继续解析协议");
                }
                pending.put(bytes, offset, count);
                offset += count;
                decodeAvailable();
            }
        }

        /** 切到读模式，循环取完整帧；遇到半帧时退回帧头并保留它。 */
        private void decodeAvailable() throws CharacterCodingException {
            pending.flip(); // limit = 原 position；position = 0。
            try {
                while (pending.remaining() >= Integer.BYTES) {
                    pending.mark(); // 记住这一帧开头，正文不完整时回到这里。
                    int length = pending.getInt();
                    if (length < 0 || length > MAX_FRAME_BYTES) {
                        // 先检查再分配，避免攻击者让服务分配超大数组。
                        throw new IllegalArgumentException("非法帧长度：" + length);
                    }
                    if (pending.remaining() < length) {
                        pending.reset(); // 连同长度头一起保存，下一次重新判断。
                        break;
                    }
                    byte[] body = new byte[length];
                    pending.get(body);
                    // 在完整正文上解码；绝不把每次网络 read 的片段分别转字符串。
                    // REPORT 让损坏的 UTF-8 明确报错，而不是悄悄替换成乱码字符。
                    String message = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body)).toString();
                    messages.add(message);
                }
            } finally {
                // 将未读字节搬到开头，position = 剩余字节数，limit = capacity。
                // clear() 会丢失未读半包，不能替换这里的 compact()。
                pending.compact();
            }
        }

        /** EOF 时仍有半包意味着报文被截断，不能假装读取成功。 */
        void endOfInput() {
            if (pending.position() != 0) {
                throw new IllegalArgumentException("EOF 时仍有不完整消息");
            }
        }

        /** 返回副本，避免调用方意外修改解码器已收到的消息。 */
        List<String> messages() {
            return new ArrayList<>(messages);
        }
    }

    /** 将一条小型 UTF-8 消息编码成长度前缀帧，长度是字节数而非字符数。 */
    private static byte[] frame(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("示例消息超过协议上限");
        }
        return ByteBuffer.allocate(Integer.BYTES + body.length)
                .order(ByteOrder.BIG_ENDIAN).putInt(body.length).put(body).array();
    }

    /** 小数据拼接仅用于构造测试输入，不是大文件的实现方式。 */
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** 条件失败直接抛异常，不依赖需要 -ea 参数才启用的 assert 关键字。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> expected = Arrays.asList("你好，NIO", "second message");
        byte[] wire = concat(frame(expected.get(0)), frame(expected.get(1)));

        // 演示半包和粘包：前 2 字节连完整帧头都不足；后续输入跨越两帧边界。
        FrameDecoder fragmented = new FrameDecoder();
        fragmented.accept(Arrays.copyOfRange(wire, 0, 2));
        check(fragmented.messages().isEmpty(), "不完整帧头不应产生消息");
        fragmented.accept(Arrays.copyOfRange(wire, 2, 7));
        check(fragmented.messages().isEmpty(), "不完整正文不应产生消息");
        fragmented.accept(Arrays.copyOfRange(wire, 7, wire.length));
        fragmented.endOfInput();
        check(fragmented.messages().equals(expected), "拆分后的消息必须保持原顺序和内容");

        // 演示协议上限：只提供超大长度头，也必须立即拒绝，而不是等待或扩容。
        boolean rejectedLength = false;
        try {
            new FrameDecoder().accept(ByteBuffer.allocate(4).putInt(65).array());
        } catch (IllegalArgumentException expectedError) {
            rejectedLength = true;
        }
        check(rejectedLength, "必须拒绝超过上限的帧长度");

        // 演示 EOF 截断：断线时缺少正文不能被当作成功结束。
        boolean rejectedTruncation = false;
        FrameDecoder truncated = new FrameDecoder();
        truncated.accept(Arrays.copyOfRange(wire, 0, 7));
        try {
            truncated.endOfInput();
        } catch (IllegalArgumentException expectedError) {
            rejectedTruncation = true;
        }
        check(rejectedTruncation, "必须识别 EOF 时的不完整帧");

        // 只创建并清理本程序自己的临时目录，路径不来自用户文件名。
        Path directory = Files.createTempDirectory("nio-buffer-demo-");
        Path file = directory.resolve("frames.bin");
        try {
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                ByteBuffer output = ByteBuffer.wrap(wire);
                while (output.hasRemaining()) {
                    // 人为把每次提交限制为最多 7 字节，确保演示多次写入。
                    // 这并不证明操作系统一定短写；内层循环兼容实际短写情况。
                    int oldLimit = output.limit();
                    output.limit(Math.min(output.position() + 7, oldLimit));
                    while (output.hasRemaining()) {
                        int written = channel.write(output);
                        if (written == 0) {
                            // 本例是普通阻塞文件；无进展时失败，避免无限自旋。
                            // SocketChannel 非阻塞写 0 应保留数据并等待 OP_WRITE。
                            throw new IOException("文件写入没有进展");
                        }
                    }
                    output.limit(oldLimit);
                }

                channel.position(0); // 回到文件开头，以另一套缓冲区读回。
                ByteBuffer input = ByteBuffer.allocate(5);
                FrameDecoder fromFile = new FrameDecoder();
                long totalRead = 0;
                while (true) {
                    int read = channel.read(input);
                    if (read == -1) {
                        break; // 文件 EOF，后面还会检查解码器是否留有半包。
                    }
                    if (read == 0) {
                        throw new IOException("文件读取没有进展");
                    }
                    totalRead += read;
                    input.flip(); // 只读取本轮实际得到的 read 个字节。
                    byte[] block = new byte[input.remaining()];
                    input.get(block);
                    fromFile.accept(block);
                    input.clear(); // 这层块已全部消费，使用 clear() 正确。
                }
                fromFile.endOfInput();
                check(totalRead == wire.length, "文件读回长度不一致");
                check(fromFile.messages().equals(expected), "文件读回解码结果不一致");
            }
        } finally {
            // 通道已关闭，Windows 上也可删除；只清理已知的临时文件与目录。
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        System.out.println("PASS messages=2 fragmented=true fileRoundTrip=true "
                + "oversizedRejected=true truncatedRejected=true");
    }
}
