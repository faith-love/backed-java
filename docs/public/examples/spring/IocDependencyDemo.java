/**
 * Java 11 兼容的 IoC/DI 教学示例。
 *
 * 本程序不引入 Spring，使用 main 手工充当“组合根”，让读者先看清：
 * 业务类只依赖接口，具体实现由外部装配；Spring 容器会在启动时完成类似装配。
 * 程序只在内存中运行，不连接数据库、不启动 HTTP 服务。
 */
public final class IocDependencyDemo {
    /** 服务能力契约：调用方只依赖稳定接口。 */
    interface GreetingService {
        String greet(String name);
    }

    /** 正式实现：这里只保留纯业务逻辑，方便独立运行。 */
    static final class DefaultGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            String safeName = name == null || name.trim().isEmpty()
                    ? "朋友" : name.trim();
            return "你好，" + safeName;
        }
    }

    /** Controller 不调用 new Service；构造器接收必需依赖。 */
    static final class GreetingController {
        private final GreetingService service;

        GreetingController(GreetingService service) {
            if (service == null) {
                throw new IllegalArgumentException("GreetingService 不能为 null");
            }
            this.service = service;
        }

        String getGreeting(String name) {
            return service.greet(name);
        }
    }

    /** 断言方法不依赖 -ea，失败会让进程以异常结束。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static void main(String[] args) {
        // main 是组合根：决定使用哪个实现，然后把它交给 Controller。
        GreetingService productionLike = new DefaultGreetingService();
        GreetingController controller = new GreetingController(productionLike);
        check("你好，Lin".equals(controller.getGreeting(" Lin ")),
                "正式实现没有完成输入清理");

        // 测试可以注入替身，不需要数据库或网络；这就是依赖注入的可替换性。
        GreetingService fake = name -> "FAKE:" + name;
        GreetingController testController = new GreetingController(fake);
        check("FAKE:测试".equals(testController.getGreeting("测试")),
                "Controller 没有调用注入的替身");

        System.out.println("PASS production=你好，Lin replacement=FAKE:测试");
        System.out.println("IoC CORE ONLY: main performs wiring; Spring would manage the same boundary.");
    }
}
