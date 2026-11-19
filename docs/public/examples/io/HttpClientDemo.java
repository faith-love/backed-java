import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java 11 HTTP 客户端教学：只访问本程序在 127.0.0.1 随机端口启动的服务器。
 * 展示复用客户端、检查 HTTP 状态、限制响应体大小、限制等待和关闭响应流。
 * HttpServer 只用于这个本机实验，不是具备鉴权、限流、TLS 的生产服务。
 */
public final class HttpClientDemo {
    // 本例只读取小型文本响应，限制的是实际读取字节，不能仅相信 Content-Length。
    private static final int MAX_BODY_BYTES = 1024;
    private static final String EXPECTED = "你好，HTTP 客户端";

    public static void main(String[] args) throws Exception {
        // 服务线程和读取线程分开，避免自己等自己；队列满时明确拒绝。
        ThreadPoolExecutor serverWorkers = newPool(2, "local-http-server");
        ThreadPoolExecutor bodyWorkers = newPool(1, "bounded-body-reader");
        HttpServer server = null;
        try {
            // 地址固定为回环地址；端口 0 表示由操作系统分配当前可用端口。
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 8);
            server.setExecutor(serverWorkers);
            server.createContext("/text", exchange -> respond(exchange, 200,
                    EXPECTED.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/missing", exchange -> respond(exchange, 404,
                    "not found".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/oversized", exchange -> {
                byte[] bytes = new byte[MAX_BODY_BYTES + 1];
                Arrays.fill(bytes, (byte) 'x');
                respond(exchange, 200, bytes);
            });
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

            // HttpClient 可复用；不要在每个业务请求中 new 一个客户端。
            // JDK 11 的 HttpClient 不实现 AutoCloseable，不能写入 try-with-resources。
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            String first = getText(client, base.resolve("/text"), bodyWorkers);
            String second = getText(client, base.resolve("/text"), bodyWorkers);
            check(EXPECTED.equals(first) && EXPECTED.equals(second), "中文正文不一致");
            // 复用客户端允许底层复用连接；本实验没有抓包，不宣称证明了 TCP 连接复用。

            int rejected = 0;
            try {
                getText(client, base.resolve("/missing"), bodyWorkers);
                throw new IllegalStateException("404 必须被业务边界识别");
            } catch (HttpStatusException expected) {
                check(expected.status == 404, "应识别为 404");
                rejected++;
            }
            try {
                getText(client, base.resolve("/oversized"), bodyWorkers);
                throw new IllegalStateException("超大正文必须被拒绝");
            } catch (BodyTooLargeException expected) {
                rejected++;
            }
            check(rejected == 2, "没有正确拒绝错误响应");
            System.out.println("PASS text=" + first + " requests=4 rejected=2");
            System.out.println("LOCAL ONLY: no external service, no credentials.");
        } catch (InterruptedException interrupted) {
            // 中断属于协作取消信号，不能打印一下就继续执行任务。
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            // 只停止本例创建的服务器、线程池，不操作其他进程或端口。
            if (server != null) {
                server.stop(0);
            }
            stopPool(bodyWorkers);
            stopPool(serverWorkers);
        }
    }

    /** HTTP 状态与实际正文读取都要检查；收到响应头并不代表正文已经读完。 */
    private static String getText(HttpClient client, URI uri,
                                  ThreadPoolExecutor bodyWorkers)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "text/plain")
                .GET()
                .build();

        // send 会等待响应；ofInputStream 让正文以流提供，不自动聚合整个正文。
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            // JDK HttpClient 不会仅因为 HTTP 404/500 就自动抛 IOException。
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode());
            }

            // ofInputStream 返回后，正文读取仍可能阻塞，因此另设读取等待预算。
            // 这演示的是已知 JDK HTTP 响应流的取消；不是任意 InputStream 的硬中断保证。
            Future<String> read = bodyWorkers.submit(() -> readBoundedUtf8(body));
            try {
                return read.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new IOException("响应正文读取超时", timeout);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("正文处理失败", cause);
            } finally {
                // cancel(true) 请求中断；外层关闭 HTTP 响应流，不能仅依赖中断标记。
                // 正常完成后的 cancel 返回 false，不会改变已经得到的结果。
                read.cancel(true);
            }
        }
    }

    /** 只将上限内的小正文转成字符串；大文件应逐块写文件而不是返回 String。 */
    private static String readBoundedUtf8(InputStream input) throws IOException {
        byte[] buffer = new byte[256];
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            // InputStream 一次 read 不保证填满 buffer，只处理本次实际读取 count 字节。
            if (count > MAX_BODY_BYTES - total) {
                throw new BodyTooLargeException();
            }
            output.write(buffer, 0, count);
            total += count;
        }
        // 本机服务明确约定 UTF-8；不能把任意图片、压缩包按 UTF-8 解码。
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 发送有限的小型测试正文，结束时关闭输出流与 exchange。 */
    private static void respond(HttpExchange exchange, int status, byte[] bytes)
            throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    /** 有界线程池；这个实验只顺序发出四次请求，不模拟生产负载。 */
    private static ThreadPoolExecutor newPool(int threads, String name) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), task -> new Thread(task, name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 有界等待线程池退出；shutdownNow 只是协作停止请求，不会强杀线程。 */
    private static void stopPool(ThreadPoolExecutor pool) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("示例线程池没有及时结束");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 使用普通条件检查，因此无需 java -ea 也会发现失败。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 独立异常让调用者明确区分 HTTP 错误与传输错误。 */
    private static final class HttpStatusException extends IOException {
        private final int status;
        private HttpStatusException(int status) {
            super("HTTP 状态异常：" + status);
            this.status = status;
        }
    }

    /** 上限超出是可识别的协议错误，不伪装成一个截断的成功响应。 */
    private static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException() {
            super("响应正文超过 " + MAX_BODY_BYTES + " 字节");
        }
    }
}
