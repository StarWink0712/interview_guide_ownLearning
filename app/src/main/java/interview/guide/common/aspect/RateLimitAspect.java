/**
 * 限流 AOP 切面
 *
 * 拦截带 @RateLimit 或 @RateLimit.Container 注解的 Controller 方法，逐条执行独立的限流规则。
 * 使用 Redis Lua 脚本（滑动时间窗口）实现限流，支持 NOSCRIPT 自动重载。
 * 任一规则不通过即触发限流，优先调用降级方法，无降级则抛出 RateLimitExceededException。
 * Redis Key 设计使用 Hash Tag 分组：ratelimit:{ClassName:MethodName}:dimension。
 *
 * 联动关系：
 * - 依赖 @RateLimit 注解获取限流规则配置
 * - 依赖 RedissonClient 执行 Lua 限流脚本
 * - 依赖 RateLimitExceededException 作为限流拒绝异常
 * - 拦截所有标注 @RateLimit 的 Controller 方法
 */
package interview.guide.common.aspect;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** Redis 客户端，用于执行 Lua 限流脚本 */
    private final RedissonClient redissonClient;

    /** 加载自 resources/scripts/rate_limit_single.lua 的限流 Lua 脚本内容 */
    private static final String LUA_SCRIPT;
    /** Lua 脚本加载到 Redis 后返回的 SHA1 摘要，用于后续 evalsha 调用 */
    private String luaScriptSha;
    /** Redisson RScript 接口，用于执行 Lua 脚本 */
    private RScript rScript;

    //把 rate_limit_single.lua 脚本文件从 classpath 读取到内存里的字符串，存到静态常量 LUA_SCRIPT 中。
    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载限流 Lua 脚本失败", e);
        }
    }

    /**
     * Spring 容器初始化后加载 Lua 脚本到 Redis，获取 SHA1 摘要
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        loadScript();
    }

    /**
     * 加载/重新加载 Lua 脚本到 Redis，更新 SHA1 摘要
     */
    private void loadScript() {
        this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 环绕通知：拦截带 @RateLimit 或 @RateLimit.Container 注解的方法。
     * 遍历所有限流规则，逐条执行 Lua 脚本，任一规则不通过即拒绝请求。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从切入点提取方法签名（类名 + 方法名），作为 Redis Key 分组依据</li>
     *   <li>获取方法上所有 @RateLimit 注解（可重复注解可能有多条规则）</li>
     *   <li>为每条规则生成唯一的请求 ID（requestId），用于 Lua 脚本去重</li>
     *   <li>遍历每条规则：生成 Redis Key → 执行 Lua 脚本 → 结果判断</li>
     *   <li>任一规则返回 null 或 0（配额耗尽）即触发限流拒绝机制</li>
     *   <li>所有规则通过后执行原方法</li>
     * </ol>
     *
     * @param joinPoint 切入点，包含被拦截方法的签名和参数
     * @return 原方法返回值或降级方法返回值
     * @throws Throwable 限流拒绝（RateLimitExceededException）或方法执行异常
     */
    @Around("@annotation(interview.guide.common.annotation.RateLimit) || "
            + "@annotation(interview.guide.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 提取被拦截方法的类名和方法名，用于构造 Redis Key
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        // 获取方法上所有 @RateLimit 注解（支持 @Repeatable 多维度限流）
        RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
        // 当前时间戳（毫秒），作为滑动窗口的当前时间
        long nowMs = System.currentTimeMillis();
        // 每次请求生成唯一的请求 ID，Lua 脚本用它去重，防止同一请求被多次计数
        String requestId = UUID.randomUUID().toString();

        // 逐条检查每个限流维度规则，所有规则必须全部通过
        for (RateLimit rule : rules) {
            // 将注解中的时间单位转换为毫秒，计算滑动窗口大小
            long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
            // 生成 Redis 限流 Key（格式：ratelimit:{ClassName:MethodName}:dimension:具体值）
            String key = generateKey(className, methodName, rule.dimension());

            // 执行 Redis Lua 脚本，返回当前窗口剩余配额
            Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

            // null 或 0 表示配额耗尽，触发限流处理
            if (result == null || result == 0) {
                return handleRateLimitExceeded(joinPoint, rule, key);
            }
        }

        // 所有规则通过，执行原方法
        return joinPoint.proceed();
    }

    /**
     * 执行 Redis Lua 限流脚本（滑动时间窗口算法），返回剩余配额。
     *
     * <p>Lua 脚本参数说明（传给 rate_limit_single.lua）：
     * <ul>
     *   <li>KEYS[1]：限流 Key（如 ratelimit:{ClassName:methodName}:global）</li>
     *   <li>ARGV[1]：当前时间戳（毫秒），用于窗口滑动计算</li>
     *   <li>ARGV[2]：每次请求消耗的令牌数（固定为 1）</li>
     *   <li>ARGV[3]：滑动窗口大小（毫秒）</li>
     *   <li>ARGV[4]：窗口内允许的最大令牌数（count）</li>
     *   <li>ARGV[5]：请求唯一 ID，用于去重，防止网络重试导致多次计数</li>
     * </ul>
     *
     * <p>NOSCRIPT 容错机制：
     * Redis 重启或脚本被 FLUSH 后，缓存的 SHA1 摘要会失效。
     * 此时 evalsha 抛出 NOSCRIPT 异常，自动调用 scriptLoad 重新加载脚本并重试一次。
     *
     * @param key 限流 Redis Key
     * @param nowMs 当前时间戳（毫秒）
     * @param requestId 请求唯一 ID
     * @param intervalMs 滑动窗口大小（毫秒）
     * @param count 窗口内允许的最大请求数
     * @return 剩余配额（大于 0 表示通过），返回 null 或 0 表示配额耗尽
     */
    private Long executeRateLimitScript(String key, long nowMs, String requestId, long intervalMs, double count) {
        // 构建 Lua 脚本参数列表
        List<Object> keysList = Collections.singletonList(key);
        Object[] args = {
                String.valueOf(nowMs),       // ARGV[1]: 当前时间戳
                String.valueOf(1),           // ARGV[2]: 每次消耗 1 个令牌
                String.valueOf(intervalMs),  // ARGV[3]: 窗口大小
                String.valueOf(count),       // ARGV[4]: 最大令牌数
                requestId                    // ARGV[5]: 请求去重 ID
        };

        try {
            // 使用 SHA1 摘要执行缓存脚本（evalsha 比 eval 更高效，减少网络传输）
            Object resultObj = rScript.evalSha(
                    RScript.Mode.READ_WRITE,      // 读写模式，因为脚本会修改 Redis 数据
                    luaScriptSha,                 // 脚本 SHA1 摘要
                    RScript.ReturnType.VALUE,     // 返回值类型为单值
                    keysList,                     // KEYS 列表
                    args                          // ARGV 参数列表
            );
            return convertToLong(resultObj);
        } catch (org.redisson.client.RedisException e) {
            // Redis 重启后脚本缓存丢失（NOSCRIPT 错误），自动重新加载脚本并重试一次
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                loadScript();  // 重新 scriptLoad 获取新的 SHA1
                Object resultObj = rScript.evalSha(
                        RScript.Mode.READ_WRITE,
                        luaScriptSha,
                        RScript.ReturnType.VALUE,
                        keysList,
                        args
                );
                return convertToLong(resultObj);
            }
            // 非 NOSCRIPT 异常直接向上抛出
            throw e;
        }
    }

    /**
     * 将时间单位转换为毫秒
     */
    private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> interval;
            case SECONDS -> interval * 1000;
            case MINUTES -> interval * 60 * 1000;
            case HOURS -> interval * 3600 * 1000;
            case DAYS -> interval * 86400 * 1000;
        };
    }

    /**
     * 将 Lua 脚本返回值（Number/String）转换为 Long
     */
    private Long convertToLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("无法将字符串转换为Long: {}", obj);
                return null;
            }
        }
        log.warn("不支持的对象类型转换为Long: {}", obj != null ? obj.getClass().getName() : "null");
        return null;
    }

    /**
     * 生成限流 Redis Key，格式：ratelimit:{ClassName:MethodName}:dimension[:具体值]
     * 使用 Hash Tag {ClassName:MethodName} 确保同一方法的所有限流 Key 落在同一个 Redis slot
     */
    private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
        String hashTag = "{" + className + ":" + methodName + "}";
        String keyPrefix = "ratelimit:" + hashTag;

        return switch (dimension) {
            case GLOBAL -> keyPrefix + ":global";
            case IP -> keyPrefix + ":ip:" + getClientIp();
            case USER -> keyPrefix + ":user:" + getCurrentUserId();
        };
    }

    /**
     * 限流被触发时的处理：优先调用降级方法，无降级则抛出 RateLimitExceededException
     */
    private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String key)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();

        if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
            try {
                Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            methodName,
                            rateLimit.fallback());
                    if (fallbackMethod.getParameterCount() > 0) {
                        return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                    } else {
                        return fallbackMethod.invoke(joinPoint.getTarget());
                    }
                }
            } catch (Exception e) {
                log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
            }
        }

        log.debug("限流触发，拒绝请求: key={}, count={} per {} {}",
                key, rateLimit.count(), rateLimit.interval(), rateLimit.timeUnit());
        throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    }

    /**
     * 查找降级方法：先尝试参数列表一致的方法，再尝试无参方法
     *
     * @return 找到的降级方法，未找到返回 null
     */
    private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] parameterTypes = signature.getParameterTypes();

        try {
            Method method = targetClass.getDeclaredMethod(fallbackName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            try {
                Method method = targetClass.getDeclaredMethod(fallbackName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                log.warn("未找到降级方法: {}.{} (需无参或参数列表一致)",
                        targetClass.getSimpleName(), fallbackName);
                return null;
            }
        }
    }

    /**
     * 获取客户端真实 IP 地址。
     *
     * <p>按以下优先级依次尝试代理头部（逐级穿透抓取）：
     * <ol>
     *   <li>X-Forwarded-For：标准代理头部，取逗号分隔的第一个 IP（最原始客户端 IP）</li>
     *   <li>X-Real-IP：Nginx 等反向代理设置的单值 IP</li>
     *   <li>Proxy-Client-IP：Apache httpd 代理头部</li>
     *   <li>WL-Proxy-Client-IP：WebLogic 代理头部</li>
     *   <li>request.getRemoteAddr()：直接连接的客户端地址（无代理时）</li>
     * </ol>
     *
     * <p>安全考量：X-Forwarded-For 可被伪造，但在可控网络环境中足够可靠。
     * 生产环境应由反向代理（Nginx）在最外层设置并覆盖所有上游头部。
     *
     * @return 客户端 IP 字符串，获取失败返回 "unknown"
     */
    private String getClientIp() {
        // 从 Spring 请求上下文中获取当前 HTTP 请求属性
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";  // 非 Web 环境（如单元测试）返回默认值
        }

        HttpServletRequest request = attributes.getRequest();
        
        // 第 1 层：X-Forwarded-For，格式为 "client, proxy1, proxy2"，取第一个（最原始客户端）
        String ip = request.getHeader("X-Forwarded-For");
        // 第 2 层：X-Real-IP（Nginx 等设置的单值头部）
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        // 第 3 层：Proxy-Client-IP（Apache httpd）
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        // 第 4 层：WL-Proxy-Client-IP（WebLogic）
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        // 第 5 层：最终回退到直接连接 IP
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // X-Forwarded-For 可能包含多个 IP（逗号分隔），取第一个（最原始客户端）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "unknown";
    }

    /**
     * 获取当前用户 ID，优先从 request attribute 获取，其次从 X-User-Id 请求头获取，
     * 都获取不到返回 "anonymous"
     */
    private String getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous";
        }

        HttpServletRequest request = attributes.getRequest();

        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return userId.toString();
        }

        userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return userId.toString();
        }

        return "anonymous";
    }
}
