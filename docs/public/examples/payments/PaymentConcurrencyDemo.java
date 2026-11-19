import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Java 11 支付并发教学：不连接真实支付渠道、不使用密钥、不扣款。
 * 用 JDK 17 执行 javac -encoding UTF-8 --release 11 PaymentConcurrencyDemo.java。
 * 然后执行 java PaymentConcurrencyDemo；断言不依赖 java -ea 参数。
 * 真实系统必须用数据库事务和唯一约束代替这里的单 JVM 内存存储。
 */
public final class PaymentConcurrencyDemo {
    // 渠道是商户订单创建时确定的属性，不能根据回调里一个任意字段来选择验签配置。
    enum Channel { ALIPAY, WECHAT }

    // 简化状态机；真实系统还要记录支付尝试、关闭中、退款中及对账异常。
    enum Status { PENDING, PAID, CLOSED }

    // 这里只描述业务处理结果，不是支付宝或微信的 HTTP 应答协议。
    enum Result { APPLIED, DUPLICATE }

    /** 不可变订单快照；金额单位始终是整数分，所有价格来自服务端。 */
    static final class Order {
        final String id;
        final Channel channel;
        final String merchantId;
        final String appId;
        final long amountFen;
        final String currency;
        final Status status;
        final String transactionId;

        Order(String id, Channel channel, String merchantId, String appId,
              long amountFen, String currency, Status status, String transactionId) {
            require(amountFen > 0, "订单金额必须为正整数分");
            this.id = id;
            this.channel = channel;
            this.merchantId = merchantId;
            this.appId = appId;
            this.amountFen = amountFen;
            this.currency = currency;
            this.status = status;
            this.transactionId = transactionId;
        }

        // 创建新快照，避免部分修改对其他线程可见。
        Order paid(String providerTransactionId) {
            return new Order(id, channel, merchantId, appId, amountFen, currency,
                    Status.PAID, providerTransactionId);
        }
    }

    /**
     * 只能由受信任的渠道适配器输出的标准化通知。
     * 支付宝字段或微信验签解密后的字段，要在适配层映射到这个领域对象。
     */
    static final class VerifiedNotification {
        final String orderId;
        final Channel channel;
        final String merchantId;
        final String appId;
        final long amountFen;
        final String currency;
        final String transactionId;
        final boolean paymentSucceeded;

        VerifiedNotification(String orderId, Channel channel, String merchantId,
                             String appId, long amountFen, String currency,
                             String transactionId, boolean paymentSucceeded) {
            this.orderId = orderId;
            this.channel = channel;
            this.merchantId = merchantId;
            this.appId = appId;
            this.amountFen = amountFen;
            this.currency = currency;
            this.transactionId = transactionId;
            this.paymentSucceeded = paymentSucceeded;
        }
    }

    /** 与订单同次发布的待处理事件；生产中要持久化到 outbox 表。 */
    static final class OutboxEvent {
        final String eventId;
        final String orderId;

        OutboxEvent(String orderId) {
            this.orderId = orderId;
            this.eventId = "ORDER_PAID:" + orderId;
        }
    }

    /** 三份状态一起成为一个不可变快照，模拟一次原子提交。 */
    static final class Snapshot {
        final Map<String, Order> orders;
        final Map<String, String> paymentOwners;
        final Map<String, OutboxEvent> outbox;

        Snapshot(Map<String, Order> orders, Map<String, String> paymentOwners,
                 Map<String, OutboxEvent> outbox) {
            this.orders = Map.copyOf(orders);
            this.paymentOwners = Map.copyOf(paymentOwners);
            this.outbox = Map.copyOf(outbox);
        }
    }

    /**
     * 单 JVM 仓库，锁只保护本对象；两个服务进程不会共享它。
     * 临界区中没有 HTTP、消息发送或任何磁盘读写。
     */
    static final class InMemoryStore {
        private final ReentrantLock commitLock = new ReentrantLock();
        private volatile Snapshot state;

        InMemoryStore(List<Order> initialOrders) {
            Map<String, Order> orders = new HashMap<>();
            for (Order order : initialOrders) {
                require(orders.putIfAbsent(order.id, order) == null, "商户订单号重复");
            }
            state = new Snapshot(orders, Map.of(), Map.of());
        }

        // 读取不可变快照，无需长时间占用锁。
        Order find(String orderId) {
            Order order = state.orders.get(orderId);
            require(order != null, "商户订单不存在");
            return order;
        }

        /** 校验、去重、状态迁移和事件发布共享同一临界区。 */
        Result apply(VerifiedNotification notification) {
            commitLock.lock();
            try {
                Snapshot before = state;
                Order order = before.orders.get(notification.orderId);
                require(order != null, "商户订单不存在");

                // 重复通知也必须先核对完整业务信息，不能仅凭订单已支付就接受任何内容。
                require(order.channel == notification.channel, "渠道不匹配");
                require(order.merchantId.equals(notification.merchantId), "商户不匹配");
                require(order.appId.equals(notification.appId), "应用不匹配");
                require(order.amountFen == notification.amountFen, "金额不匹配");
                require(order.currency.equals(notification.currency), "币种不匹配");
                require(notification.paymentSucceeded, "不是支付成功事件");
                require(notification.transactionId != null
                        && !notification.transactionId.isBlank(), "渠道流水号为空");

                if (order.status == Status.PAID) {
                    // 已支付且同一渠道流水才是重复；第二笔真实付款需要对账/退款流程。
                    require(order.transactionId.equals(notification.transactionId),
                            "同一订单出现不同成功流水，需对账处理");
                    return Result.DUPLICATE;
                }
                require(order.status == Status.PENDING, "本地订单已关闭，需查单对账");

                // 领域内唯一键：同一渠道商户下的成功流水不可占用两个订单。
                String receiptKey = notification.channel + ":" + notification.merchantId
                        + ":" + notification.transactionId;
                require(!before.paymentOwners.containsKey(receiptKey), "渠道流水已归属另一订单");
                OutboxEvent event = new OutboxEvent(order.id);
                require(!before.outbox.containsKey(event.eventId), "重复支付事件");

                // 先在局部副本里准备全部改动；校验失败不会改动原始快照。
                Map<String, Order> orders = new HashMap<>(before.orders);
                Map<String, String> owners = new HashMap<>(before.paymentOwners);
                Map<String, OutboxEvent> outbox = new HashMap<>(before.outbox);
                orders.put(order.id, order.paid(notification.transactionId));
                owners.put(receiptKey, order.id);
                outbox.put(event.eventId, event);

                // 一次 volatile 引用替换发布完整结果，不代表磁盘持久事务。
                state = new Snapshot(orders, owners, outbox);
                return Result.APPLIED;
            } finally {
                // 无论校验是否抛错都释放锁，不能让异常把仓库永久锁住。
                commitLock.unlock();
            }
        }

        Snapshot snapshot() {
            return state;
        }
    }

    /**
     * 真实适配器边界：从服务端订单发起支付，再用官方 SDK 处理原始通知。
     * 这里只定义教学契约，返回值和方法名不代表任何渠道原生协议。
     */
    interface PaymentAdapter {
        String createPayment(Order serverOrder);
        VerifiedNotification verifyAndDecode(byte[] rawBody, Map<String, String> headers);
    }

    /**
     * 测试替身：把已登记的测试令牌映射为可信通知。
     * 不执行签名、验签、加密、解密或网络请求，不能用于线上 HTTP 回调。
     */
    static final class FixtureAdapter implements PaymentAdapter {
        private final Channel channel;
        private final Map<String, VerifiedNotification> fixtures = new ConcurrentHashMap<>();

        FixtureAdapter(Channel channel) {
            this.channel = channel;
        }

        // 只允许测试程序登记假通知；真实适配器根本不应提供此方法。
        byte[] register(String fixtureId, VerifiedNotification notification) {
            require(notification.channel == channel, "测试渠道不匹配");
            require(fixtures.putIfAbsent(fixtureId, notification) == null, "测试令牌重复");
            return fixtureId.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String createPayment(Order serverOrder) {
            require(serverOrder.channel == channel, "支付入口渠道不匹配");
            require(serverOrder.status == Status.PENDING, "订单不允许再发起支付");
            // 此文本不可打开或扫码，没有生成真实表单、支付 URL 或 JSAPI 参数。
            return "SIMULATED_" + channel + "_ENTRY_FOR_" + serverOrder.id;
        }

        @Override
        public VerifiedNotification verifyAndDecode(byte[] rawBody,
                                                     Map<String, String> headers) {
            VerifiedNotification fixture = fixtures.get(new String(rawBody, StandardCharsets.UTF_8));
            if (fixture == null) {
                // 模拟适配器拒绝未登记内容，不声称具备密码学真实性。
                throw new SecurityException("模拟适配器拒绝未知测试令牌");
            }
            return fixture;
        }
    }

    /** 通知入口：先做适配器校验，再同步提交业务状态，随后才能返回渠道要求的 ACK。 */
    static Result onNotification(PaymentAdapter adapter, byte[] rawBody, InMemoryStore store) {
        // 验签/解密位于数据库短事务之外；真实实现必须保存原始 body 和必要请求头。
        VerifiedNotification verified = adapter.verifyAndDecode(rawBody, Map.of());
        // 不另起线程后立即 ACK；先完成可靠持久化。这里仅模拟内存提交。
        return store.apply(verified);
    }

    /** 从服务端订单构造测试事件，金额绝不来自前端请求字段。 */
    static VerifiedNotification success(Order order, String transactionId) {
        return new VerifiedNotification(order.id, order.channel, order.merchantId,
                order.appId, order.amountFen, order.currency, transactionId, true);
    }

    /** 业务验证失败抛出异常；入口应记录并按渠道规则返回失败，不能吞掉。 */
    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 确定性测试断言，不受 JVM 是否启用 assertions 影响。 */
    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** 负例必须失败且不能改变任何已发布状态。 */
    static void expectRejected(InMemoryStore store, Runnable operation) {
        Snapshot before = store.snapshot();
        try {
            operation.run();
            throw new AssertionError("应拒绝非法通知");
        } catch (IllegalStateException | SecurityException expected) {
            // 这是测试断言的预期异常，不是业务逻辑忽略错误。
            check(store.snapshot() == before, "拒绝非法通知时发生了部分写入");
        }
    }

    /** 并发模拟两个渠道各重复通知 80 次，最终只有两次状态迁移和两个 outbox 事件。 */
    public static void main(String[] args) throws Exception {
        Order alipay = new Order("ORDER-ALI-001", Channel.ALIPAY, "DEMO-ALI-MERCHANT",
                "DEMO-ALI-APP", 1990, "CNY", Status.PENDING, null);
        Order wechat = new Order("ORDER-WX-001", Channel.WECHAT, "DEMO-WX-MERCHANT",
                "DEMO-WX-APP", 2990, "CNY", Status.PENDING, null);
        Order conflict = new Order("ORDER-ALI-002", Channel.ALIPAY, alipay.merchantId,
                alipay.appId, 1990, "CNY", Status.PENDING, null);
        InMemoryStore store = new InMemoryStore(List.of(alipay, wechat, conflict));
        FixtureAdapter aliAdapter = new FixtureAdapter(Channel.ALIPAY);
        FixtureAdapter wxAdapter = new FixtureAdapter(Channel.WECHAT);

        // 创建支付入口位于存储锁/数据库事务之外，不在锁中等待远程渠道。
        System.out.println(aliAdapter.createPayment(store.find(alipay.id)));
        System.out.println(wxAdapter.createPayment(store.find(wechat.id)));
        byte[] aliBody = aliAdapter.register("fixture-ali", success(alipay, "DEMO-ALI-TX"));
        byte[] wxBody = wxAdapter.register("fixture-wx", success(wechat, "DEMO-WX-TX"));

        // 有界线程池仅制造测试并发；真实 Web 容器已用自己的线程处理 HTTP 请求。
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 160; i++) {
                final boolean useAlipay = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    // 多个工作线程一起开始，制造同一订单的通知竞争。
                    start.await();
                    return onNotification(useAlipay ? aliAdapter : wxAdapter,
                            useAlipay ? aliBody : wxBody, store);
                }));
            }
            start.countDown();
            int applied = 0;
            int duplicate = 0;
            for (Future<Result> future : futures) {
                // 读取每个 Future，让任何后台异常都令测试失败；不静默丢弃。
                Result result = future.get(10, TimeUnit.SECONDS);
                if (result == Result.APPLIED) applied++;
                if (result == Result.DUPLICATE) duplicate++;
            }
            check(applied == 2, "只能首次应用两个订单");
            check(duplicate == 158, "其余通知必须全部识别为重复");
            check(store.find(alipay.id).status == Status.PAID, "支付宝订单未支付");
            check(store.find(wechat.id).status == Status.PAID, "微信订单未支付");
            check(store.snapshot().outbox.size() == 2, "outbox 必须恰好两个事件");
            check(store.snapshot().paymentOwners.size() == 2, "渠道流水归属必须唯一");

            // 已支付也不能接受错误金额；同时确认异常路径没有部分写入。
            VerifiedNotification wrongAmount = new VerifiedNotification(alipay.id, alipay.channel,
                    alipay.merchantId, alipay.appId, 1, "CNY", "DEMO-ALI-TX", true);
            byte[] badAmount = aliAdapter.register("fixture-wrong-amount", wrongAmount);
            expectRejected(store, () -> onNotification(aliAdapter, badAmount, store));
            // 同一渠道流水试图占用另一个订单，必须由全局唯一约束/教学锁阻止。
            byte[] reusedTx = aliAdapter.register("fixture-reused-tx", success(conflict, "DEMO-ALI-TX"));
            expectRejected(store, () -> onNotification(aliAdapter, reusedTx, store));
            // 未经过适配器验证的数据不能进入状态机。
            expectRejected(store, () -> onNotification(wxAdapter,
                    "unknown".getBytes(StandardCharsets.UTF_8), store));
            check(Objects.equals(store.find(conflict.id).status, Status.PENDING), "冲突订单被污染");
            System.out.println("PASS applied=2 duplicate=158 outbox=2 rejected=3");
            System.out.println("SIMULATION ONLY: no real payment, no durable transaction, no message sent.");
        } finally {
            // 即使提交或断言失败，也释放门闩并关闭线程池，避免 JVM 挂住。
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试线程池未按时退出");
            }
        }
    }
}
