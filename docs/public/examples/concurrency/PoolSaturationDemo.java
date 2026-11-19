import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示线程池的核心线程、排队、扩容和拒绝，不用 sleep 猜测调度顺序。
 * 编译：javac -encoding UTF-8 --release 11 PoolSaturationDemo.java
 * 运行：java PoolSaturationDemo
 */
public final class PoolSaturationDemo {
    /**
     * 队列只有一个位置：第一个任务开核心线程，第二个排队，第三个扩容，第四个被拒绝。
     */
    public static void main(String[] args) throws Exception {
        // 名字便于从日志和线程转储定位具体工作池。
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 30, TimeUnit.SECONDS,
                // 队列必须有界，满后才有机会扩容到 maximumPoolSize。
                new ArrayBlockingQueue<>(1),
                task -> new Thread(task, "publish-worker-" + sequence.incrementAndGet()),
                // 显式失败，方便请求入口返回过载状态；不静默丢掉任务。
                new ThreadPoolExecutor.AbortPolicy());

        // 两个正在执行的任务都到齐后，主线程再检查饱和状态。
        CountDownLatch started = new CountDownLatch(2);
        // 让工作任务保持占用，直到主线程完成拒绝检查后放行。
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Integer>> accepted = new ArrayList<>();

        try {
            for (int id = 1; id <= 3; id++) {
                // Lambda 捕获每次迭代自己的值，不共享可变循环变量。
                final int taskId = id;
                accepted.add(pool.submit(() -> {
                    // 工作线程先声明自己已启动，再等待主线程释放。
                    started.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待演示放行超时");
                    }
                    // Callable 的返回值将由 Future.get 获取。
                    return taskId;
                }));
            }

            // 有界等待防止演示在异常环境中永远挂住。
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("两个工作线程未按预期启动");
            }
            if (pool.getPoolSize() != 2 || pool.getQueue().size() != 1) {
                throw new AssertionError("应为两个执行任务、一个排队任务");
            }

            // 此刻两个线程都被占用，队列也满；第四个任务一定触发拒绝。
            boolean rejected = false;
            try {
                pool.submit(() -> 4);
            } catch (RejectedExecutionException expected) {
                rejected = true;
                System.out.println("第四个任务被拒绝：线程数与队列容量已达上限");
            }
            if (!rejected) {
                throw new AssertionError("预期的饱和拒绝没有发生");
            }

            // 释放任务，再读取全部 Future，确保任务异常不会无人观察。
            release.countDown();
            int total = 0;
            for (Future<Integer> result : accepted) {
                total += result.get(5, TimeUnit.SECONDS);
            }
            if (total != 6) {
                throw new AssertionError("三个已接收任务的结果应为 1+2+3");
            }
            System.out.println("已接收任务完成，结果合计=6");
        } finally {
            // 无论主线程在哪一步失败，都先放行等待者，再回收池资源。
            release.countDown();
            for (Future<Integer> result : accepted) {
                if (!result.isDone()) {
                    // 取消未完成任务只是尝试中断；任务本身仍需响应中断。
                    result.cancel(true);
                }
            }
            stop(pool);
        }
    }

    /** 两阶段关闭；本示例任务会响应中断，实际 I/O 仍需配置客户端超时。 */
    private static void stop(ThreadPoolExecutor pool) throws InterruptedException {
        // 拒绝新提交，但允许已接收任务结束。
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                // 尝试中断正在执行的任务；并非强制杀线程。
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("工作池未在期限内退出");
                }
            }
        } catch (InterruptedException exception) {
            // 清理后向调用者继续传递中断，不吞掉停止信号。
            pool.shutdownNow();
            throw exception;
        }
    }
}
