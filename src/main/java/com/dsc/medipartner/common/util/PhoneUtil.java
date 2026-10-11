package com.dsc.medipartner.common.util;

import java.util.regex.Pattern;

/**
 * 手机号校验与归一化（契约 §2.5、§2.6）。
 *
 * <p><b>为什么单独成类而不是用 Bean Validation 注解</b>：契约要求手机号格式非法时返回
 * {@code 1001}，但本项目 {@code GlobalExceptionHandler} 会把
 * {@code MethodArgumentNotValidException} 统一转成 {@code 1001}——
 * 看似一致，实则把「手机号格式错误」与「任何其它参数错误」混为一谈，
 * 且校验发生在 Controller 层，Service 里的正确分支会成为死代码
 * （历史教训：{@code RechargeRequest} 曾因 {@code @DecimalMax} 导致 {@code 4006} 永远拿不到）。
 * 因此手机号校验放在 Service 层，由本类提供工具方法。
 */
public final class PhoneUtil {

    /**
     * 中国大陆手机号：1 开头，第二位 3~9，共 11 位。
     *
     * <p>刻意不用更严格的号段白名单（如只认 13x/15x/18x）：虚拟运营商号段持续新增，
     * 过严的规则会把合法用户挡在门外，且这类误伤在生产上很难及时发现。
     */
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneUtil() {
    }

    /** 是否为合法的中国大陆手机号 */
    public static boolean isValid(String phone) {
        return phone != null && MOBILE.matcher(phone.trim()).matches();
    }

    /**
     * 归一化：去除空白字符。
     *
     * <p>用户在输入框里复制粘贴手机号常带空格或不换行空格，
     * 不归一化会导致「同一个人绑定出两个手机号」，或校验明明该过却报 1001。
     *
     * @return 去空白后的手机号；入参为 null 时返回 null
     */
    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        // 去掉所有空白（含全角空格 \u3000 与不换行空格 \u00A0）
        return phone.replaceAll("[\\s\\u00A0\\u3000]", "");
    }
}
