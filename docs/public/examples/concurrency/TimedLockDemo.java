import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 展示 tryLock 的超时分支和成功获取锁后的 finally 释放。
 * 编译：javac -encoding UTF-8 --release 11 TimedLockDemo.java
 * 运行：java TimedLockDemo
 */
public class TimedLockDemo {

    /** 单个 JVM 内存里的演示库存，所有访问都遵守同一把锁。 */
    static final class Inventory {
        private final ReentrantLock lock = new ReentrantLock();
        private int available = 1;

        /** 超时只约束等锁时间，不限制取得锁后的业务执行时间。 */
        String reserve(long timeoutMillis) throws InterruptedException {
            if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
                // 没拿到锁，直接返回；此分支不能调用 unlock。
                return "忙碌，请稍后重试";
            }
            try {
                if (available == 0) {
                    return "库存不足";
                }
                available--;
                return "预留成功";
            } finally {
                // 只有取得锁的当前线程才能释放，每次获取对应一次释放。
                lock.unlock();
            }
        }

        /** 使用同一把锁读取库存，避免写操作与读操作采用不同协议。 */
        int remaining() {
            lock.lock();
            try {
                return available;
            } finally {
                lock.unlock();
            }
        }
    }

    /** 程序输出固定；主线程持锁只是测试夹具，不是生产调用方式。 */
    public static void main(String[] args) throws Exception {
        Inventory inventory = new Inventory();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 主线程确定地占有锁，保证另一线程的首次请求只能超时。
            inventory.lock.lock();
            try {
                Future<String> blocked = worker.submit(() -> inventory.reserve(100));
                // 仅在演示中持锁等 Future，worker 的 tryLock 有界，所以能结束。
                System.out.println("第一次请求：" + blocked.get(3, TimeUnit.SECONDS));
            } finally {
                inventory.lock.unlock();
            }
            // 第一笔任务结束且主线程已解锁，第二次请求没有竞争者。
            Future<String> successful = worker.submit(() -> inventory.reserve(100));
            System.out.println("第二次请求：" + successful.get(3, TimeUnit.SECONDS));
            System.out.println("剩余库存=" + inventory.remaining());
        } finally {
            // 发生异常时也清理执行器；示例中的任务能够响应中断。
            worker.shutdownNow();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("工作线程未及时结束");
            }
        }
    }
}
