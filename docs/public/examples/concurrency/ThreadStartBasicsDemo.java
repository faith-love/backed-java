import java.util.concurrent.atomic.AtomicReference;

/**
 * 对比 run()、start()，并演示继承 Thread。
 * 所有线程都会限时等待和清理；断言失败会让程序以错误退出。
 */
public class ThreadStartBasicsDemo {

    /** 主线程负责启动、观察结果和清理线程。 */
    public static void main(String[] args) throws InterruptedException {
        // AtomicReference 安全地记录任务实际运行在哪个线程。
        AtomicReference<String> executedBy = new AtomicReference<>();
        String callerName = Thread.currentThread().getName();

        // Runnable 只是任务内容；还没有创建新的执行流程。
        Runnable task = () -> executedBy.set(Thread.currentThread().getName());

        // new Thread 创建线程对象，此刻尚未启动。
        Thread worker = new Thread(task, "runnable-worker");
        NamedWorker child = new NamedWorker("subclass-worker");

        try {
            // 直接 run() 是普通方法调用，由当前调用线程同步执行。
            worker.run();
            require(callerName.equals(executedBy.get()), "run() 应由调用线程执行");
            require(worker.getState() == Thread.State.NEW, "run() 不会启动线程");
            System.out.println("PASS: run() 在调用线程执行，Thread 仍为 NEW");

            // start() 启动新线程，JVM 随后在该线程中执行 run()。
            worker.start();
            joinWithin(worker);
            require("runnable-worker".equals(executedBy.get()), "start() 应由新线程执行");
            require(worker.getState() == Thread.State.TERMINATED, "线程应已终止");
            System.out.println("PASS: start() 在新线程执行，join 后为 TERMINATED");

            // 每个 Thread 对象只能 start 一次，即使它已经执行结束。
            boolean rejected = false;
            try {
                worker.start();
            } catch (IllegalThreadStateException expected) {
                rejected = true;
            }
            require(rejected, "第二次 start() 必须失败");
            System.out.println("PASS: 同一个 Thread 不能二次 start()");

            // 继承 Thread 的子类也由 start() 启动，并覆盖 run() 定义任务。
            child.start();
            joinWithin(child);
            require("subclass-worker".equals(child.executedBy), "子类 run() 应在新线程执行");
            System.out.println("PASS: 继承 Thread 并覆盖 run() 也能定义任务");
        } finally {
            // 即使主线程等待被中断或断言失败，也尝试停止并回收所有已启动线程。
            cancelAndJoin(worker, child);
        }
    }

    /** 继承 Thread 的写法；业务开发通常更偏向 Runnable + 受管理的线程池。 */
    private static final class NamedWorker extends Thread {
        // 字段只由该线程写；主线程在成功 join 后读取，享有可见性保证。
        private String executedBy;

        private NamedWorker(String name) {
            super(name);
        }

        @Override
        public void run() {
            executedBy = Thread.currentThread().getName();
        }
    }

    /** join(timeout) 返回不代表任务结束，必须继续检查 isAlive()。 */
    private static void joinWithin(Thread thread) throws InterruptedException {
        thread.join(3_000);
        require(!thread.isAlive(), "线程未在 3 秒内结束：" + thread.getName());
    }

    /** 有限清理：请求中断并限时等待，保留清理期间收到的中断信号。 */
    private static void cancelAndJoin(Thread... threads) {
        boolean interrupted = Thread.interrupted();
        for (Thread thread : threads) {
            // 尚未启动或已经结束的线程无需清理。
            if (!thread.isAlive()) {
                continue;
            }
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException exception) {
                // 暂时记录信号，确保仍能尝试清理其他线程。
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

    /** 显式断言不依赖 JVM 的 -ea 参数。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
