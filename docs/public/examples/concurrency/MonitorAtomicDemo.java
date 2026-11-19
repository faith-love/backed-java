import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用同一个 monitor 保护库存关系，用 AtomicInteger 统计独立的成功次数。
 * 编译：javac -encoding UTF-8 --release 11 MonitorAtomicDemo.java
 * 运行：java MonitorAtomicDemo
 */
public class MonitorAtomicDemo {

    /** 单个 JVM 内存里的演示库存，不是数据库库存实现。 */
    static final class Stock {
        // 私有且不替换的对象让所有调用始终使用同一把锁。
        private final Object monitor = new Object();
        private int remaining = 100;
        private int sold;

        /** 检查和扣减必须属于同一个临界区，才能防止本地超卖。 */
        boolean sellOne() {
            synchronized (monitor) {
                // 库存不足时直接失败；退出代码块也会自动释放锁。
                if (remaining == 0) {
                    return false;
                }
                // 两个字段一起更新，保持 remaining + sold == 100。
                remaining--;
                sold++;
                return true;
            }
        }

        /** 读共享字段时使用相同的锁，返回不再变化的字符串快照。 */
        String snapshot() {
            synchronized (monitor) {
                return "剩余库存=" + remaining + "，已售=" + sold;
            }
        }
    }

    /** 每个工作线程最多尝试八十次；等待开始信号也有超时。 */
    private static Integer sellBatch(Stock stock, AtomicInteger successes,
            CountDownLatch start) throws InterruptedException {
        if (!start.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待开始信号超时");
        }
        int completed = 0;
        for (int attempt = 0; attempt < 80; attempt++) {
            if (stock.sellOne()) {
                // 该指标是独立计数，不负责保护 Stock 内部的两个字段。
                successes.incrementAndGet();
                completed++;
            }
        }
        return completed;
    }

    /** main 的 throws 只用于命令行演示，让失败明确终止程序。 */
    public static void main(String[] args) throws Exception {
        Stock stock = new Stock();
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(
                    () -> sellBatch(stock, successes, start));
            Future<Integer> second = workers.submit(
                    () -> sellBatch(stock, successes, start));
            // 信号用于协调开始，不假定线程一定按提交顺序执行。
            start.countDown();
            // 有界等待并读取任务失败；不靠 sleep 猜测任务是否完成。
            int soldByTasks = first.get(5, TimeUnit.SECONDS)
                    + second.get(5, TimeUnit.SECONDS);
            if (soldByTasks != 100 || successes.get() != 100) {
                throw new IllegalStateException("销售计数不符合预期");
            }
            System.out.println(stock.snapshot());
            // 所有任务已结束，此时独立指标与库存结果才能一起核对。
            System.out.println("原子成功计数=" + successes.get());
        } finally {
            // 即使提交或等待失败，也打开门闩并停止工作线程。
            start.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("工作线程未及时结束");
            }
        }
    }
}
