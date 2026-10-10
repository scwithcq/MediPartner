package com.dsc.medipartner.common.util;

/**
 * 脱敏工具（契约 §1.11）。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code phone} — 中间四位打星：{@code 138****1234}</li>
 *   <li>{@code realName} — 保留姓氏：{@code 张**}</li>
 *   <li>{@code idCardNo} / {@code openid} — <b>永不返回</b>（实体上加 {@code @JsonIgnore}，不经过本类）</li>
 * </ul>
 *
 * <p>脱敏一律在<b>服务端</b>完成，前端不做二次处理，避免原值从别的字段漏出。
 */
public final class DesensitizeUtil {

    private static final String MASK = "****";

    private DesensitizeUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位，中间打星。
     * <p>非 11 位或空值原样返回（避免把已是脱敏态或异常数据二次破坏）。
     */
    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + MASK + phone.substring(7);
    }

    /**
     * 姓名脱敏：保留姓氏，其余打星。
     * <p>单字姓名（或空值）原样返回；两字及以上返回「姓 + 等长星号」。
     */
    public static String realName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        if (trimmed.length() <= 1) {
            return trimmed;
        }
        return trimmed.charAt(0) + "*".repeat(trimmed.length() - 1);
    }

    /**
     * 身份证号脱敏回显（契约 §7.2 的 {@code idCardMasked}）：保留前 4 位与后 4 位。
     * <p><b>注意</b>：这只用于「本人查看自己的资料」场景；对外接口一律不返回证件号。
     * 位数不足 8 时全部打星。
     */
    public static String idCard(String idCardNo) {
        if (idCardNo == null || idCardNo.isBlank()) {
            return idCardNo;
        }
        String value = idCardNo.trim();
        if (value.length() < 8) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    /**
     * 昵称脱敏（评价匿名场景，契约 §6.3）：保留姓氏，其余打星。
     * 与 {@link #realName} 同规则，独立命名是为了让调用点语义清晰。
     */
    public static String nickname(String nickname) {
        return realName(nickname);
    }
}
