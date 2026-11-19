# Java 对象与 JSON 契约

前端在 Network 面板看到的是 JSON 字节，后端业务代码处理的却是 Java 对象。Jackson 等消息转换器负责两者之间的序列化和反序列化；DTO 字段类型、全局 ObjectMapper 配置、注解和 HTTP 响应头共同决定最终契约。这个边界解决的是“Java 代码看起来类型正确，但浏览器拿到的值精度丢失、字段名变化或时间语义不明”的问题。

本章以 Java 11、Spring Boot 2.7 的 Jackson 2.x 为学习基线。实际字段格式由应用配置决定，应通过浏览器 Network 或契约测试验证，不能仅看 DTO 声明。

## Java 类型怎样变成前端类型

| Java 类型 | 常见 JSON 表达 | TypeScript 建议 | 主要风险 |
|---|---|---|---|
| `Long` 主键 | JSON number 或 string | 大整数 ID 用 `string` | JavaScript `number` 只能安全表示到 `2^53 - 1` |
| `BigDecimal` 金额 | JSON number 或 string | 精确金额常用 `string`，用 decimal 库计算 | JSON number 被 `JSON.parse` 转成二进制浮点数，金额可能失真 |
| `LocalDate` | `yyyy-MM-dd` | `string`（日期） | 它没有时区，不代表某个全球唯一时刻 |
| `Instant` / `OffsetDateTime` | ISO-8601 字符串 | `string`（带时区的时间） | 缺少时区约定会造成展示偏移或跨日错误 |
| `enum` | 字符串或数字 | 字符串联合类型，并保留未知值兜底 | 枚举重命名/新增可能让旧客户端报错 |
| `null` 字段 | `null` 或被省略 | 必须区分必填、可空和可省略 | PATCH 更新中“没传”与“显式清空”含义不同 |
| 集合 | JSON array | `T[]` | `null` 与空数组 `[]` 是不同契约 |

日期时间与金额的业务含义见[日期时间与金额](../java/date-time-money.md)。接口还应说明 JSON 使用的字段命名、空值策略、枚举值、错误响应、分页形状和 ID 精度。`Content-Type: application/json` 只说明媒体类型，不会替前后端自动校验字段语义。

## 示例：DTO 显式定义稳定输出

下面的响应 DTO 把数据库/领域模型转换为适合浏览器消费的契约。它把大整数 ID 和高精度金额输出为字符串，时间保留 UTC offset，状态使用稳定的业务 code；这些选择必须与前端类型和接口文档同步。

~~~java
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

// 枚举名称可以在代码中重构；对外接口使用显式、稳定的字符串 code。
enum OrderStatus {
    PENDING("pending"),
    PAID("paid"),
    CANCELLED("cancelled");

    private final String apiValue;

    // 保存序列化时使用的 API 值，而不是输出枚举在代码里的序号。
    OrderStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    // 告诉 Jackson 将状态序列化为字符串 code。
    @JsonValue
    public String getApiValue() {
        return apiValue;
    }
}

// 响应 DTO 与数据库 Entity 分开，避免表结构变化直接改变前端 API。
public final class OrderResponse {
    private final String id;
    private final BigDecimal amount;
    private final OffsetDateTime createdAt;
    private final OrderStatus status;

    // 将数据库 Long 主键显式转换为字符串，并传入已确定的时间和金额语义。
    public OrderResponse(Long id, BigDecimal amount, OffsetDateTime createdAt, OrderStatus status) {
        this.id = id == null ? null : id.toString();
        this.amount = amount;
        this.createdAt = createdAt;
        this.status = status;
    }

    // JSON 字段名固定为 id，避免全局命名策略改动影响 API。
    public String getId() {
        return id;
    }

    // 金额序列化为十进制字符串，避免浏览器浮点解析损失精度。
    @JsonSerialize(using = ToStringSerializer.class)
    public BigDecimal getAmount() {
        return amount;
    }

    // 保留 OffsetDateTime 自带的偏移量，使用可读的 ISO-8601 字符串。
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 输出稳定的状态 code，例如 "pending"。
    public OrderStatus getStatus() {
        return status;
    }
}
~~~

对应响应可能是：

~~~json
{
  "id": "9007199254740993",
  "amount": "99.90",
  "createdAt": "2026-11-18T09:30:00+08:00",
  "status": "pending"
}
~~~

金额如果选择 JSON number，也可以由后端输出 `99.90`；但浏览器原生 `JSON.parse` 会得到 JavaScript `number`，不能保证任意精度。无论用字符串还是数字，都不要让前端靠猜测决定精度策略。状态不要输出 `ordinal()` 数字，新增或重排枚举值会悄悄改变含义。

## 空字段、缺字段和未知字段

- `null`、缺失字段、空字符串和空数组不是同一状态。列表通常约定始终返回 `[]`，避免前端每处都处理 `null`。
- `@JsonInclude(NON_NULL)` 会省略 null 字段，可能影响旧客户端和 PATCH 语义；只在契约明确时设置。
- PATCH 往往需要区分“没传：保持原值”“传 null：清空”“传值：更新”。单一 nullable 字段可能表达不了三态，应该用显式 patch wrapper 或专门的字段状态类型。
- 输入 JSON 的额外字段是否接受由 ObjectMapper/DTO 配置决定。兼容旧客户端时可以选择忽略已知无害的未知字段，但仍要校验业务字段；不能用关闭校验来解决版本不兼容。
- 枚举新增值后，前端应对未知状态展示可理解的 fallback；服务端输入若遇到未知 code，则应按接口规则返回清楚的 400，而不是静默映射成另一种状态。

## 用序列化测试保护字段类型

Spring Boot 的 `@JsonTest` 能验证 Jackson 配置，而不必启动 Controller、数据库或完整应用。测试实际序列化后的 JSON，而不是只断言 DTO 的 Java getter。

~~~java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

// 只加载 JSON 序列化相关配置，适合验证 Java 类型到 JSON 的契约。
@JsonTest
class OrderResponseJsonTest {

    // 使用应用的 Jackson 配置，确保测试和 MVC 实际响应走同一套转换规则。
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldKeepIdAmountTimeAndStatusContract() throws Exception {
        // 构造超过 JavaScript 安全整数范围的 ID，并保留金额小数位和 UTC offset。
        OrderResponse response = new OrderResponse(
                9007199254740993L,
                new BigDecimal("99.90"),
                OffsetDateTime.parse("2026-11-18T09:30:00+08:00"),
                OrderStatus.PENDING);

        // 将对象序列化为 JSON 树，检查浏览器真正会收到的值和 JSON 类型。
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        // 大整数 ID 和精确金额都必须是字符串，避免客户端舍入。
        assertEquals("9007199254740993", json.get("id").asText());
        assertTrue(json.get("id").isTextual());
        assertEquals("99.90", json.get("amount").asText());
        assertTrue(json.get("amount").isTextual());

        // 时间和枚举值属于公开契约，格式/命名变化时该断言会提示需要评估兼容性。
        assertEquals("2026-11-18T09:30:00+08:00", json.get("createdAt").asText());
        assertEquals("pending", json.get("status").asText());
    }
}
~~~

这是 Boot 测试集成示例，需要项目已有 Jackson Java Time 支持和 `spring-boot-starter-test`；编译/运行时应使用项目实际管理的 Jackson 版本。修改 `ObjectMapper` 全局时区、命名策略或 null inclusion 后，重新运行这类测试，并用 MockMvc/真实接口测试确认 HTTP 响应一致。

## 从前端 Network 到兼容性检查

```ts
// decimal.js 以十进制字符串运算；金额类型不经过 JavaScript number。
import Decimal from 'decimal.js'

// 接口契约把精确 ID、金额和时刻都作为字符串，避免 TS number 的精度和时区歧义。
interface OrderResponse {
  id: string
  amount: string
  createdAt: string
  status: string
}

function addAmounts(left: string, right: string): string {
  return new Decimal(left).plus(right).toFixed(2)
}
```

TypeScript 接口不会验证网络实际返回的 JSON；运行时仍应使用 schema 校验或在 API 适配层检查数据。查看 Network 时逐项对照 JSON：ID 是否带引号、金额是否保留小数、时间是否含 offset、枚举是否是稳定字符串、null/空数组是否符合约定。前端类型最好由 OpenAPI 生成或与契约测试核对，相关流程见[Spring 测试中的 OpenAPI 专题](./testing.md#openapi-把前后端约定变成可检查的接口描述)。

## 练习

1. 选一个返回 `Long`、`BigDecimal`、时间和枚举的接口，记录 DTO、ObjectMapper 配置以及浏览器实际 JSON。
2. 把接口的数据库 ID 改成超过 `9007199254740991` 的演示值，验证前端是否保持字符串精度。
3. 为 `null` 与缺失字段分别写响应 fixture，说明前端如何处理；再讨论 PATCH 中“保持、清空、更新”三种语义。
4. 新增一个枚举值，列出旧前端、生成客户端和服务端输入校验可能出现的兼容性问题。

下一步：[Spring MVC 与 Controller](./mvc.md)、[OpenAPI 接口契约](./testing.md#openapi-把前后端约定变成可检查的接口描述)、[日期时间与金额](../java/date-time-money.md)。
