package com.dsc.medipartner.module.order.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 虚拟号服务（契约 §5.6、§7.10、附录 F.2）。
 *
 * <p>本项目为 <b>mock</b>：由 {@code orderNo} 哈希稳定生成 {@code 170} 号段号码，
 * <b>同一订单多次查询返回同一号码</b>（确定性），双方拿到的是同一个号。真实隐私号需接入运营商
 * AXB 服务，届时只替换本实现、不动接口。
 */
@Component
public class VirtualPhoneService {

    /** 虚拟号号段前缀（契约 §5.6：形如 1700000XXXX） */
    private static final String PREFIX = "170";
    /** 号段前缀后补零至 8 位，再拼 3 位哈希，共 11 位 */
    private static final String PADDING = "00000";

    /**
     * 由订单号稳定生成 11 位虚拟号。
     *
     * @param orderNo 订单号，必填
     * @return 形如 {@code 17000001234} 的确定性号码
     * @throws IllegalArgumentException orderNo 为空时抛出（调用方数据异常，属编程错误）
     */
    public String generate(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        int hash = hash(orderNo);
        // 取哈希绝对值后 5 位，保证同一订单稳定且号段内分布均匀
        int suffix = Math.abs(hash % 100_000);
        return PREFIX + PADDING + String.format("%05d", suffix).substring(0, 3);
    }

    /** SHA-256 前 4 字节转 int，跨进程稳定（不用 String.hashCode 以防实现变更） */
    private int hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，不可能走到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
