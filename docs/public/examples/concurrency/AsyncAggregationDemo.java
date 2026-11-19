import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发组合两个独立查询；这是纯内存示例，不调用真实数据库或远程服务。
 * 编译：javac -encoding UTF-8 --release 11 AsyncAggregationDemo.java
 * 运行：java AsyncAggregationDemo
 */
public final class AsyncAggregationDemo {
    /** 任务先全部提交，再统一等待组合结果，避免每提交一项就立刻 get。 */
    public static void main(String[] args) throws Exception {
        // 应用中的池一般为共享 Bean；main 示例负责创建和关闭自己的池。
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                task -> new Thread(task, "summary-worker-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            // 两项独立查询都提交到显式的业务池，不使用共享 commonPool。
            CompletableFuture<String> customer = CompletableFuture.supplyAsync(
                    () -> queryCustomer("user-demo"), pool);
            CompletableFuture<Integer> paidOrders = CompletableFuture.supplyAsync(
                    () -> queryPaidOrders("user-demo"), pool);

            // thenCombine 只在两项都成功后拼响应；不会创建额外线程等待另一个 Future。
            CompletableFuture<String> summary = customer.thenCombine(paidOrders,
                    (name, count) -> name + ": paidOrders=" + count);
            try {
                // 统一给整个组合结果设置等待上限；本例 get 超时不等于底层任务停止。
                String result = summary.get(3, TimeUnit.SECONDS);
                if (!"learner: paidOrders=3".equals(result)) {
                    throw new AssertionError("组合结果不正确：" + result);
                }
                System.out.println(result);
            } catch (TimeoutException exception) {
                // CompletableFuture.cancel 的布尔参数不会中断实际执行函数。
                // 这里只终止句柄的后续使用；真实 HTTP/JDBC 必须设置自身超时。
                customer.cancel(false);
                paidOrders.cancel(false);
                summary.cancel(false);
                throw new IllegalStateException("查询汇总超时，不能当成查无订单", exception);
            } catch (InterruptedException exception) {
                // 当前调用已不再等待；保留中断语义交给上层，finally 仍会关闭池。
                customer.cancel(false);
                paidOrders.cancel(false);
                summary.cancel(false);
                throw exception;
            }

            // 再演示失败路径：一个必需查询失败，不能伪装成正常结果 0。
            CompletableFuture<Integer> failed = CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("模拟统计服务不可用");
            }, pool);
            try {
                failed.get(3, TimeUnit.SECONDS);
                throw new AssertionError("失败任务不应返回成功");
            } catch (ExecutionException exception) {
                // get 将工作线程异常包装为 ExecutionException；通过 cause 查看原因。
                if (!(exception.getCause() instanceof IllegalStateException)) {
                    throw exception;
                }
                System.out.println("失败分支已观察到 cause，没有替换成订单数 0");
            }
        } finally {
            stop(pool);
        }
    }

    /** 模拟用户查询；真实调用必须另配连接/读取超时并传递必要的鉴权信息。 */
    private static String queryCustomer(String userId) {
        if (!"user-demo".equals(userId)) {
            throw new IllegalArgumentException("示例只接受 user-demo");
        }
        return "learner";
    }

    /** 模拟订单统计；订单数 0 应当表示确实没有订单，而不是远程查询失败。 */
    private static int queryPaidOrders(String userId) {
        if (!"user-demo".equals(userId)) {
            throw new IllegalArgumentException("示例只接受 user-demo");
        }
        return 3;
    }

    /** 在独立示例退出时关闭线程池；业务请求不能关闭应用共享的池。 */
    private static void stop(ThreadPoolExecutor pool) throws InterruptedException {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("工作池未在期限内退出");
                }
            }
        } catch (InterruptedException exception) {
            pool.shutdownNow();
            throw exception;
        }
    }
}
