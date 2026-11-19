import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用共享条件演示 wait() 释放监视器、notifyAll() 不立即释放监视器。
 * 所有等待有限时；没有使用 sleep() 保证顺序。
 */
public class ThreadWaitSignalDemo {

    public static void main(String[] args) throws InterruptedException {
        Object monitor = new Object();
        Slot slot = new Slot();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                synchronized (monitor) {
                    // 仅表示已进入临界区，不依赖采样 Thread.State 推测是否已经 wait。
                    entered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

                    // 必须用 while：唤醒可能是虚假唤醒，条件也可能已被其他线程改变。
                    while (!slot.ready) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            throw new IllegalStateException("等待消息超时");
                        }
                        // 内部调用 monitor.wait(timeout)，等待期间释放 monitor。
                        // 返回或抛 InterruptedException 前必须重新获得 monitor。
                        TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                    }

                    // 读写 slot 都受同一把锁保护，字段不额外依赖 volatile。
                    received.set(slot.value);
                }
            } catch (InterruptedException exception) {
                // 中断表示取消：记录并退出，不继续执行依赖消息的后续工作。
                Thread.currentThread().interrupt();
                failure.set(exception);
            } catch (RuntimeException exception) {
                // 让主线程观察后台失败，避免只在后台打印异常而主程序误判成功。
                failure.set(exception);
            }
        }, "message-reader");

        try {
            reader.start();
            require(entered.await(3, TimeUnit.SECONDS), "读取线程未及时开始");

            synchronized (monitor) {
                // 能获得这把锁，说明 reader 已在等待时释放它（或异常退出）。
                require(failure.get() == null, "读取线程在发信号前失败");
                slot.value = "hello";
                slot.ready = true;

                // 只通知等待者重新竞争锁，不会在此行释放 monitor。
                monitor.notifyAll();
                require(received.get() == null, "持锁期间 reader 不应完成读取");
                System.out.println("PASS: wait 释放 monitor，notifyAll 不立即释放 monitor");
            }

            // 离开 synchronized 后释放锁，reader 才能重新获得锁并检查条件。
            reader.join(3_000);
            require(!reader.isAlive(), "读取线程未及时结束");
            require(failure.get() == null, "读取线程发生异常：" + failure.get());
            require("hello".equals(received.get()), "读取结果应为 hello");
            System.out.println("PASS: 释放 monitor 后读取到 hello");
        } finally {
            // 即使主线程中断，也唤醒并限时回收 reader。
            reader.interrupt();
            boolean interrupted = Thread.interrupted();
            try {
                reader.join(3_000);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            require(!reader.isAlive(), "读取线程清理失败");
        }
    }

    /** 共享条件和消息始终在 monitor 锁内访问。 */
    private static final class Slot {
        private boolean ready;
        private String value;
    }

    /** 断言失败直接退出，便于脚本验证预期行为。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
