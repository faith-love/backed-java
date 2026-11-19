import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 可运行示例：演示自定义注解的声明、使用、运行时读取和简单处理。
 *
 * <p>本文件只依赖 JDK 11，不依赖 Spring。它用反射模拟框架读取注解的最小过程：
 * 先找到方法，再检查方法上是否有 @Audit，最后决定是否输出审计信息。</p>
 *
 * <p>编译：javac -encoding UTF-8 --release 11 AnnotationReflectionDemo.java</p>
 * <p>运行：java -Dfile.encoding=UTF-8 AnnotationReflectionDemo</p>
 */
public class AnnotationReflectionDemo {

    /**
     * 程序入口：分别执行带注解和不带注解的方法，最后输出稳定的 PASS 标记。
     *
     * @param args 命令行参数，本示例不使用
     * @throws Exception 反射调用失败时向上抛出，便于示例保持简短
     */
    public static void main(String[] args) throws Exception {
        // 创建一个普通 Java 对象；没有 Spring 容器也可以使用运行时注解。
        OrderApplication application = new OrderApplication();

        // 框架通常根据类名、方法名或路由找到要执行的方法。
        invokeWithAudit(application, "createOrder", new Class<?>[]{String.class}, "order-1001");

        // health 方法没有 @Audit，因此处理器只执行业务方法，不输出审计事件。
        invokeWithAudit(application, "health");

        // 用固定输出作为人工或脚本验证标志。
        System.out.println("PASS annotation reflection demo");
    }

    /**
     * 读取方法上的 @Audit，并在真正调用业务方法前输出审计信息。
     *
     * @param target 目标对象
     * @param methodName 方法名
     * @param parameterTypes 方法参数类型
     * @param arguments 方法实参
     * @throws NoSuchMethodException 找不到方法时抛出
     * @throws InvocationTargetException 业务方法本身抛出异常时抛出
     * @throws IllegalAccessException 反射没有访问权限时抛出
     */
    private static void invokeWithAudit(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // getDeclaredMethod 按“方法名 + 参数类型”精确定位，避免重载方法找错。
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);

        // getAnnotation 只会返回运行时仍保留的注解；本例 @Audit 的 Retention 是 RUNTIME。
        Audit audit = method.getAnnotation(Audit.class);
        if (audit != null) {
            // 注解属性像只读配置一样提供给处理器；它们不是自动执行的业务代码。
            System.out.println("AUDIT action=" + audit.action() + ", operator=" + audit.operator());
        } else {
            // 明确输出未审计路径，便于观察“没有注解就不执行对应处理”的行为。
            System.out.println("NO_AUDIT method=" + methodName);
        }

        // 反射调用真正的业务方法；实际框架可能在这里执行代理、参数校验或事务逻辑。
        method.invoke(target, arguments);
    }

    /**
     * 为无参方法提供一个重载，调用方不需要手工创建空参数数组。
     *
     * @param target 目标对象
     * @param methodName 方法名
     * @throws NoSuchMethodException 找不到方法时抛出
     * @throws InvocationTargetException 业务方法本身抛出异常时抛出
     * @throws IllegalAccessException 反射没有访问权限时抛出
     */
    private static void invokeWithAudit(Object target, String methodName)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // 无参方法的参数类型数组长度为 0，实参同样为空。
        invokeWithAudit(target, methodName, new Class<?>[0]);
    }

    /**
     * 一个模拟订单应用的普通类。
     */
    private static final class OrderApplication {

        /**
         * 创建订单：这个方法需要留下审计事件。
         *
         * @param orderId 订单编号
         */
        @Audit(action = "create-order", operator = "system")
        private void createOrder(String orderId) {
            // 这里用打印代替数据库写入，确保示例可以脱离框架直接运行。
            System.out.println("BUSINESS create order=" + orderId);
        }

        /**
         * 健康检查：没有审计注解，演示注解处理器的条件分支。
         */
        private void health() {
            // 这里只表示业务执行成功，不触发审计输出。
            System.out.println("BUSINESS health=UP");
        }
    }

    /**
     * 自定义审计注解：它描述“需要审计的动作”，不直接实现审计逻辑。
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    private @interface Audit {

        /**
         * 审计动作名称，要求调用方明确填写。
         *
         * @return 动作名称
         */
        String action();

        /**
         * 操作者标识；实际项目通常来自登录用户上下文。
         *
         * @return 操作者标识
         */
        String operator() default "unknown";
    }
}
