# 支付宝与微信支付：用并发、锁和事务守住订单状态

从 Vue 页面点击“支付”到商户订单变成“已支付”，至少跨越浏览器、商户后端和支付平台三个系统。本章用支付宝电脑网站支付、微信 Native 支付说明完整链路，并比较微信 H5、JSAPI 的入口差异。重点是把[线程](../guide/java/threads)、[锁](../guide/java/locks)、[线程池](../guide/java/thread-pools)和[并发](../guide/java/concurrency)放进一个会遇到重复请求、超时和多实例竞争的业务场景。

::: info 示例的验证范围
完整 Java 示例仅在本机内存模拟两个支付渠道，已用 JDK 17 的 `javac --release 11` 编译并运行；没有真实扣款、商户接入、网络支付请求或消息发送。SDK 片段根据官方 README 与源码核对了调用位置，尚未配置依赖、商户参数、密钥、回调域名，也未做渠道联调。SQL 是未执行的 MySQL 教学脚本。
:::

## 先把三个“订单”分清楚

| 对象 | 谁维护 | 解决的问题 |
|---|---|---|
| 商户业务订单 | 你的后端和数据库 | 保存买什么、谁购买、服务端核定金额、币种与业务状态 |
| 支付尝试/渠道订单 | 商户后端与支付平台 | 记录渠道、商户单号、有效期、支付入口及渠道交易流水 |
| 履约任务 | 你的订单/会员/库存服务 | 在确认收款后发货、开通权益、发积分或发送通知 |

本示例简化成“一张订单绑定一个渠道、接受一笔成功付款”。实际支持切换支付宝/微信、重新支付或部分退款时，应增加 `payment_attempt`、退款单和状态迁移规则；不能每点一次按钮就重新生成一个可独立扣款的商户订单号。

**金额的权威来源在服务端。** 前端提交商品 ID、数量、优惠券等选择；后端重新验证价格、库存、优惠、用户身份并保存定价快照。客户端传来的 `amount=1` 不能直接作为支付金额。内部使用 `long amountFen`、数据库使用 `BIGINT` 保存整数分，避免 `double` 的舍入误差。

例如 `1990` 分对应 `19.90` 元。支付宝网页支付适配器将分精确转换为两位小数的元字符串；微信 API v3 的总金额按其接口要求传整数分，并校验目标 SDK 的整数范围及渠道限额。

## 用户点击支付以后发生什么

~~~text
Vue：选择商品、提交订单
  -> 商户后端：鉴权、服务端定价、持久化 PENDING 订单
  -> 渠道适配器：使用这张订单发起支付或生成签名支付入口
  -> Vue/浏览器：跳转支付宝、显示微信二维码或调用对应客户端入口

用户在支付平台付款
  -> 支付平台：向商户服务端发送异步通知
  -> 商户后端：验签/必要解密 -> 核对订单 -> 短事务幂等记账
  -> 提交成功：按该渠道规定返回 ACK

后台工作者：读取 outbox -> 事务外投递消息 -> 消费者幂等履约
Vue：轮询商户后端订单状态 -> 显示已支付/处理中/已关闭
~~~

签名支付入口生成成功只说明可以进入收银台；浏览器回跳只说明用户回到了页面。**商户订单是否已支付，必须由后端确认的支付结果决定。** 权威结果通常来自验签后的异步通知，也可以来自经过官方 SDK 验证的主动查单结果。

## 支付入口的差异：前端到底要做什么

| 方式 | 常见场景 | 后端提供什么 | 前端做什么 | 仍不能证明什么 |
|---|---|---|---|---|
| 支付宝电脑网站支付 | PC 浏览器网页 | 官方 SDK 生成的签名跳转 URL 或表单 | 跳转收银台；回跳后查询本地订单 | URL 已生成、表单已提交、return_url 已访问都不等于付款成功 |
| 微信 Native | PC 展示二维码，用户用微信扫码 | Native 下单成功返回的 `code_url` | 将该内容生成二维码并轮询本地订单 | 二维码已显示或已扫描不等于已支付 |
| 微信 H5 | 手机浏览器中的网页支付 | H5 支付入口；后端按产品要求提供场景信息 | 按官方 H5 规则跳转，并在返回后查单 | 页面回来了不表示渠道已确认成功 |
| 微信 JSAPI | 微信内网页等符合产品要求的场景 | 根据用户身份和当前产品生成的调起支付参数 | 通过微信提供的客户端能力调起支付 | 客户端成功提示仍须后端确认最终订单状态 |

H5 和 JSAPI 不只是换一个 URL。它们涉及不同的产品开通条件、授权域名、用户标识和调起方式；JSAPI 的用户标识必须与目标应用匹配。微信小程序也有自己的客户端调用入口，不能把所有微信场景都当作浏览器扫码。本页的真实 API 片段选择 **Native**，其余两种方式以当前官方产品文档为准。

支付入口必须由已鉴权的后端签发，校验当前用户有权操作该订单，回跳地址来自服务端配置/允许列表。不要把任意 HTML 放入 Vue 的 `v-html` 执行；若选择表单模式，由受控页面输出 SDK 生成的表单。下面的支付宝片段采用跳转 URL，便于前端理解。

## 哪些地方需要线程，哪些地方需要锁

Web 容器通常已经用工作线程并发处理多个请求。收到通知后再 `new Thread()`，不会让同一笔支付更可靠。真正需要设计的是工作边界和状态共享范围。

| 步骤 | 建议执行方式 | 解决的问题 |
|---|---|---|
| 创建业务订单 | 请求线程中的短数据库事务 | 先保存金额和商户单号，避免平台订单没有本地归属 |
| SDK 下单/查询/关单 | 数据库事务之外，设置超时和并发上限 | 防止持有行锁等待远程网络；超时后可以按相同商户单号查明结果 |
| 通知验签/解密 | 通知请求线程内，进入短事务之前 | 确认请求真实性，未经验证的内容不进入业务状态机 |
| 订单更新、收款记录、outbox | 同一短事务，同步等待提交 | 只有可靠记录成功才能告诉平台“已收到并处理” |
| 发消息、发短信、生成账单、发货 | 提交后的可靠后台任务 | 慢任务不拖延支付通知，同时可在进程重启后恢复 |
| 前端轮询状态 | 有间隔、可取消、禁止重叠的请求 | 浏览器可以退出、刷新再恢复，不依赖某次回调在页面里执行 |

`public static` 只表示类级共享，不提供复合操作的原子性；`volatile` 只解决相应的可见性/排序问题，不能让“查状态—更新—发货”合成事务。`synchronized`、`ReentrantLock` 或 `ConcurrentHashMap.compute` 只能协调同一个 JVM 中使用相同锁/容器的线程。

部署两个实例后，它们拥有不同的堆内存和锁。于是支付幂等的最终保护必须落到共享数据库的条件更新、唯一约束和事务；不能靠给 Java 方法加 `synchronized` 防止两个实例重复发货。即使增加分布式锁，也不能代替业务唯一约束、过期后的恢复规则与幂等消费者。

## 创建支付：服务端先下单，再提供渠道入口

推荐顺序：

1. 验证登录用户和订单归属，根据服务端价格生成商户订单及支付尝试，提交数据库事务。
2. 在事务外调用适配器。微信 Native 下单是远程 API 请求；支付宝 v2 `pageExecute` 的网页模式主要生成签名跳转 URL/表单，浏览器随后访问收银台，不代表已经收款。
3. 对返回的支付入口和有效期做必要持久化，用条件更新防止覆盖已支付/已关闭状态。
4. 将入口返回给当前有权操作订单的前端；重复点击优先复用同一未过期尝试。

如果调用超时，状态是“结果未知”，不是“渠道一定没创建订单”。先用原商户单号查单，再按该产品规则重试或关闭；不要立即生成新的商户单号。重复创建业务订单也应有独立的客户端幂等键和数据库唯一约束，不能只依赖前端按钮禁用。

### 支付宝 v2 SDK：生成网页支付跳转 URL

以下为**待集成片段**，依赖 `com.alipay.sdk:alipay-sdk-java`。已核对官方 v2 README、`AlipayTradePagePayModel`、`AlipayTradePagePayRequest` 和 `AbstractAlipayClient.pageExecute`。它不使用支付宝 v3 的包名或认证协议。具体 SDK 版本、商户产品签约、密钥/证书配置需要在正式工程中固定并验证。

~~~java
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import java.math.BigDecimal;

/** SDK 集成示意：没有 main，不在知识库构建或并发模拟中执行。 */
public final class AlipayPageAdapter {
    // 由服务端配置层构造；私钥和证书只在服务端持有，不返回浏览器。
    private final AlipayClient client;
    private final String notifyUrl;
    private final String returnUrl;

    public AlipayPageAdapter(AlipayClient client, String notifyUrl, String returnUrl) {
        this.client = client;
        this.notifyUrl = notifyUrl;
        this.returnUrl = returnUrl;
    }

    /** 参数必须来自已鉴权、已持久化的服务端订单，调用前校验产品金额上限。 */
    public String createRedirectUrl(String orderNo, long amountFen, String subject)
            throws AlipayApiException {
        if (amountFen <= 0) {
            throw new IllegalArgumentException("订单金额必须为正整数分");
        }
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo); // 使用已保存的商户单号，重试时不随意换号。
        model.setSubject(subject); // 商品标题来自服务端，按产品限制校验长度和内容。
        model.setProductCode("FAST_INSTANT_TRADE_PAY"); // v2 电脑网站支付产品码。
        model.setTotalAmount(BigDecimal.valueOf(amountFen, 2).toPlainString()); // 分 -> 元，无浮点误差。

        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(notifyUrl); // 平台向服务端通知结果，需可被平台访问。
        request.setReturnUrl(returnUrl); // 浏览器回跳仅供交互，不在这里直接置为已支付。
        // 官方 v2 源码中 GET 模式生成签名跳转 URL，POST 模式生成表单。
        // 返回入口不代表用户完成付款；异常继续交给上层错误边界处理。
        return client.pageExecute(request, "GET").getBody();
    }
}
~~~

### 微信 API v3 SDK：Native 下单得到二维码内容

以下为**待集成片段**。官方 Java SDK README 在本次核对时展示 `com.github.wechatpay-apiv3:wechatpay-java:0.2.17`，并展示 `NativePayService.Builder().config(config).build()` 与 `prepay()`。实际工程应按选定版本的依赖和当前商户接入方式配置；下面注入已配置好的服务，不写入任何商户密钥。

~~~java
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;

/** 微信 Native SDK 集成位置；没有网络调用测试，也没有填写真实商户配置。 */
public final class WechatNativeAdapter {
    private final NativePayService service;
    private final String appId;
    private final String merchantId;
    private final String notifyUrl;

    public WechatNativeAdapter(NativePayService service, String appId,
                               String merchantId, String notifyUrl) {
        this.service = service;
        this.appId = appId;
        this.merchantId = merchantId;
        this.notifyUrl = notifyUrl;
    }

    /** 从服务端订单取参数；远程调用必须在数据库短事务外执行。 */
    public String createCodeUrl(String orderNo, long amountFen, String description) {
        if (amountFen <= 0) {
            throw new IllegalArgumentException("订单金额必须为正整数分");
        }
        Amount amount = new Amount();
        amount.setTotal(Math.toIntExact(amountFen)); // 检查 long -> int 溢出，另需校验渠道额度。
        amount.setCurrency("CNY"); // 本例订单只支持人民币，必须与持久化订单一致。

        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId); // 使用服务端配置，不允许客户端任意指定商户应用。
        request.setMchid(merchantId);
        request.setDescription(description);
        request.setNotifyUrl(notifyUrl);
        request.setOutTradeNo(orderNo);
        request.setAmount(amount);
        // 此处会发生真实远程 API 调用；本文只展示代码，不执行。
        // 超时不能直接判定下单失败；上层应保留商户单号并主动查单。
        return service.prepay(request).getCodeUrl();
    }
}
~~~

## 通知处理：验真、核对、持久化，然后 ACK

通知可能重复、延迟、乱序，也可能在用户已经回跳后才到达。客户端能模拟 HTTP 请求，因此回调 URL 必须根据渠道官方协议验签，不能仅检查 IP、URL 中的密文或一个自定义 Token。

### 先由适配器完成渠道级校验

支付宝 v2 表单通知与微信 API v3 通知不是同一种报文，不能共用一个自制“验签函数”。

- **支付宝 v2：** 按官方编码规则解析通知表单；使用选定公钥或证书模式的官方验签方法验证签名。公钥模式可见 `AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2")`；证书模式有对应的证书验签入口。支付宝公钥不是商户应用的公钥，二者不能混用。不要对已解析参数反复 URL 解码或通过随意拼接处理重复参数。
- **微信 API v3：** 保留原始 body 与请求头，交给 `NotificationParser` 验签、解密并映射支付通知对象。平台公钥/平台证书验证签名，API v3 密钥用于相关通知资源解密；它们的职责不同。
- **两者共同点：** 验签成功只证明报文来源和完整性，还要核对是否属于本应用、本商户、本订单、正确金额和支持的支付成功状态。

下面是已核对官方 SDK README 的微信解析位置。SDK/容器依赖、密钥配置和 Controller 仍需在工程中完成；示例不是可直接上线的回调接口。

~~~java
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import java.util.Map;

/** 将微信回调转换成已验签解密的对象；不在这里修改订单。 */
public final class WechatNotificationDecoder {
    private final NotificationParser parser;

    public WechatNotificationDecoder(NotificationParser parser) {
        // parser 由服务端 NotificationConfig 创建，按当前公钥/证书模式初始化。
        this.parser = parser;
    }

    public Transaction decode(String untouchedBody, Map<String, String> headers) {
        // headers 的键由 Controller 统一映射；HTTP 头实际不区分大小写。
        // untouchedBody 必须保留请求正文，不能 JSON.parse 再 stringify 后拿去验签。
        RequestParam request = new RequestParam.Builder()
                .serialNumber(headers.get("Wechatpay-Serial"))
                .timestamp(headers.get("Wechatpay-Timestamp"))
                .nonce(headers.get("Wechatpay-Nonce"))
                .signature(headers.get("Wechatpay-Signature"))
                .body(untouchedBody)
                .build();
        // 验签/解密失败应向错误边界抛出；不能 catch 后构造一个“成功”对象。
        return parser.parse(request, Transaction.class);
    }
}
~~~

真实工程还要根据所固定 SDK 版本处理签名类型、时间戳有效性、平台公钥/证书轮换、请求大小和重复报文，以及适配器异常到 HTTP 应答的映射。不能绕过 SDK 响应验签，也不能从前端选择回调使用哪套信任配置。

### 再由领域层核对订单

| 核对项 | 防止的问题 |
|---|---|
| 商户订单号 | 把无归属或其他业务订单的付款记到当前订单 |
| 渠道、商户号、应用 ID | 把另一商户/另一应用的合法通知当成自己的付款 |
| 总金额、币种 | 将低金额付款或其他币种记成当前订单足额支付 |
| 渠道交易状态 | 把创建成功、待支付、关闭等状态当作收款完成 |
| 渠道交易流水号 | 同一笔真实付款被重复关联到多张订单 |
| 本地订单状态 | 已支付再次履约，或关单任务覆盖已支付状态 |

比较的是所选支付产品的订单应付总额，不是扣除手续费后的商户结算净额。若支持优惠或其他币种，必须明确通知里的哪个字段对应订单定价快照。支付宝元字符串需精确转换为分并拒绝异常小数/范围；本例人民币产品的币种由已签约产品契约及本地订单限定，不凭空假设回调一定有与微信相同的币种字段。

同一订单已支付且流水、金额、商户、应用、币种全部一致，才能作为已完成的重复通知处理。若成功流水不同，应进入重复收款对账/退款流程，不能再次发货，也不能当成完全正常的通知重发。

### ACK 的位置比线程数量更重要

正确时序是：`验签/解密 -> 业务核对 -> 数据库事务提交 -> 渠道 ACK`。只把任务放进普通内存线程池就 ACK，进程随即崩溃时任务会丢失，支付平台却以为商户已接收。`@Async` 不是可靠消息队列。

本页的 `APPLIED`、`DUPLICATE` 只是领域处理结果，**不是渠道要求的应答文本**。HTTP 端点应按当前产品官方通知规范映射状态码和响应体；支付宝与微信的 ACK 不应混写。校验失败、数据库失败、提交结果未知时不得返回“已成功处理”；合法重复通知确认原事务已提交后可按规则确认。无法处理的业务冲突还应记录到可重放的异常队列/对账记录并告警，不能仅靠平台反复重试解决。

## 数据库幂等：状态、流水和 outbox 一起提交

示例使用三张表：

1. `payment_order` 以商户订单号为主键，固定金额、币种、商户/应用与状态。
2. `payment_receipt` 以订单号防止重复成功记录，并以 `(channel, merchant_id, provider_transaction_id)` 唯一约束防止流水跨订单复用。
3. `payment_outbox` 用 `(merchant_order_no, event_type)` 唯一约束保证一张订单只创建一个 `ORDER_PAID` 事件。

同一事务内先锁定订单并核对，再写收款记录，以 `WHERE status = 'PENDING'` 条件更新订单，最后写 outbox。任何一步失败都 `ROLLBACK`；更新影响行数不是 1 也必须抛出并回滚，不能继续插事件后提交。对于合法重复，读取并核实已支付结果后结束，不重复生成事件。

跨实例的控制来自 InnoDB 行锁和约束。行锁解决同一订单并发检查，条件更新表达允许的状态迁移，唯一键解决跨订单流水冲突，事务解决部分写入。这四个机制分工不同。

<a href="/examples/payments/payment-transactions.sql" download>下载完整事务 SQL</a>。以下脚本未在数据库执行；其中 outbox 后台领取部分明确标为应用绑定参数的伪代码。

::: details 展开建表、事务过程与 outbox 领取流程
<<< @/public/examples/payments/payment-transactions.sql
:::

如果把 SQL 改成 Spring `@Transactional` 方法，要确保事务代理生效，异常继续传播到事务管理器；按异常类型配置回滚规则。不要抓住唯一键异常继续提交部分修改。遇到死锁或瞬时数据库故障，回滚整个事务后再有界重试，并重新读取订单；通知重复本来就应该安全。

## outbox：提交订单以后，慢任务怎样不丢

outbox 解决“订单已支付，但发送消息失败”这个双写问题：订单和待发送事件在同一个数据库事务中提交。它不承诺消息恰好投递一次，而是提供可恢复的待处理事实。

后台调度器用短事务领取到期事件并写入租约，提交后在事务外发消息，收到消息代理确认后再用短事务标记已发送。领取可使用 `FOR UPDATE SKIP LOCKED`；租约超时由其他工作者回收，更新时必须检查本次领取令牌，防止旧线程覆盖新工作者的状态。

| 崩溃/失败位置 | 恢复方式 |
|---|---|
| 订单事务提交前 | 整体回滚，平台重发/主动查单后安全重试 |
| 订单提交后、后台尚未领取 | 数据库里仍有 outbox，后台恢复后继续处理 |
| 已领取、消息尚未发出 | 租约到期后重新领取 |
| 消息已发、未标记 SENT | 可能重复投递，消费者按 `event_id` 幂等处理 |
| 消费者完成履约、尚未确认消息 | 消费者重投时读取已提交的 inbox/业务结果，不重复履约 |

消费者应将 `inbox(event_id 唯一)` 与本地履约变更放入同一事务；先记“处理过”再执行不可回滚操作同样会丢任务。发送短信、发起外部物流等跨系统副作用还需要对方的幂等键、查询或补偿策略，不能只依赖消费者本地锁。

什么时候需要线程池？当领取、发送、履约要受控并发时，为工作者配置有界线程池、超时、队列上限、拒绝策略与可观测性。**可靠性来自数据库/消息队列中的任务事实，线程池只负责执行。** 队列满不能丢任务；失败要保留重试时间和次数，持续失败要进入告警/人工处理流程。

## 查单、关单与支付通知竞争

通知不是唯一恢复通道。用户等待过久、下单超时、通知未到或对账发现差异时，后端可主动查单。

- 查单在数据库事务外调用渠道，按官方 SDK 验证响应，核对与通知同样的商户、订单、金额和状态；确认已支付后复用同一幂等状态迁移，不另写一套“直接改支付成功”。
- 关单不能仅由浏览器倒计时决定。服务端确认过期规则后调用渠道关单，明确成功后再用条件更新迁移本地状态。
- 关单或查单超时表示结果未知，应持久化重查计划；不要直接改成 `CLOSED`，也不要为未知付款立即重复创建新的支付订单。
- 关单任务更新本地状态时，如果影响行数为 0，要重新读订单：支付通知可能已经先提交。不能把已支付状态覆盖为已关闭。
- 本地已关闭却收到可信的支付成功通知属于对账异常，应查明渠道最终结果并履约或退款；不能静默丢弃这笔资金事实。

生产状态机通常会增加 `CLOSING`、待查标记、最后查单时间和独立支付尝试。本例只保留 `PENDING/PAID/CLOSED` 来解释竞争条件，没有实现完整资金账务与退款系统。

## 前端轮询：只展示服务端确认的状态

建议后端提供类似 `GET /api/orders/{orderNo}/payment-status` 的商户接口，并校验订单归属，返回受控状态。这个路径是本页业务设计示意，不是支付宝或微信的接口。

### Vue 发起支付：只传订单标识，不传可信金额

下面的两个接口都是**需要你实现的商户后端 API**：`POST /api/payments/entry` 接收 `{ orderId }`，从服务端订单读取渠道和金额；查询接口读取已持久化的订单状态。示例中的 `orderId` 就是前后端共同约定的订单标识，是否映射商户订单号由后端决定。

示例可放入 Vue 组件的 `<script setup lang="ts">`，并与后面的 `waitForPayment` 辅助函数一起使用。认证、CSRF 防护和错误包装应接入项目现有请求层；扫码展示使用你项目中的二维码组件，不把 `codeUrl` 当网页跳转链接。

~~~ts
import { onUnmounted, ref } from 'vue'

type PaymentEntry =
  | { kind: 'ALIPAY_PAGE'; redirectUrl: string }
  | { kind: 'WECHAT_NATIVE'; codeUrl: string }

const qrText = ref('') // 交给项目的二维码组件，例如 <QRCode :value="qrText" />。
const statusText = ref('等待支付')
const busy = ref(false)
let activeRequest: AbortController | undefined

/** 用户点击支付按钮时调用；后端从订单查金额，绝不信任浏览器的售价。 */
async function startPayment(orderId: string) {
  if (busy.value) return // 防重复点击只是交互优化，真正去重在后端。
  busy.value = true
  activeRequest?.abort() // 切换订单时清理上一轮请求和轮询。
  const request = new AbortController()
  activeRequest = request
  const entryTimeout = setTimeout(() => request.abort(), 15_000)
  try {
    const response = await fetch('/api/payments/entry', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderId }), // 只传订单标识，不传金额、商户私钥或自选回跳地址。
      signal: request.signal,
    })
    if (!response.ok) throw new Error(`支付入口获取失败：HTTP ${response.status}`)
    const entry: PaymentEntry = await response.json()
    clearTimeout(entryTimeout)
    if (entry.kind === 'ALIPAY_PAGE') {
      // 后端只返回官方 SDK 生成、且通过服务端渠道域名允许列表校验的签名 URL。
      // 当前网页会跳转；回到 return_url 页面时重新按订单号调用 confirmPayment。
      window.location.assign(entry.redirectUrl)
      return
    }
    if (entry.kind !== 'WECHAT_NATIVE') throw new Error('不支持的支付入口')
    qrText.value = entry.codeUrl // Native 内容用于生成二维码，不用 v-html 或执行脚本。
    await confirmPayment(orderId, request.signal)
  } catch (error) {
    // 请求取消/超时不代表订单支付失败；用户可稍后查询同一个订单。
    statusText.value = request.signal.aborted
      ? '查询已暂停，请在订单页确认结果'
      : error instanceof Error ? error.message : '支付入口暂时不可用'
  } finally {
    clearTimeout(entryTimeout)
    busy.value = false
  }
}

/** Native 展示二维码后，以及支付宝回跳页加载后，都查询后端权威状态。 */
async function confirmPayment(orderId: string, signal: AbortSignal) {
  const status = await waitForPayment(orderId, signal)
  statusText.value = status === 'PAID' ? '支付已确认'
    : status === 'CLOSED' ? '订单已关闭'
    : '结果待确认，请稍后查看订单'
  // 只有后端返回 PAID 才显示确认；return_url 和 JSAPI 客户端回调不能直接设置 PAID。
}

onUnmounted(() => activeRequest?.abort()) // 离开页面时停止请求、轮询与相关定时器。
~~~

支付宝回跳页应先取到自己的订单标识，创建页面级 `AbortController` 后调用 `confirmPayment`，并同样在卸载时取消；不要把回跳参数中的“成功”字样写进本地订单状态。以上 TypeScript 类型只是开发约束，生产请求层还应校验响应形状和支付入口是否过期。

### 有截止时间、可取消的串行轮询

轮询要逐次等待上一个请求完成，避免 `setInterval` 在慢网下不断叠加；离开页面或已达终态时取消。轮询超时表示“仍待确认”，不是“支付失败”，之后可凭订单号继续查询。页面网络错误也不应触发后端把订单关掉。

~~~ts
type PaymentState = 'PENDING' | 'PAID' | 'CLOSED'

/** 示例业务接口约定；按你的 Axios 响应包装和认证方式调整。 */
async function waitForPayment(orderNo: string, outerSignal: AbortSignal): Promise<PaymentState | 'UNKNOWN'> {
  const controller = new AbortController()
  // 用户离开页面时，取消正在等待的 HTTP 请求和轮询间隔。
  const onAbort = () => controller.abort()
  outerSignal.addEventListener('abort', onAbort, { once: true })
  if (outerSignal.aborted) controller.abort()
  // 对整个等待过程设上限，包含单次网络请求，避免无限挂起。
  const timeout = setTimeout(() => controller.abort(), 120_000)
  try {
    while (!controller.signal.aborted) {
      const response = await fetch(`/api/orders/${encodeURIComponent(orderNo)}/payment-status`, {
        credentials: 'same-origin', // 示例使用同源会话，实际系统按既有认证层处理。
        signal: controller.signal,
      })
      if (!response.ok) throw new Error(`订单查询失败：HTTP ${response.status}`)
      const data: { status: PaymentState } = await response.json()
      if (data.status === 'PAID' || data.status === 'CLOSED') return data.status
      if (data.status !== 'PENDING') throw new Error('无法识别订单状态')
      // 串行等待，上一轮完成后才发起下一轮；控制请求数量。
      await abortableDelay(2000, controller.signal)
    }
    return 'UNKNOWN'
  } catch (error) {
    if (controller.signal.aborted) return 'UNKNOWN' // 取消/超时并非支付失败。
    throw error // 交给页面展示“查询失败，可重试”，不伪装成未付款。
  } finally {
    clearTimeout(timeout)
    outerSignal.removeEventListener('abort', onAbort)
  }
}

/** 可取消的间隔，结束时清理事件监听器，避免长时间轮询积累引用。 */
function abortableDelay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('已取消', 'AbortError'))
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', onAbort)
      reject(new DOMException('已取消', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}
~~~

在 Vue 中创建一个页面级 `AbortController`，启动 `waitForPayment`，并在 `onUnmounted` 中 `abort()`。页面隐藏时可暂停/降低轮询频率；刷新后从后端恢复订单状态。多个页面同时查单也应由后端限流/合并主动渠道查询，不要让每次浏览器轮询都直接请求支付平台。

## 完整可运行例：两个渠道的并发重复通知

<a href="/examples/payments/PaymentConcurrencyDemo.java" download>下载 PaymentConcurrencyDemo.java</a>。文件包含完整类型、适配器测试替身、内存仓库、并发入口和断言，无第三方依赖。

~~~powershell
# 在你的练习目录保存下载的源码，然后以 JDK 17 编译成 Java 11 兼容字节码。
# UTF-8 参数让 Windows 编译器正确读取中文注释和字符串。
javac -encoding UTF-8 --release 11 PaymentConcurrencyDemo.java

# 使用 JDK 17 运行；程序不联网、不发起支付，也不发送真实消息。
java PaymentConcurrencyDemo
~~~

预期输出中的两个 `SIMULATED_...` 表示假支付入口，最终必须出现：

~~~text
PASS applied=2 duplicate=158 outbox=2 rejected=3
SIMULATION ONLY: no real payment, no durable transaction, no message sent.
~~~

练习中的 `FixtureAdapter` 仅将登记的测试令牌映射成通知对象，不做密码学验签/解密；未知令牌会被拒绝。它只能用来验证**业务状态机的并发幂等**，不能替换官方 SDK，不能作为线上 HTTP 入口。

`InMemoryStore` 使用一个 `ReentrantLock` 保护不可变快照的整体发布。订单、流水归属、outbox 先在局部副本里准备完，再一次发布；异常会让原快照保持原样。这个设计让读者看清临界区边界，但会串行化所有订单，且重启丢失数据，完全不等于生产数据库事务。

::: details 展开完整中文注释源码
<<< @/public/examples/payments/PaymentConcurrencyDemo.java
:::

其中确定性断言检查：两个渠道各 80 次通知后，只应用两次状态更新；其余 158 次全部是重复；outbox 恰好两个事件；错误金额、跨订单重复流水和未经适配器识别的通知均被拒绝，并且拒绝后没有部分写入。断言不用 `assert` 关键字，所以无需额外传入 `-ea`。所有 `Future` 都调用 `get()`，工作线程异常不会被悄悄忽略。

## 带着问题练习

1. 画出代码中锁的开始和结束位置，说明为什么没有把 SDK 下单、通知验签和发消息放进临界区。
2. 假设部署两个 JVM，解释为什么每个进程的 `ReentrantLock` 都正常工作，业务仍可能重复；再指出 SQL 中承担跨实例保护的机制。
3. 在测试里增加错误币种、错误商户、非成功状态、已关闭订单通知，验证它们不会产生 outbox。
4. 把模拟的订单持久化替换成数据库事务，再模拟两实例同时通知；核对订单、收款记录与 outbox 是否一起成功或一起回滚。
5. 在“消息已发送但尚未标记 SENT”处模拟崩溃，说明消费者为什么也需要 `event_id` 唯一约束。
6. 设计前端显示文案：支付中、已支付、已关闭、查询暂时失败、轮询超时。说明哪些状态可以由浏览器自己确认，哪些必须查询后端。

## 官方接入资料与后续工作

本次只读核对时间为 **2026-11-17**，文档和 SDK 可能继续更新，正式工程应固定版本并按所选支付产品复核：

- [支付宝开放平台文档中心](https://opendocs.alipay.com/)：查找电脑网站支付、`alipay.trade.page.pay`、异步通知、查询和关单。
- [支付宝 Java SDK v2 README](https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/README.md)：配置、依赖和 v2 调用方式。
- [支付宝网页支付模型源码](https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/domain/AlipayTradePagePayModel.java)与[pageExecute 实现](https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/AbstractAlipayClient.java)：本页金额、产品码、GET 跳转 URL 的核对依据。
- [微信支付商户开发文档](https://pay.weixin.qq.com/docs/merchant/)：分别查 Native、H5、JSAPI 产品接入要求、通知及查关单规则。
- [微信 API v3 Java SDK](https://github.com/wechatpay-apiv3/wechatpay-java)：本页 Native 下单、`NotificationParser` 验签解密和 SDK 示例版本的核对依据。

**已验证的是本地 Java 并发模拟。正式 SDK 与通知端点仍待接入，数据库事务未执行。** 正式实现还需要商户签约及环境配置、服务端密钥/公钥或证书管理、SDK 依赖锁定、真实数据库迁移、渠道协议 ACK、可靠 outbox 工作者、主动查关单、退款/对账与对应联调。这些步骤完成前，不能把示例当成已经具备线上收款能力。
