import java.math.BigDecimal; // 使用十进制定点数表示金额，避免 double 的二进制精度误差。
import java.util.Arrays; // 创建示例订单和订单明细列表。
import java.util.List; // 声明只读意义上的订单列表接口。
import java.util.Map; // 接收分组与分区后的键值结果。
import java.util.Optional; // 表示查询可能没有结果的 Top N 元素。
import java.util.stream.Collectors; // 提供分组、求和、计数和收集等 Collector。
import java.util.stream.Stream; // 订单明细通过流展开时需要 Stream 类型。

public class StreamBusinessDemo { // 定义可直接用 java 命令运行的示例入口类。
    public static void main(String[] args) { // Java 程序从 main 方法开始执行。
        List<Order> orders = Arrays.asList( // 创建三个用于报表示范的订单。
            new Order("A-100", OrderStatus.PAID, Arrays.asList( // 构造第一笔已支付订单。
                new OrderLine("耗材", "导管", 2, new BigDecimal("120.50")), // 第一单包含两件导管。
                new OrderLine("设备", "监护仪", 1, new BigDecimal("860.00")) // 第一单包含一台监护仪。
            )), // 结束第一笔订单的明细和对象构造。
            new Order("A-101", OrderStatus.PENDING, Arrays.asList( // 构造一笔尚未支付的订单。
                new OrderLine("耗材", "注射器", 10, new BigDecimal("3.20")) // 待支付订单包含十支注射器。
            )), // 结束待支付订单的明细和对象构造。
            new Order("A-102", OrderStatus.PAID, Arrays.asList( // 构造第二笔已支付订单。
                new OrderLine("耗材", "导管", 1, new BigDecimal("120.50")) // 第二单再购买一件导管。
            )) // 结束第二笔订单的明细和对象构造。
        ); // 完成订单数据源的创建。
        List<Order> paidOrders = orders.stream() // 从订单集合创建一条新流水线。
            .filter(order -> order.getStatus() == OrderStatus.PAID) // 只保留已经支付的订单以计算确认收入。
            .collect(Collectors.toList()); // 终端收集让前面的惰性过滤真正执行并生成新列表。
        BigDecimal totalRevenue = paidOrders.stream() // 为总收入创建独立 Stream，避免复用已消费的流。
            .flatMap(order -> order.getLines().stream()) // 把订单中的明细列表摊平为一条商品明细流。
            .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))) // 计算每行的金额等于单价乘数量。
            .reduce(BigDecimal.ZERO, BigDecimal::add); // 以零为加法单位元，将所有明细金额累加。
        Map<String, BigDecimal> revenueByCategory = paidOrders.stream() // 从已支付订单开始生成类别销售额报表。
            .flatMap(order -> order.getLines().stream()) // 将一对多的订单明细展开以便按明细类别统计。
            .collect(Collectors.groupingBy( // 按商品类别建立一个类别到汇总值的 Map。
                OrderLine::getCategory, // 选择商品明细的类别作为分组键。
                Collectors.mapping( // 在每个类别组内先把明细映射成金额再汇总。
                    line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())), // 将每条明细转换为行金额。
                    Collectors.reducing(BigDecimal.ZERO, BigDecimal::add) // 对同组金额使用 BigDecimal 安全累加。
                ) // 结束组内映射和归约 Collector。
            )); // 执行收集并得到每个商品类别的销售额。
        Map<String, Long> unitsByCategory = paidOrders.stream() // 再创建一条独立流水线统计商品件数。
            .flatMap(order -> order.getLines().stream()) // 展开每笔已支付订单的全部商品明细。
            .collect(Collectors.groupingBy( // 按商品类别分组并为每组产生一个统计结果。
                OrderLine::getCategory, // 使用明细类别作为 Map 的 key。
                Collectors.summingLong(OrderLine::getQuantity) // 对组内商品数量求和并返回 long。
            )); // 完成各类别销量统计。
        Map<Boolean, List<Order>> ordersByAmount = paidOrders.stream() // 对已付款订单做高低金额二分。
            .collect(Collectors.partitioningBy(order -> order.total().compareTo(new BigDecimal("500.00")) >= 0)); // true 代表金额至少五百元，false 代表较低金额。
        List<Order> topTwo = paidOrders.stream() // 生成用于查找 Top 2 的独立流水线。
            .sorted((left, right) -> right.total().compareTo(left.total())) // 按订单总额从高到低排序。
            .limit(2) // 排序后只保留金额最高的两笔订单。
            .collect(Collectors.toList()); // 收集 Top 2 订单供后续展示。
        Optional<Order> largestOrder = paidOrders.stream() // 为最大订单查找再创建一条流水线。
            .max((left, right) -> left.total().compareTo(right.total())); // 按订单总额比较并在没有订单时返回空 Optional。
        System.out.println("已支付订单数：" + paidOrders.size()); // 输出筛选后的订单数量。
        System.out.println("确认收入：" + totalRevenue); // 输出所有已支付订单的商品金额总和。
        System.out.println("按类别销售额：" + revenueByCategory); // 输出每个类别的收入分组。
        System.out.println("按类别销量：" + unitsByCategory); // 输出每个类别售出的件数。
        System.out.println("高额订单数：" + ordersByAmount.get(true).size()); // 输出达到五百元阈值的订单数量。
        System.out.println("低额订单数：" + ordersByAmount.get(false).size()); // 输出低于五百元阈值的订单数量。
        System.out.println("Top 2 订单：" + topTwo); // 输出排序后金额最高的两笔订单。
        System.out.println("最大订单：" + largestOrder.map(Order::getId).orElse("无订单")); // 安全处理最大订单可能不存在的情况。
    } // 结束示例程序的入口方法。
} // 结束订单销售报表示例类。

enum OrderStatus { // 声明订单状态，避免使用含义不清的字符串常量。
    PENDING, // 表示订单已提交但尚未完成支付。
    PAID // 表示订单已支付，可计入确认收入。
} // 结束订单状态枚举。

final class Order { // 定义订单对象并限制它被继承修改。
    private final String id; // 保存订单业务编号。
    private final OrderStatus status; // 保存订单当前状态。
    private final List<OrderLine> lines; // 保存订单包含的商品明细。

    Order(String id, OrderStatus status, List<OrderLine> lines) { // 通过构造器一次性提供订单所需字段。
        this.id = id; // 保存业务编号。
        this.status = status; // 保存订单状态。
        this.lines = lines; // 保存商品明细集合。
    } // 结束订单构造器。

    String getId() { return id; } // 提供订单编号的只读访问方法。
    OrderStatus getStatus() { return status; } // 提供订单状态的只读访问方法。
    List<OrderLine> getLines() { return lines; } // 提供订单明细的只读访问方法。

    BigDecimal total() { // 计算当前订单全部明细的金额总和。
        return lines.stream() // 从订单明细创建一个局部 Stream。
            .map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()))) // 把每个商品明细转换成行金额。
            .reduce(BigDecimal.ZERO, BigDecimal::add); // 使用 BigDecimal 累加并以零表示空订单总额。
    } // 结束订单金额计算方法。

    @Override // 声明此方法覆盖 Object 的文本表示方法。
    public String toString() { // 提供适合报表示例输出的订单摘要。
        return id + "=" + total(); // 用订单编号和订单金额构成易读摘要。
    } // 结束订单摘要方法。
} // 结束订单类。

final class OrderLine { // 定义一条订单商品明细。
    private final String category; // 保存商品类别，用于按类别分组。
    private final String product; // 保存商品名称，便于识别具体物品。
    private final int quantity; // 保存购买数量。
    private final BigDecimal unitPrice; // 保存商品单价。

    OrderLine(String category, String product, int quantity, BigDecimal unitPrice) { // 创建带类别、名称、数量和单价的明细。
        this.category = category; // 保存商品类别。
        this.product = product; // 保存商品名称。
        this.quantity = quantity; // 保存购买数量。
        this.unitPrice = unitPrice; // 保存单价。
    } // 结束明细构造器。

    String getCategory() { return category; } // 提供分组统计所需的类别。
    String getProduct() { return product; } // 提供展示商品名称所需的读取方法。
    int getQuantity() { return quantity; } // 提供销量统计所需的数量。
    BigDecimal getUnitPrice() { return unitPrice; } // 提供金额计算所需的单价。
} // 结束订单明细类。
