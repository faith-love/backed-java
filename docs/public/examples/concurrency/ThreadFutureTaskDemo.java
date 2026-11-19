import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 演示 Callable -> FutureTask -> Thread 的职责分离。
 * 同时验证：get 超时不会取消任务；cancel(true) 需要任务响应中断。
 */
public class ThreadFutureTaskDemo {

    public static void main(String[] args) throws Exception {
        // 第一个 Callable 立即返回结果，用于说明 FutureTask 如何保存返回值。
        FutureTask<Integer> sumTask = new FutureTask<>(() -> 20 + 22);
        Thread calculator = new Thread(sumTask, "calculator");

        // 第二个任务使用信号量等待，避免靠 sleep 猜测执行顺序。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        AtomicBoolean cleaned = new AtomicBoolean(false);

        // Lambda 对应 Callable<Integer>：有返回值，也允许抛出受检异常。
        FutureTask<Integer> waitingTask = new FutureTask<>(() -> {
            try {
                // 告诉主线程：任务已经进入执行流程。
                entered.countDown();

                // 等待的是业务信号，不是依靠固定休眠时间安排顺序。
                // 30 秒是故障保护；正常示例会立即通过 cancel(true) 结束等待。
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("业务信号等待超时");
                }
                return 99;
            } catch (InterruptedException exception) {
                // await 收到中断会抛异常，并清除当前线程的中断标记。
                sawInterrupt.set(true);
                Thread.currentThread().interrupt();
                throw exception;
            } finally {
                // 用于演示 finally 确实执行；实际业务会在此关闭任务拥有的资源。
                cleaned.set(true);
            }
        });
        Thread waiter = new Thread(waitingTask, "future-waiter");

        try {
            calculator.start();
            require(sumTask.get(3, TimeUnit.SECONDS) == 42, "计算结果应为 42");
            calculator.join(3_000);
            require(!calculator.isAlive(), "计算线程应已结束");
            System.out.println("PASS: Callable 返回值由 FutureTask.get() 取得：42");

            waiter.start();
            require(entered.await(3, TimeUnit.SECONDS), "等待任务未及时开始");

            // 主线程尚未发出 release 信号，任务此时不可能正常返回。
            boolean timedOut = false;
            try {
                waitingTask.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                timedOut = true;
            }
            require(timedOut, "get(timeout) 应报告超时");
            require(!waitingTask.isDone(), "get 超时不应自动取消任务");
            System.out.println("PASS: get(timeout) 只结束本次等待，任务仍未完成");

            // cancel(true) 请求取消，并尝试中断正在执行任务的线程。
            require(waitingTask.cancel(true), "尚未完成的任务应能接受取消");
            require(waitingTask.isCancelled(), "Future 应进入已取消状态");

            // 取消后的 get 抛 CancellationException，而不是返回 99。
            boolean cancelled = false;
            try {
                waitingTask.get(3, TimeUnit.SECONDS);
            } catch (CancellationException expected) {
                cancelled = true;
            }
            require(cancelled, "取消后 get 应抛 CancellationException");

            // isCancelled 只说明 Future 状态，join 才用于确认执行线程确实退出。
            waiter.join(3_000);
            require(!waiter.isAlive(), "等待线程应响应中断并退出");
            require(sawInterrupt.get(), "任务应观察到中断");
            require(cleaned.get(), "任务退出前应执行 finally 清理");
            System.out.println("PASS: cancel(true) 发出中断，任务响应后退出并完成清理");
        } finally {
            // 任何步骤失败都取消任务并发出解除等待信号。
            sumTask.cancel(true);
            waitingTask.cancel(true);
            release.countDown();
            cancelAndJoin(calculator, waiter);
        }
    }

    /** 清理不无限等待；在处理完线程后恢复清理期间收到的中断标记。 */
    private static void cancelAndJoin(Thread... threads) {
        boolean interrupted = Thread.interrupted();
        for (Thread thread : threads) {
            if (!thread.isAlive()) {
                continue;
            }
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        for (Thread thread : threads) {
            require(!thread.isAlive(), "线程清理失败：" + thread.getName());
        }
    }

    /** 显式断言不依赖 -ea；让验证结果能被脚本可靠判断。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
