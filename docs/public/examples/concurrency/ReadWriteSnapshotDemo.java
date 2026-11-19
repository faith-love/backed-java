import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 读写锁保护版本号和配置一起更新，读者得到独立且只读的快照。
 * 编译：javac -encoding UTF-8 --release 11 ReadWriteSnapshotDemo.java
 * 运行：java ReadWriteSnapshotDemo
 */
public class ReadWriteSnapshotDemo {

    /** 快照字段不可替换，Map 的值为不可变 Integer，因此浅拷贝已足够。 */
    static final class Snapshot {
        private final int version;
        private final Map<String, Integer> limits;

        Snapshot(int version, Map<String, Integer> source) {
            this.version = version;
            // 先复制再限制修改，不能只给原始共享 Map 套一个只读视图。
            this.limits = Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        /** 校验多字段的一致性，防止读到一半新、一半旧的配置。 */
        void verify() {
            if (limits.get("A") != version * 10
                    || limits.get("B") != version * 20) {
                throw new IllegalStateException("快照中的版本和配置不一致");
            }
        }

        @Override
        public String toString() {
            return "version=" + version + ", limits=" + limits;
        }
    }

    /** 多读少写的本地配置；所有共享字段都由同一对读写锁保护。 */
    static final class Settings {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final Map<String, Integer> limits = new LinkedHashMap<>();
        private int version;

        Settings() {
            // 构造结束前还未把对象交给其他线程，初始化不需要加锁。
            limits.put("A", 0);
            limits.put("B", 0);
        }

        void replace(int nextVersion) throws InterruptedException {
            lock.writeLock().lockInterruptibly();
            try {
                // 写锁独占：读者不能观察到下面三次赋值的中间状态。
                version = nextVersion;
                limits.put("A", nextVersion * 10);
                limits.put("B", nextVersion * 20);
            } finally {
                lock.writeLock().unlock();
            }
        }

        Snapshot snapshot() throws InterruptedException {
            lock.readLock().lockInterruptibly();
            try {
                // 必须在持有读锁时完成复制，释放锁后再复制仍会出现竞态。
                return new Snapshot(version, limits);
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    /** 有界等待任务起跑；不依赖 sleep 或线程调度顺序。 */
    private static void awaitStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待开始信号超时");
        }
    }

    /** 两个任务并发更新和读取，结束后验证旧快照没有被后续修改污染。 */
    public static void main(String[] args) throws Exception {
        Settings settings = new Settings();
        Snapshot original = settings.snapshot();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> writer = workers.submit(() -> {
                awaitStart(start);
                for (int version = 1; version <= 100; version++) {
                    settings.replace(version);
                }
                return 100;
            });
            Future<Integer> reader = workers.submit(() -> {
                awaitStart(start);
                for (int attempt = 0; attempt < 1000; attempt++) {
                    settings.snapshot().verify();
                }
                return 1000;
            });
            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            int verified = reader.get(5, TimeUnit.SECONDS);
            Snapshot current = settings.snapshot();
            current.verify();
            original.verify();
            System.out.println("旧快照：" + original);
            System.out.println("已核对快照数=" + verified);
            System.out.println("当前快照：" + current);
        } finally {
            start.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("工作线程未及时结束");
            }
        }
    }
}
