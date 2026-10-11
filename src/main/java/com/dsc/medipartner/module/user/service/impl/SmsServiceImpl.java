package com.dsc.medipartner.module.user.service.impl;

import com.dsc.medipartner.common.cache.CacheKeys;
import com.dsc.medipartner.common.cache.CacheService;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.PhoneUtil;
import com.dsc.medipartner.module.user.domain.dto.SmsCodeRequest;
import com.dsc.medipartner.module.user.domain.vo.SmsCodeVO;
import com.dsc.medipartner.module.user.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 短信验证码实现（契约 §2.5 / §2.6）。
 *
 * <p><b>开发期与生产期的区别收敛在一处</b>：{@code medi.sms.mock=true} 时用固定验证码
 * 并在响应中回显；置 false 后走真实网关。真实网关发送属阶段 6（契约 §7.3），
 * 本阶段只保证「校验、过期、限流、一次性」这套业务逻辑是真实可用的——
 * mock 只替换与外部系统的交互，不替换业务规则。
 */
@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    /** 场景枚举，目前仅手机号绑定（契约 §2.5） */
    private static final String SCENE_BIND_PHONE = "BIND_PHONE";

    private final CacheService cacheService;

    /** 开发期开关：true 时不发真实短信，验证码固定 */
    @Value("${medi.sms.mock:true}")
    private boolean mock;

    @Value("${medi.sms.mock-code:123456}")
    private String mockCode;

    @Value("${medi.sms.code-expire-seconds:300}")
    private int codeExpireSeconds;

    @Value("${medi.sms.send-interval-seconds:60}")
    private int sendIntervalSeconds;

    private final SecureRandom random = new SecureRandom();

    public SmsServiceImpl(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public SmsCodeVO sendCode(SmsCodeRequest request) {
        String phone = PhoneUtil.normalize(request == null ? null : request.getPhone());
        if (!PhoneUtil.isValid(phone)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "手机号格式不正确");
        }
        String scene = StringUtils.hasText(request.getScene()) ? request.getScene() : SCENE_BIND_PHONE;
        if (!SCENE_BIND_PHONE.equals(scene)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的验证码场景");
        }

        // 限流：同手机号 60 秒一次（契约 §2.5）。
        // 用 setIfAbsent 而非「读-判断-写」：后者在并发下两个请求可能同时通过检查，
        // 导致同一手机号被连发两条短信（既是资损，也是短信轰炸的入口）。
        //
        // ⚠ 必须显式判 > 0 而不是直接依赖 TTL：TTL 为 0 在缓存层表示「永不过期」，
        // 若配置成 0 会把限流键永久写死，该手机号从此再也收不到验证码。
        // 语义上「间隔 0 秒」应当是「不限流」。
        if (sendIntervalSeconds > 0) {
            boolean allowed = cacheService.setIfAbsent(
                    CacheKeys.smsInterval(phone), "1", Duration.ofSeconds(sendIntervalSeconds));
            if (!allowed) {
                throw new BizException(ErrorCode.REPEAT_REQUEST, "发送过于频繁，请稍后再试");
            }
        }

        String code = mock ? mockCode : generateCode();
        cacheService.set(CacheKeys.sms(scene, phone), code, Duration.ofSeconds(codeExpireSeconds));

        if (mock) {
            // 开发期打日志便于本地联调；生产开关为 false，此分支不会执行
            log.info("[sms] 开发期验证码 phone={} scene={} code={}", phone, scene, code);
        } else {
            // 阶段 6 接真实短信网关；未接入时只记日志，不阻塞主流程
            log.warn("[sms] 真实短信通道未接入（阶段 6），本次未实际下发 phone={} scene={}", phone, scene);
        }

        SmsCodeVO vo = new SmsCodeVO();
        vo.setExpiresIn(codeExpireSeconds);
        vo.setSendAt(LocalDateTime.now());
        // 仅开发期回显；生产返回 null，避免把验证码直接交给调用方
        vo.setDebugCode(mock ? code : null);
        return vo;
    }

    @Override
    public void verifyCode(String scene, String phone, String code) {
        String normalizedPhone = PhoneUtil.normalize(phone);
        if (!StringUtils.hasText(code)) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        String key = CacheKeys.sms(scene, normalizedPhone);
        String expected = cacheService.get(key);
        if (expected == null || !expected.equals(code.trim())) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        // 一次性：校验成功立即删除，防止同一验证码被重复使用
        cacheService.delete(key);
    }

    /** 生成 6 位数字验证码（含前导零，用 SecureRandom 避免可预测序列） */
    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
