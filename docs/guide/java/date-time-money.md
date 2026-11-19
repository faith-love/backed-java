# 日期时间与金额：把业务数值保存准确

日期时间和金额都不是“普通字符串”或“普通数字”。它们的业务含义由时区、币种、精度和舍入规则共同决定。把它们建模正确，可以避免账单差几分钱、跨时区时间错一天、夏令时重复时刻被排错等问题。

本章以 Java 11 为准，重点连接三处边界：Java 类型如何表达业务含义、JSON/API 如何传给前端、数据库如何持久化。项目中的支付链路还可以对照[支付宝与微信支付实战](../../examples/online-payment.md)，JDBC 类型映射见 [JDBC 类型映射](../data/jdbc.md#类型映射null-与生成主键)。

## 先选择表达业务含义的类型

### 日期时间类型解决什么问题

| Java 类型 | 表达的含义 | 常见场景 | 不适合做什么 |
|---|---|---|---|
| `Instant` | UTC 时间线上的一个确定时刻 | 支付成功时间、创建时间、日志事件时间 | 直接代表用户所在地区的“今天几点” |
| `LocalDate` | 不带时区的年月日 | 生日、营业日期、账期日期 | 推断一个精确的发生时刻 |
| `LocalTime` | 不带日期和时区的时分秒 | 每天 09:00 开门 | 代表一个完整的绝对时间点 |
| `LocalDateTime` | 不带时区的墙上日期与时间 | 预约表单输入、尚未指定时区的业务时间 | 存放跨系统事件时间而又未保存时区 |
| `OffsetDateTime` | 日期时间加固定 UTC 偏移，例如 `+08:00` | API 中明确带偏移的时间值 | 需要依据地区规则计算未来夏令时的时间 |
| `ZonedDateTime` | 日期时间加地区时区规则，例如 `Asia/Shanghai` | 把某个地区的本地时间转换成确定时刻 | 作为所有数据库列的默认类型；要根据存储契约决定 |
| `ZoneId` | 时区规则名称，例如 `Asia/Tokyo` | 解释地区时间、计算一天的起止点 | 当成单纯的 `+08:00` 偏移量 |

**判断顺序：** 如果数据表示“事情实际发生的时刻”，优先用 `Instant`；如果表示用户选择的日历日期，用 `LocalDate`；如果表示某个地区未来的营业/预约时间，同时保存 `ZoneId`，再根据地区规则换算。

例如，“支付于某个时刻成功”需要在全世界系统里指向同一个瞬间，所以保存 `Instant`；“门诊日期为 2026-11-18”是业务日历日期，不应因为部署服务器时区不同而变成前一天；“每周一当地时间 09:00 开会”需要日期规则和地区时区，固定偏移量可能无法正确处理夏令时变化。

### 把事件时刻显示为用户本地时间

后端通常保存确定的时间点，显示时再按用户选择或业务所属地区转换。不要把服务器默认时区当成用户时区。

```java
import java.time.Instant; // 使用时间线时刻表示已经发生的事件。
import java.time.ZoneId; // 使用地区规则名称表达显示时区。
import java.time.ZonedDateTime; // 将同一时刻转换成指定时区的本地日期与时间。
import java.time.format.DateTimeFormatter; // 使用固定格式生成便于阅读的文本。

public class EventTimeExample { // 定义一个可独立运行的事件时间示例。
    public static void main(String[] args) { // 从入口演示 UTC 存储与本地显示的边界。
        Instant paidAt = Instant.parse("2026-11-18T03:15:30Z"); // 解析 API 或数据库中带 Z 的 UTC 时刻。
        ZoneId businessZone = ZoneId.of("Asia/Shanghai"); // 明确使用业务地区的时区规则。
        ZonedDateTime displayedAt = paidAt.atZone(businessZone); // 保持同一瞬间，只改变显示地区。
        String isoText = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(displayedAt); // 输出带偏移量的 ISO 8601 时间。

        System.out.println("存储时刻：" + paidAt); // 输出规范化的 UTC 时刻，便于跨服务比较。
        System.out.println("本地显示：" + isoText); // 输出带 +08:00 的文本，避免接收方猜时区。
    } // 结束示例入口。
} // 结束事件时间示例类。
```

`2026-11-18T03:15:30Z` 与 `2026-11-18T11:15:30+08:00` 是同一个时刻。只要表示的是事件时间，传输时应包含 `Z` 或明确偏移量；只有 `2026-11-18T11:15:30` 的 `LocalDateTime` 不含足够信息，接收方只能猜测其时区。

### 夏令时会让本地时间重复或不存在

地区时区规则会随法律和历史变化。夏令时切换时，本地时间可能跳过一段区间，也可能把一段时间重复一次。固定写 `+08:00` 无法表达地区规则，`ZoneId` 才能按规则转换。

```java
import java.time.LocalDateTime; // 表示用户表单输入的墙上时间，不含时区。
import java.time.ZoneId; // 选择有夏令时规则的地区时区。
import java.time.ZoneOffset; // 表示某个时刻适用的 UTC 偏移量。
import java.time.zone.ZoneRules; // 查询时区规则在指定日期的有效偏移量。
import java.util.List; // 接收零个、一个或多个候选偏移量。

public class DaylightSavingExample { // 定义一个用于说明夏令时歧义的示例。
    public static void main(String[] args) { // 检查一个在纽约夏令时结束时重复的本地时间。
        LocalDateTime localInput = LocalDateTime.of(2024, 11, 3, 1, 30); // 用户输入凌晨一点半，但没有选 UTC 偏移量。
        ZoneId zoneId = ZoneId.of("America/New_York"); // 指定纽约地区规则，而不是固定写死偏移量。
        ZoneRules rules = zoneId.getRules(); // 取得该地区随日期变化的时区规则。
        List<ZoneOffset> validOffsets = rules.getValidOffsets(localInput); // 查询这个本地时间对应的候选偏移量。

        if (validOffsets.isEmpty()) { // 零个偏移量说明该本地时间落在夏令时跳过的时间段。
            throw new IllegalArgumentException("该本地时间不存在，请提示用户重新选择"); // 不应静默猜测一个时间点。
        } // 结束不存在时间的检查。
        if (validOffsets.size() > 1) { // 多个偏移量说明本地时间重复，存在多个可能的瞬间。
            System.out.println("时间有歧义，请业务明确选择偏移量：" + validOffsets); // 让业务或用户决定采用哪个瞬间。
        } else { // 一个偏移量表示本地时间在该地区规则下唯一。
            System.out.println("唯一偏移量：" + validOffsets.get(0)); // 输出可用于构造明确时刻的偏移量。
        } // 结束候选时刻分支。
    } // 结束示例入口。
} // 结束夏令时示例类。
```

预约、航班、跨地区会议等未来本地时间要明确处理“时间不存在”和“时间重复”的情况。默认调用 `localDateTime.atZone(zoneId)` 会按 Java 时区规则自动调整或选择其中一种解释；如果这个选择会影响合同、履约或计费，应先检查 `ZoneRules.getValidOffsets()`，把选择规则写入业务契约。

### 一天的起止点不能固定加 24 小时

按用户选择的本地日期查当天数据时，应先在业务时区求该日开始和下一日开始，再转换为 `Instant`。夏令时切换日可能不是 24 小时；用 `start.plus(Duration.ofHours(24))` 会把下一天边界算错。

~~~java
import java.time.Instant; // 将时区内的日历边界转换为数据库可比较的确定时刻。
import java.time.LocalDate; // 表示用户选择的业务日期。
import java.time.ZoneId; // 以用户或业务所属地区计算日历边界。
import java.time.ZonedDateTime; // 将当地零点解释为带时区规则的时间。

public class DayRangeExample { // 定义用于分页查询某一天事件的示例。
    public static void main(String[] args) { // 从一个本地日期构造半开时间区间。
        LocalDate selectedDate = LocalDate.of(2026, 11, 18); // 接收页面日期选择器提交的年月日。
        ZoneId userZone = ZoneId.of("Asia/Shanghai"); // 使用接口契约明确的用户地区时区。
        ZonedDateTime localStart = selectedDate.atStartOfDay(userZone); // 取得该日期在该时区的开始时刻。
        ZonedDateTime localNextStart = selectedDate.plusDays(1).atStartOfDay(userZone); // 按日历规则计算下一天开始。
        Instant startInclusive = localStart.toInstant(); // 将包含的起点转换成 UTC 时间线上的时刻。
        Instant endExclusive = localNextStart.toInstant(); // 将不包含的终点转换成 UTC 时间线上的时刻。

        System.out.println("created_at >= " + startInclusive); // 查询包含当天零点的数据。
        System.out.println("created_at < " + endExclusive); // 查询排除下一天零点的数据，避免精度边界遗漏。
    } // 结束示例入口。
} // 结束日期范围示例类。
~~~

数据库查询建议采用 `created_at >= startInclusive AND created_at < endExclusive`，不要用 `23:59:59.999...` 拼一天的末尾时间。半开区间不依赖数据库列的毫秒/微秒精度，也更容易使用索引。

## JSON 和数据库要约定时区语义

| 业务数据 | 推荐的 API 表达 | 数据库选择与约束 |
|---|---|---|
| 已发生的事件时刻 | ISO 8601 带 `Z` 或偏移量，例如 `2026-11-18T03:15:30Z` | 选择能表示该瞬间的列和统一连接时区；必要时按 UTC 读写 |
| 日历日期 | `2026-11-18` | `DATE`；不转换成服务器时区的午夜时刻 |
| 尚未指定时区的本地预约输入 | 本地日期时间加明确 `zoneId`，或转成已确认时刻后保存 | 分别保存本地输入和时区，或保存计算后的瞬间及业务规则，不能丢失解释依据 |
| 持续时间 | 按契约用 ISO 8601 duration 或整数毫秒/秒 | 使用数值时明确单位；不要把 duration 当成日期时间 |

MySQL 的 `TIMESTAMP` 会受到连接时区转换行为影响，`DATETIME` 储存日期与时间字段但本身不附带时区。JDBC 驱动、数据库 session time zone 和 ORM 映射也会影响读写结果。团队应选择一种约定（例如事件时刻统一 UTC），然后用测试验证“写入 → 查询 → API 序列化 → 浏览器显示”往返仍是同一瞬间。

Spring/Jackson 对 Java 时间类型的序列化行为取决于依赖模块和配置。不要假设一个无偏移的 `LocalDateTime` 会自动变成 UTC，也不要只靠全局格式化注解改变时间语义。API 合约应说明类型、时区、精度和字符串格式；实现测试至少覆盖带 `Z`、带 `+08:00`、日期边界和夏令时场景。

## 金额用 BigDecimal 或最小货币单位表达

### BigDecimal 解决十进制精度问题

`double` 使用二进制浮点近似表示许多十进制小数。金额计算若用 `double`，`0.1 + 0.2` 可能得到近似值，而不是精确的 `0.3`。`BigDecimal` 以十进制无比例整数和 scale 表达数值，适合单价、税额和汇总金额。

| 写法 | 结果 | 建议 |
|---|---|---|
| `new BigDecimal("0.10")` | 精确表示十进制 0.10，scale 为 2 | 优先用字符串或从已定义的十进制文本创建 |
| `BigDecimal.valueOf(10L)` | 精确表示整数 10 | 整数金额可安全转换 |
| `new BigDecimal(0.1)` | 把二进制浮点近似值完整转成十进制 | 禁止用于金额 |
| `BigDecimal.valueOf(0.1)` | 使用 `Double.toString` 的可读表示 | 能避免某些构造误差，但源头若已经过 `double` 运算，精度问题仍然存在 |

`BigDecimal` 的 `scale` 是小数位数，`precision` 是有效数字总位数。数据库 `DECIMAL(p, s)` 的 `p` 和 `s` 必须按业务最大金额和小数位设计；取值超过列范围可能失败或在某些配置下发生截断。

### 舍入规则必须成为业务规则

除法可能产生无限小数，例如 `10 / 3`。调用 `divide()` 时应选择目标 scale 和舍入模式；不能依赖默认行为，也不应在每一步计算后都随意四舍五入。舍入发生在哪一层（商品行、订单、税额、渠道提交）会影响最终账单。

```java
import java.math.BigDecimal; // 用十进制表示金额，避免二进制浮点误差。
import java.math.RoundingMode; // 显式选择除法和舍入时的业务规则。

public class MoneyArithmeticExample { // 定义金额精度示例。
    public static void main(String[] args) { // 展示精确计算、比例分摊和数值比较。
        BigDecimal unitPrice = new BigDecimal("19.90"); // 从十进制字符串读取单价，不经过 double。
        BigDecimal quantity = BigDecimal.valueOf(3); // 用整数数量参与金额乘法。
        BigDecimal subtotal = unitPrice.multiply(quantity); // 精确计算三件商品的小计 59.70。
        BigDecimal ratio = new BigDecimal("10").divide( // 将 10 除以 3，结果必须指定有限小数位。
            new BigDecimal("3"), // 指定除数，保持计算输入的十进制语义。
            2, // 规定结果保留两位小数。
            RoundingMode.HALF_UP // 使用业务明确约定的四舍五入规则。
        ); // 完成有目标 scale 和舍入规则的除法。
        BigDecimal formatted = subtotal.setScale(2, RoundingMode.UNNECESSARY); // 确认金额最多两位小数，否则让错误显式失败。
        boolean numericallyEqual = new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) == 0; // 用 compareTo 比较数值而不比较 scale。
        boolean representationEqual = new BigDecimal("1.0").equals(new BigDecimal("1.00")); // equals 同时比较数值和 scale，因此结果为 false。

        System.out.println("小计：" + formatted.toPlainString()); // 输出不使用科学计数法的 API/日志文本。
        System.out.println("10 除以 3：" + ratio.toPlainString()); // 输出按约定舍入后的结果。
        System.out.println("数值相等：" + numericallyEqual); // 显示金额业务比较应使用 compareTo 的原因。
        System.out.println("表示相等：" + representationEqual); // 显示 equals 会区分 1.0 与 1.00。
    } // 结束示例入口。
} // 结束金额运算示例类。
```

若计算利息、折扣或税额，需要先约定比例精度、舍入模式和舍入层级，再对边界值编写测试。`RoundingMode.UNNECESSARY` 可以在不允许超出 scale 时让异常尽早暴露；它不是适用于所有场景的通用舍入规则。

金额比较、排序时使用 `compareTo()`：负数表示小于、0 表示数值相等、正数表示大于。`equals()` 还要求 scale 相同；如果把 `BigDecimal` 用作 HashMap/HashSet key，`1.0` 和 `1.00` 也会被当作不同 key。要先决定金额表示是否需要规范化。

### 币种决定小数位，不能总是假设两位

人民币和美元通常以两位小数展示，日元等币种常见的小数位不同。支付渠道和税务系统也可能对小数位、舍入时点、现金舍入和最大金额有特定规定。金额数据需要至少与币种关联：`(amount, currency)`，不能单独存储一个含义不清的 `19.90`。

对支付订单，整数最小单位（例如人民币“分”）易于做条件比较和防止小数传输问题；但要根据币种、渠道 API、退款与汇率规则定义换算。不能把“乘 100”当作所有币种通用算法，也要检查 `long` 的溢出边界。数据库若存 `DECIMAL`，API 和数据库的小数位/范围依然必须一致。

## 前端与后端的金额契约

JavaScript `Number` 是 IEEE-754 双精度浮点数，整数只在 `Number.MAX_SAFE_INTEGER` 范围内连续精确表示，十进制小数也不是普遍精确。下面两种常见契约各有边界：

| 契约 | JSON 示例 | 优点 | 必须约定 |
|---|---|---|---|
| 金额十进制字符串加币种 | `{"amount":"19.90","currency":"CNY"}` | 避免前端把 JSON 小数解析成不精确的 Number；保留格式与币种 | 前端用十进制金额库计算/格式化；后端校验精度和范围 |
| 最小货币单位整数加币种 | `{"amountMinor":1990,"currency":"CNY"}` | 加减比较简单，便于支付渠道整数分接口 | 约定币种小数位和字段范围；前端的大整数可能要用字符串或 BigInt |

不要传没有币种的金额，也不要让浏览器自行决定金额精度。支付金额由后端依据商品、优惠和权限重新计算；前端提交的价格只能作为展示信息，不能作为扣款权威值。若用字符串，服务端要拒绝指数格式、负数、超出币种 scale 和范围的输入，不能简单 `parseDouble()` 再转回 `BigDecimal`。

```java
import java.math.BigDecimal; // 将 API 中的十进制字符串精确解析成金额。
import java.math.RoundingMode; // 让不符合币种小数位的输入显式失败。
import java.util.Currency; // 使用标准币种代码表达金额单位。

public class ApiMoneyValidationExample { // 定义 API 金额边界校验示例。
    public static BigDecimal parseAmount(String amountText, String currencyCode) { // 接收 JSON 字符串金额和币种代码。
        Currency currency = Currency.getInstance(currencyCode); // 校验币种代码是否受 Java 货币表识别。
        int scale = currency.getDefaultFractionDigits(); // 读取常见币种默认小数位，但业务仍需核对渠道规则。
        BigDecimal amount = new BigDecimal(amountText); // 直接从字符串创建 BigDecimal，不经过 double。
        BigDecimal normalized = amount.setScale(scale, RoundingMode.UNNECESSARY); // 拒绝需要舍入才能符合币种 scale 的输入。
        if (normalized.signum() < 0) { // 检查本接口是否只接受非负支付金额。
            throw new IllegalArgumentException("支付金额不能为负数"); // 在 API 边界返回明确的字段校验错误。
        } // 结束负金额检查。
        return normalized; // 返回已通过格式、精度和符号校验的金额值。
    } // 结束金额解析方法。

    public static void main(String[] args) { // 用字符串金额演示 API 调用边界。
        BigDecimal accepted = parseAmount("19.90", "CNY"); // 接受两位小数的人民币金额。
        System.out.println(accepted.toPlainString()); // 输出规范化的十进制字符串。
    } // 结束示例入口。
} // 结束 API 金额校验示例类。
```

`Currency.getDefaultFractionDigits()` 只是常见 ISO 币种元数据，不等于渠道、税务或现金交易规则。对特殊计价单位、积分、股数或自定义 token，应由业务明确 scale 和 rounding mode，不要借用普通货币的默认值。

## 落到数据库与支付时的检查清单

### 存储字段

- 精确小数可用 `DECIMAL(p, s)`，在 Java 映射为 `BigDecimal`；核实最大精度、小数位、符号和 `NULL` 语义。
- 最小货币单位可用 `BIGINT`/Java `long`，同时保存 ISO 币种代码；对退款、负值和汇率结果定义边界。
- 事件时间保存为统一含义的时刻，并把数据库 session time zone、JDBC Driver 和应用序列化配置纳入集成测试。
- 业务日期用 `DATE` 或 `LocalDate`；不要把生日或账期日期编码为午夜 UTC 的 `Instant`。
- 日期范围查询用起点包含、终点不包含的区间，避免硬编码一天的最后一毫秒。

### 支付/账单流程

1. 前端提交商品标识、数量和币种等选择，不提交可被信任的最终金额。
2. 后端读取服务端价格与优惠规则，使用 `BigDecimal` 或币种最小单位重新计算并保存定价快照。
3. 对支付渠道做精度、最大值和币种映射检查；转换成渠道要求的字符串或整数单位。
4. 通知验签后，把订单状态、支付流水和幂等记录放入数据库事务；时刻记录为统一的事件时间。
5. 响应时按 API 契约返回金额与币种，前端只负责无损展示，不能自行推断后端支付结果。

具体支付流程、验签边界和状态幂等见[支付示例](../../examples/online-payment.md)；HTTP 参数与响应 wrapper 见[响应结构与参数约定](../../api/response-contracts.md)。

## 常见错误与后果

| 错误做法 | 可能后果 | 修正方法 |
|---|---|---|
| 用 `LocalDateTime` 存支付成功时刻 | 不知道它属于哪个时区，跨区排序或审计可能偏移 | 用 `Instant` 保存事件时刻，按明确地区格式化显示 |
| 用服务器默认时区构造当天范围 | 同一日期在不同机器查出不同记录 | 用用户/业务 `ZoneId` 算当地开始与下一天开始 |
| 永远认为一天等于 24 小时 | 夏令时切换日的区间可能漏查或多查 | 按日历计算次日零点，不用固定时长代替 |
| `new BigDecimal(0.1)` | 得到二进制近似值的精确十进制展开 | 从字符串或精确整数单位构造 |
| `equals()` 用于金额数值比较 | `1.0` 和 `1.00` 被判为不等 | 数值比较使用 `compareTo()`，并明确规范 scale |
| 不指定除法舍入规则 | 无限小数导致 `ArithmeticException`，或结果语义不明 | 明确 scale、`RoundingMode` 和业务舍入节点 |
| 所有金额都乘/除 100 | 非两位小数币种和渠道单位转换错误 | 以币种和渠道契约定义最小单位与 scale |
| 把金额 JSON Number 直接交给 JS Number 计算 | 前端出现小数误差，或大整数失去精度 | 使用十进制字符串/最小单位整数，并选用无损计算方式 |

## 练习

1. 把支付回调时间从字符串解析成 `Instant`，按上海和纽约两个时区显示，确认底层时刻保持一致。
2. 选一个包含夏令时的地区，检查夏令时开始时的无效本地时间和结束时的重复本地时间，并说明预约界面如何处理。
3. 用 3 种方式计算 `0.1 + 0.2`：`double`、字符串构造的 `BigDecimal`、最小货币单位整数；记录差异。
4. 设计一份订单 API 的金额字段，比较“十进制字符串”和“最小单位整数”契约，并写明币种、小数位和最大范围。
5. 为每日账单查询构造半开时间范围，解释为何按 `LocalDate.plusDays(1)` 而不是增加 24 小时。

继续学习：[Java 类、对象与类型](./core.md)、[Stream 金额汇总](./streams.md)、[JDBC 日期和金额映射](../data/jdbc.md)、[支付交易示例](../../examples/online-payment.md)。
