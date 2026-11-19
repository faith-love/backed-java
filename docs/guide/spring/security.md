# 认证与授权

认证（Authentication）回答“请求是谁发出的”；授权（Authorization）回答“这个身份能做什么”。两者共同解决匿名访问、用户身份、角色/权限、资源归属和数据范围问题。

本章讲的是安全边界与排错方法，不建议手写 JWT、密码哈希或加密协议。项目基线是 Java 11、Spring Boot 2.7；实际 Spring Security 版本与应用使用的 Bearer、Session 或网关 Token 机制，要从依赖和配置确认。

## 知识点地图：安全要回答五个问题

| 知识点 | 要回答的问题 | 主要证据 | 常见错误 |
|---|---|---|---|
| 身份认证 | 这个请求是谁发出的？凭证是否有效？ | Token/Cookie、签名、过期时间、issuer | 只相信 body 的 `userId` |
| 请求授权 | 这个身份能否调用这个功能？ | URL 规则、方法注解、权限码 | 只隐藏前端按钮 |
| 资源授权 | 能否操作这条具体记录/文件？ | 资源归属、租户、组织条件 | 只校验模块权限 |
| 数据范围 | 这次查询能看到哪些行？ | SQL 条件、租户/机构过滤 | 查出全部后在内存过滤 |
| 凭证与浏览器边界 | 凭证如何存、传、刷新和撤销？ | Cookie 属性、HTTPS、CORS/CSRF、刷新日志 | 无限刷新、Token 放 URL |

阅读顺序建议是：**认证恢复身份 → 请求级规则 → 方法级规则 → 资源/数据范围 → 数据库提交**。前面的规则通过，只代表“可以继续”，不代表资源本身已经授权。

## 重难点一：SecurityContext 是请求上下文，不是全局用户变量

过滤器验证凭证后通常把 `Authentication` 放进当前线程关联的 `SecurityContext`，Controller/Service 可以读取当前身份。请求结束后上下文应被清理；不能把当前用户保存到单例 Bean 字段或普通 `static` 变量。

~~~java
// Spring Security 集成示意：只在当前调用链读取身份，不能写到全局字段。
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication == null || !authentication.isAuthenticated()) {
    throw new AccessDeniedException("当前请求没有已验证身份");
}
String userId = authentication.getName();
// userId 只作为本次方法的局部值，后续查询仍要加资源/租户条件。
~~~

异步线程是难点：把任务交给线程池后，不能默认它自动拥有原请求的身份；即使使用 `DelegatingSecurityContextExecutor` 传递上下文，也要确认任务的生命周期、权限时效和是否会把身份带到不该访问的下游。支付、文件处理等后台任务优先保存明确的业务主体 ID，由任务重新鉴权或使用最小服务身份；不要把 Servlet 请求对象传入后台线程。

## 重难点二：Filter、Interceptor、AOP 和 Service 各自保护什么

| 位置 | 适合做什么 | 不适合做什么 |
|---|---|---|
| Security Filter | 解析凭证、建立身份、粗粒度 URL 拦截 | 查询每个文件归属、执行长时间业务 |
| MVC Interceptor | 路由级日志、请求上下文、部分前置校验 | 代替数据库条件授权 |
| AOP/方法权限 | 对 Service 方法统一做功能权限检查 | 隐藏关键资源规则、保证跨表原子性 |
| Service/Repository | 资源归属、数据范围、状态迁移 | 依赖浏览器按钮或前端角色 |
| 数据库条件 | 最后一道行级保护 | 代替完整身份认证 |

越靠近数据的边界越能防止绕过路径。导出、批量接口、异步任务和内部 Feign 调用必须复用授权规则，不能只保护列表页面。

## 重难点三：Session、JWT、OAuth2 不是三个可随便互换的名词

### Session Cookie

服务端保存会话状态，浏览器通过 Cookie 自动携带。适合同源管理后台；多实例需要共享 Session 或粘性策略，并且要处理 CSRF、失效和并发登录。

### JWT Bearer

服务端验证签名和 claims，Token 通常自带身份信息。它减少每次查询 Session 的依赖，但撤销、轮换、泄露处置和 claims 版本变化更难。JWT 不是加密，载荷可读。

### OAuth2/OIDC

OAuth2 解决授权委托，OIDC 在其上提供身份层。浏览器应用优先理解授权码 + PKCE、`state`、redirect URI、scope 和 issuer；不要把“拿到 access token”理解成已经完成应用内资源授权。

选型的重难点是明确谁是认证服务器、谁是资源服务器、Token 发给谁、如何吊销、前端保存在哪里，以及资源服务如何把外部 subject 映射到本地用户/租户。

## 一次受保护请求经过哪里

~~~text
浏览器/前端请求
  -> HTTPS / Gateway
  -> SecurityFilterChain
  -> 从 Authorization 或 Cookie 提取凭证
  -> 验证签名、有效期、issuer、audience 等
  -> 建立 SecurityContext（当前身份）
  -> URL/方法权限判断
  -> Controller 校验资源归属和数据范围
  -> Service 执行实际操作
  -> Security EntryPoint / AccessDeniedHandler 或 MVC 异常处理器生成响应
~~~

过滤器只完成“从请求恢复身份”和通用拦截；它不能替代订单金额核对、文件归属检查或数据库条件更新。Controller 中拿到 `Authentication` 也不表示用户自动拥有资源权限。

## 401/403：Security 过滤器与 MVC 异常处理边界

前端看到的状态码相同，异常可能来自不同层。`@RestControllerAdvice` 主要处理进入 Spring MVC 后、由 `HandlerExceptionResolver` 处理的异常；它不能保证捕获尚未进入 `DispatcherServlet` 的过滤器异常。

| 失败位置/原因 | 常见处理组件 | 典型状态 | 排查证据 |
|---|---|---:|---|
| 缺少、过期或无效凭证，在 Security 链拒绝 | `AuthenticationEntryPoint` | 401 | Network 的 WWW-Authenticate/响应体、认证过滤器日志、网关日志 |
| 已认证用户被 URL/方法授权规则拒绝 | `AccessDeniedHandler`；由 `ExceptionTranslationFilter` 协调处理 | 403 | 当前身份、命中规则、权限表达式和处理器响应 |
| Controller/Service 抛出普通业务异常 | `@RestControllerAdvice` / `HandlerExceptionResolver` | 由业务映射决定，例如 400/404/409 | MVC 异常映射、traceId、业务异常码 |
| 网关在请求到达服务前拒绝 | Gateway 自身的错误处理 | 可能是 401/403/其他状态 | 网关路由、过滤器和响应头；服务日志可能没有对应请求 |

在 Spring Security 5.7 中，`ExceptionTranslationFilter` 位于安全链内，会把它下游抛出的认证/授权异常交给对应处理器。方法级权限异常若先被 MVC 的异常解析器处理，可能走 `@RestControllerAdvice`；若继续向外传播，也可能回到安全过滤器处理。因此应检查实际过滤器顺序、异常解析器和响应来源，不能只凭异常类名推断。

下面展示 Boot 2.7 / Spring Security 5.7 的处理器配置方式。它只统一过滤器链的 JSON 错误外形；URL 规则、登录方式和资源授权仍须按应用定义。

~~~java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 仅示范过滤器链的认证失败和授权失败响应格式。 */
@Configuration
public class SecurityErrorConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper)
            throws Exception {
        // 未认证请求进入这里：返回 401，告诉浏览器身份凭证无效或缺失。
        AuthenticationEntryPoint entryPoint = (request, response, exception) ->
                writeJsonError(response, objectMapper, 401, "UNAUTHENTICATED", "请先登录");

        // 已认证但权限不足的请求进入这里：返回 403，不把它误报为登录失效。
        AccessDeniedHandler deniedHandler = (request, response, exception) ->
                writeJsonError(response, objectMapper, 403, "FORBIDDEN", "没有执行该操作的权限");

        // 示例只要求登录；真实项目还应在此配置公开路径和细粒度授权规则。
        http.authorizeRequests()
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler);

        // 返回由 Spring 管理的安全过滤器链。
        return http.build();
    }

    private static void writeJsonError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message) throws IOException {
        // 明确设置 HTTP 状态和 JSON 类型；不要把异常堆栈或 Token 放进响应。
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // 统一的错误字段让前端拦截器可以稳定读取 code 和 message。
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", code,
                "message", message));
    }
}
~~~

调试 401/403 时按请求 ID 搜索网关和服务日志，再确认请求是否进入 Controller；同时查看 Network 中的 HTTP 状态、响应体、`WWW-Authenticate`、CORS 响应头和是否发送了预检请求。统一响应包装可能改变 body 的外形，但不应把 401、403 和业务失败混成同一种 HTTP 200。

## 认证、授权、会话和 Token 的关系

| 方式 | 服务端保存什么 | 浏览器如何带凭证 | 适用场景 | 主要风险/成本 |
|---|---|---|---|---|
| Session Cookie | 服务端会话或共享 Session | 自动携带 Cookie | 传统 Web、同源后台 | 跨站请求、会话存储和多实例共享 |
| JWT Bearer | 服务端通常不保存每次请求状态 | `Authorization: Bearer ...` | API、服务间调用 | 吊销困难、Token 泄露、签名/claims 配置 |
| Opaque Token/Introspection | 授权服务器保存状态 | Bearer Token | 需要集中吊销 | 每次 introspection 的网络依赖 |
| OAuth2/OIDC | 授权服务器、客户端和资源服务器 | 授权码、access token、ID token | 第三方登录、统一身份 | redirect、state/PKCE、scope 和多方配置 |

JWT 是一种签名数据格式，不等于“加密”。不要把密码或秘密放入 claims；Base64URL 可被读取。签名验证必须使用受信任的 issuer/密钥和算法配置，不能接受 Token 自己声明的算法。

## 登录流程：前端能做什么，不能证明什么

1. Vue 表单通过 HTTPS 提交用户名/密码或跳转 OIDC 授权端点。
2. 后端验证密码（使用成熟 password encoder）或授权服务器结果，建立 Session 或签发短期 access token。
3. 前端请求层按约定附加 Cookie 或 Bearer header；不要把 Token 拼到 URL。
4. 服务端每次受保护请求重新验证凭证和权限，不能相信前端保存的 `userId`、角色或按钮状态。
5. 401 通常表示没有有效身份（缺失、过期、签名错误）；403 通常表示身份已识别但无权操作。实际项目可能有统一响应包装，最终以 Network、Gateway 和服务日志为准。

前端路由守卫和按钮隐藏只解决体验；用户可以绕过 UI 直接构造 HTTP 请求。权限安全边界始终在后端。

## Spring Security 过滤器链示意

下面是 Spring Boot 2.7/Spring Security 5.7 风格的**集成示意**。它没有包含真实用户、密钥、登录页和生产 CORS 白名单，不能直接当作上线配置；Boot 3 的包名和配置写法可能不同。

~~~java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 只演示授权边界；认证方式需接入项目已有 Session/JWT/OIDC 配置。 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            // Cookie 会话应用保留 CSRF 防护；Bearer-only 无状态 API 需按凭证机制单独评估。
            .csrf().and()
            .authorizeRequests()
                // 只放行明确的健康/登录入口，不要用 /** permitAll() 省事。
                .antMatchers("/actuator/health", "/api/auth/login").permitAll()
                .antMatchers(HttpMethod.GET, "/api/reports/**").hasAuthority("REPORT_VIEW")
                .anyRequest().authenticated();
        return http.build();
    }
}
~~~

`SecurityFilterChain` 只表达请求级规则；方法级 `@PreAuthorize` 可以保护服务方法，但仍需在服务层核对资源归属。多个过滤器链时，`securityMatcher` 的匹配顺序和默认链要明确测试。不要在过滤器里执行长时间数据库查询、文件上传或远程 HTTP；这些会阻塞请求线程或事件循环。

### 为什么“规则写了”仍可能没有效果

1. 请求没有进入预期的过滤器链，例如多个链的匹配顺序不对。
2. 静态资源、Gateway 或另一服务先返回了响应，当前应用没有机会执行规则。
3. 方法注解没有开启对应的 method security，或调用没有经过 Spring 代理。
4. 认证成功了，但 `Authentication` 中没有你检查的 authority/scope。
5. Controller 通过了功能权限，Service 却没有检查资源归属。

排错要同时保存：实际请求 URL/方法、命中的链、认证结果（只记 subject，不记完整 Token）、权限表达式、最终 401/403 来源。不要只看前端菜单。

## 方法权限与资源权限不是一回事

功能权限判断“能不能执行查看/编辑”；资源权限判断“能不能操作这个具体报表/文件”；数据范围判断“能看哪些机构、项目或记录”。

~~~java
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** 真实项目还需要在方法内部查询当前用户可访问的资源并执行数据范围条件。 */
@Service
public class ReportService {
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ReportView getReport(String reportId, CurrentUser user) {
        // 不能只依据前端传来的 reportId；必须检查当前用户与资源的关系。
        Report report = loadReport(reportId);
        if (!user.canReadOrganization(report.organizationId())) {
            throw new ForbiddenException("无权访问该报表");
        }
        return toView(report);
    }

    // 这些方法代表真实仓储/映射层，示例只展示安全边界。
    private Report loadReport(String id) { throw new UnsupportedOperationException(); }
    private ReportView toView(Report report) { throw new UnsupportedOperationException(); }
}
~~~

如果 SQL 查询一次返回所有机构数据，再在 Java 中过滤，不仅浪费资源还可能在日志、分页、导出路径泄露数据。优先把数据范围条件带入查询，并在更新/删除时使用 `WHERE id = ? AND organization_id IN (...)` 或等价的授权条件。

**重难点：授权检查与写入之间的竞态。** 先单独查询“用户能否修改”，再用另一个 SQL 更新，中间资源归属可能已变化。更新语句应带上资源归属/版本条件，并检查影响行数；涉及多表状态变化时使用事务。权限检查不是一把永久锁。

当前项目的 `DashboardPageController` 可沿 `@ApiPermission`、权限码定义和注解处理实现追踪：

1. 打开 `ApiPermission` 的定义和 VIEW/ADD/UPDATE 等权限码。
2. 搜索处理该注解的 AOP、拦截器或 MVC 基础设施。
3. 找到身份从哪里加载，确认匿名请求的默认状态。
4. 查 Service/查询层是否另有资源和数据范围限制。
5. 用无权账号请求接口，确认后端实际返回 403，而不是仅仅前端隐藏按钮。

## 密码、Token 和刷新

- 密码只能经过成熟的自适应 password encoder（如 BCrypt/Argon2 的项目支持版本）保存；不使用 MD5/SHA-256 直接哈希，也不保存明文。
- access token 设置短有效期和最小 scope；refresh token 只在明确的刷新端点使用，并设计轮换、撤销和重放检测。
- Bearer Token 通过 HTTPS 的 `Authorization` header 发送，脱敏日志、异常和埋点；不要放 query string。
- HttpOnly、Secure、SameSite Cookie 可降低脚本读取或跨站携带风险，但要结合 CSRF 策略；Cookie 不是“自动安全”。
- 登出应定义服务端语义：Session 失效、Token 撤销/黑名单、refresh token 轮换，不能只清空 Vue 的 localStorage。

前端 Axios 示例（只展示请求边界，不生成或解析 JWT）：

~~~ts
// 认证状态由项目统一 store 管理；这里不把 access token 打印到日志。
const api = axios.create({ baseURL: '/api', timeout: 10_000 })

api.interceptors.request.use((config) => {
  const token = authStore.accessToken
  if (token) {
    // 只放在 HTTPS 请求头；具体刷新并发锁由统一认证模块实现。
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(undefined, async (error) => {
  if (error.response?.status === 401 && !error.config.__retried) {
    error.config.__retried = true
    // refresh 也必须防止并发风暴，并校验响应来源；失败后清理状态并跳登录。
    await authStore.refreshOnce()
    return api(error.config)
  }
  if (error.response?.status === 403) showMessage('当前账号没有该操作权限')
  throw error
})
~~~

刷新失败不能无限重试；多个请求同时 401 时应共享一个 refresh Promise，避免同时轮换同一 refresh token。刷新响应和错误体也不能原样写入日志。前端显示 401/403 的文案不应泄露“用户是否存在”等敏感认证信息。

~~~ts
// 一个最小的并发刷新门闩：同一时刻只允许一个 refresh 请求。
let refreshPromise: Promise<string> | undefined

function refreshOnce(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = requestNewAccessToken()
      .finally(() => { refreshPromise = undefined })
  }
  return refreshPromise
}

// 401 的原请求只重试一次；刷新失败就清理会话并跳转登录，避免死循环。
async function retryAfter401(config: RequestConfig) {
  if (config.__retried) throw new Error('认证已失效')
  config.__retried = true
  const token = await refreshOnce()
  return api({ ...config, headers: { ...config.headers, Authorization: `Bearer ${token}` } })
}
~~~

这个门闩只解决浏览器内同一页面的并发请求；多标签页、多个设备和 refresh token 轮换仍需要服务端的版本/重放策略。`finally` 必须清理 promise，否则一次失败会让后续请求永久复用失败结果。

### Cookie Session 与 Bearer：前后端应选择匹配的一种凭证链

| 项目 | HttpOnly Session Cookie | Bearer Token |
|---|---|---|
| 凭证如何发送 | 浏览器按 Cookie 规则自动携带 | 前端请求层显式写入 `Authorization` |
| JavaScript 能否读取主要凭证 | `HttpOnly` Cookie 不可读取 | 若 Token 存在 JS 可读存储，XSS 可窃取；应谨慎缩短寿命并限制权限 |
| CSRF | Cookie 自动附带，修改状态的请求需要 CSRF 防护 | 自定义 Authorization 头通常不由普通跨站表单自动附带；仍需按刷新 Cookie、浏览器和部署设计评估 |
| 跨域配置 | `withCredentials`、精确 CORS 来源、`allowCredentials=true`、Cookie 的 `SameSite/Secure/Domain` 必须配套 | 预检要允许 `Authorization` 和实际 Content-Type；也要配置精确 CORS 来源 |
| 适合情况 | 浏览器 Web 应用、服务端 Session 管理 | API 客户端、服务间调用或明确采用 Token 的架构 |

同源 Cookie 请求通常会按浏览器策略发送 Cookie；调用跨源 API 时，Axios 需要 `withCredentials: true`。状态修改请求还必须携带由服务端签发/验证的 CSRF token。一个简化的前端边界如下：

~~~ts
// 该客户端使用 Session Cookie；withCredentials 主要用于跨源凭证请求。
const cookieApi = axios.create({ baseURL: 'https://api.example.test', withCredentials: true })

// CSRF token 从受信任后端端点取得；它与 HttpOnly 的登录 Cookie 是不同数据。
let csrfToken = ''

async function loadCsrfToken() {
  const response = await cookieApi.get('/csrf')
  csrfToken = response.data.token
}

cookieApi.interceptors.request.use((config) => {
  // 只给会改变服务端状态的方法附带 CSRF token；服务端仍必须验证它。
  const method = (config.method ?? 'get').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) {
    config.headers['X-CSRF-TOKEN'] = csrfToken
  }
  return config
})
~~~

服务端跨源 Cookie 响应必须返回明确的 `Access-Control-Allow-Origin`，其值与允许的前端 Origin 完全匹配，并返回 `Access-Control-Allow-Credentials: true`；不能将允许来源设为 `*`。预检响应还要允许实际使用的方法和 `X-CSRF-TOKEN` 等请求头。跨站 Cookie 通常要求 `SameSite=None; Secure`，只能在 HTTPS 下使用；同站部署则应按实际域名和浏览器策略设置。不要为省事关闭 CSRF，也不要让跨域凭证配置接受任意网站。

如果架构选择 Bearer，就使用前面的 `Authorization` 拦截器并按需要维护短期 access token；不要再把 Session Cookie 当成第二套隐式登录凭证。若 refresh token 放在 Cookie 中，刷新端点也要单独设计 CSRF、防重放和轮换策略。联调时在 Network 确认 Cookie 是否发送、预检是否成功、实际请求是否带 CSRF/Authorization，以及响应 CORS 头是否与页面 Origin 一致。

## CORS、CSRF、XSS、TLS：不要混为一谈

| 机制 | 解决的问题 | 不负责什么 |
|---|---|---|
| TLS/HTTPS | 传输中机密性和完整性 | 不能替代身份与资源授权 |
| CORS | 浏览器脚本是否可跨源读取响应 | 不是服务端认证；curl 不受浏览器 CORS 限制 |
| CSRF | Cookie 自动携带导致的跨站状态修改 | 不等于 XSS 防护；Bearer header 也要按实际落地评估 |
| CSP/XSS 防护 | 限制脚本注入和页面数据读取 | 不能修复后端越权查询 |

CORS 必须使用明确的允许来源、方法、请求头和凭证策略；不能在 `allowCredentials=true` 时用任意 `*` 来源。预检请求要允许实际使用的 `Authorization`、`Content-Type` 等头。CSRF token 应由受信任服务端生成/校验，不能把任意前端字段当作防护。

## 常见漏洞与验证方法

- 只根据 body 中的 `userId` 判断身份；应从已验证上下文取得当前用户。
- 只隐藏按钮；直接调用 API 仍能成功。
- 只检查模块权限，不检查 `reportId/fileId/orderId` 归属。
- 列表查询没有数据范围，导出接口却绕过列表权限。
- 过滤器验证 Token 后，Service 又接受调用方传来的角色覆盖当前身份。
- 401 自动刷新无限循环，或把 refresh token 放进 URL/日志。
- CORS 允许任意来源携带 Cookie，或为方便关闭 CSRF。
- 将完整 JWT、Cookie、支付签名 URL 写入日志和截图。

排错时按顺序记录：浏览器实际请求（方法、URL、是否带凭证、预检）、Gateway 是否改写/拒绝、过滤器是否建立身份、Controller 方法规则、Service 资源授权、最终 HTTP 响应。不要只看前端路由菜单。

## 当前项目的 `@ApiPermission` 阅读路径

在当前 checkout 中，`DashboardPageController` 的方法使用 `@ApiPermission(permissions = {Permission.Dashboard.VIEW})` 等注解，`ApiPermissionAspect` 通过 `@Before("@annotation(apiPermission) || @within(apiPermission)")` 进入权限检查。阅读时要注意几个边界：

1. 切面是否被 Spring 扫描并由 AOP 代理创建；直接 `new Controller` 不会触发它。
2. 类级与方法级注解的覆盖规则是什么，空权限数组如何处理。
3. `ApiPermissionClient` 从哪里取得当前用户，远程权限服务失败时是拒绝还是放行。
4. `GlobalException` 最终被哪个异常处理器转换成 HTTP 状态和响应体。
5. `VIEW/ADD/UPDATE/DELETE` 只表达功能权限时，Service/SQL 是否还核对具体页面、文件或租户归属。

项目里“没有权限接口实现时跳过校验”的兼容分支尤其要结合部署配置理解：它可能是本地模块的设计，也可能形成误放行风险；不能只看到注解就断言线上一定执行了权限校验。

## 分级练习与官方资料

### 基础

1. 找一个受保护 Controller，画出“Token → SecurityContext → 方法权限 → 数据范围”的链路。
2. 用同一账号访问自己的和别人的 `reportId`，比较列表、详情、导出、更新四条路径。

### 进阶

3. 分别构造缺 Token、过期 Token、有效 Token 无权限三种请求，确认 401/403 的来源。
4. 检查 CORS 允许来源、Cookie 属性、CSRF token 和前端 refresh 并发行为。

### 排错

5. 让一个下游服务不可用，确认认证失败、授权失败、业务超时在前端展示上没有混成“无权限”。
6. 追踪 `DashboardPageController` 的 `@ApiPermission` 到 `ApiPermissionAspect` 和异常处理器，记录一次允许与一次拒绝的证据。
7. 设计一个导出接口，说明为什么列表权限通过后仍需对导出资源再次做归属和数据范围检查。

官方资料：[Spring Security 5.7](https://docs.spring.io/spring-security/site/docs/5.7.x/reference/html5/)、[Spring Boot 2.7 Security](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/web.html#web.security)、[OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)、[OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)。

下一步：[Spring MVC 与 Controller](./mvc.md)、[AOP 与代理](./aop.md) 或 [支付宝与微信支付实战](../../examples/online-payment.md)。
