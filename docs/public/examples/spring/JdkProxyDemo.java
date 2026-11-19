import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 不依赖 Spring 的 JDK 动态代理教学示例。
 * 观察代理前后增强、异常继续传播，以及直接调用目标对象会绕过代理。
 */
public final class JdkProxyDemo {
    interface GreetingService {
        String greet(String name);
        void fail();
    }

    static final class GreetingServiceImpl implements GreetingService {
        private int calls;

        @Override
        public String greet(String name) {
            calls++;
            return "你好，" + name;
        }

        @Override
        public void fail() {
            throw new IllegalArgumentException("示例业务异常");
        }
    }

    public static void main(String[] args) {
        GreetingServiceImpl target = new GreetingServiceImpl();
        AtomicInteger before = new AtomicInteger();
        GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
                JdkProxyDemo.class.getClassLoader(),
                new Class<?>[] { GreetingService.class },
                (Object ignored, Method method, Object[] arguments) -> {
                    before.incrementAndGet();
                    try {
                        // 代理必须显式转发目标调用；不调用 method.invoke 就会短路业务。
                        return method.invoke(target, arguments);
                    } catch (InvocationTargetException failure) {
                        // 反射会包装原异常；将真实业务异常继续传播给调用方。
                        throw failure.getCause();
                    }
                });

        String result = proxy.greet("前端同学");
        check("你好，前端同学".equals(result), "代理返回值错误");
        boolean observed = false;
        try {
            proxy.fail();
        } catch (IllegalArgumentException expected) {
            observed = true;
        }
        check(observed, "代理不应吞掉业务异常");
        int beforeDirect = before.get();
        target.greet("直接目标"); // 直接拿目标对象调用，绕过代理增强。
        check(before.get() == beforeDirect, "直接目标调用不应增加代理计数");
        check(target.calls == 2, "目标方法应执行两次");
        System.out.println("PASS proxyBefore=" + before.get()
                + " targetCalls=" + target.calls
                + " exceptionObserved=" + observed
                + " directTargetBypassedProxy=true");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
