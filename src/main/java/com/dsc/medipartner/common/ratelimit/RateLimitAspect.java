package com.dsc.medipartner.common.ratelimit;

import com.dsc.medipartner.common.cache.CacheKeys;
import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.security.LoginUser;
import com.dsc.medipartner.common.util.PhoneUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;

/**
 * 限流切面（阶段 5）。
 *
 * <p>实现为<b>固定窗口计数</b>（{@code INCR + 首次设 TTL}），而非滑动窗口：
 * <ul>
 *   <li>本项目的限流目标很朴素——挡住短信轰炸、充值刷单、验证码爆破，
 *       固定窗口的「窗口边界双倍放行」特性对这类场景影响可忽略。</li>
 *   <li>滑动窗口需要 ZSET 或 Lua 脚本，在 Redis 降级为进程内实现时无法等价表达，
 *       会导致「有 Redis 与没 Redis 行为不同」。</li>
 * </ul>
 *
 * <p><b>降级语义</b>：{@code CacheService.increment} 在后端不可用时返回 1
 * （即视为首次请求），因此限流会<b>整体失效但不报错</b>。
 * 这是刻意取舍：限流是保护性能力，不应因缓存故障把全部业务接口打挂。
 *
 * <p>{@code @Order} 设成较低优先级，确保它在事务与其它切面之前执行——
 * 被限流的请求应当尽早短路，不要先开事务再抛异常。
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class RateLimitAspect {

    private final CacheService cacheService;

    public RateLimitAspect(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String subject = resolveSubject(joinPoint, rateLimit);
        if (subject == null) {
            // 拿不到限流主体（如匿名且取不到 IP）时放行：
            // 宁可漏限也不能因取不到标识而拒绝正常请求
            return joinPoint.proceed();
        }

        String name = StringUtils.hasText(rateLimit.name())
                ? rateLimit.name()
                : defaultName(joinPoint);
        String key = CacheKeys.rate(name, subject);

        long count = cacheService.increment(key, Duration.ofSeconds(rateLimit.windowSeconds()));
        if (count > rateLimit.limit()) {
            log.warn("[ratelimit] 触发限流 name={} subject={} count={}/{} window={}s",
                    name, subject, count, rateLimit.limit(), rateLimit.windowSeconds());
            throw new BizException(ErrorCode.REPEAT_REQUEST);
        }
        return joinPoint.proceed();
    }

    /** 默认限流名：{@code 简单类名.方法名}，保证不同接口配额互不干扰 */
    private String defaultName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }

    /**
     * 解析限流主体标识。
     *
     * <p>取不到时返回 {@code null}，由调用方放行。
     */
    private String resolveSubject(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        return switch (rateLimit.dimension()) {
            case USER -> resolveUserOrIp();
            case IP -> clientIp();
            case PHONE -> resolvePhone(joinPoint);
        };
    }

    /** 已登录用 userId，未登录回落 IP（否则匿名接口所有人共享一个计数器） */
    private String resolveUserOrIp() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return "u" + loginUser.getUserId();
        }
        return clientIp();
    }

    /**
     * 从方法参数里找手机号。
     *
     * <p>按「参数名含 phone」或「值是合法手机号」匹配，而不是绑定到某个具体 DTO 类型——
     * 后者会让切面依赖具体业务类，将来新增带手机号的请求就得改切面。
     */
    private String resolvePhone(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length && i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            // 直接是手机号字符串
            if (arg instanceof String text) {
                String normalized = PhoneUtil.normalize(text);
                if (PhoneUtil.isValid(normalized)) {
                    return normalized;
                }
                continue;
            }
            // DTO：反射读 phone 字段（避免切面依赖具体 DTO 类型）
            String phone = readPhoneProperty(arg);
            if (phone != null) {
                return phone;
            }
        }
        // 取不到手机号时回落 IP：仍然提供保护，只是粒度变粗
        return clientIp();
    }

    /** 反射读取 getPhone() 的返回值并归一化；不可用返回 null */
    private String readPhoneProperty(Object bean) {
        try {
            Method getter = bean.getClass().getMethod("getPhone");
            Object value = getter.invoke(bean);
            if (value instanceof String text) {
                String normalized = PhoneUtil.normalize(text);
                return PhoneUtil.isValid(normalized) ? normalized : null;
            }
        } catch (Exception ignored) {
            // 没有 getPhone 或不可访问：属正常情况，静默跳过
        }
        return null;
    }

    /**
     * 客户端真实 IP。
     *
     * <p>优先取 {@code X-Forwarded-For} 的第一段（经代理时真实来源在这里）；
     * 无代理场景回落 {@code remoteAddr}。注意 XFF 可被伪造，
     * 因此它只作限流维度，<b>绝不用于鉴权或审计</b>。
     */
    private String clientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // 可能是逗号分隔的链，取第一个（最接近客户端的那个）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
