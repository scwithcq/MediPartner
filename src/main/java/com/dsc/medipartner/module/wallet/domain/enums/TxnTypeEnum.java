package com.dsc.medipartner.module.wallet.domain.enums;

import lombok.Getter;

/**
 * 资金流水类型（契约附录 B.3）。
 *
 * <p>阶段 1.5 补 {@code typeName} 中文名与 {@code direction} 方向，供 §3.7 直接下发，
 * 前端免维护映射、免自己判断正负号。
 *
 * <p>方向语义：
 * <ul>
 *   <li>{@code IN} 入账 —— 余额增加</li>
 *   <li>{@code OUT} 出账 —— 余额减少</li>
 *   <li>{@code LOCK} 冻结类 —— 只动 {@code frozen}，{@code balance} 不变</li>
 * </ul>
 *
 * <p><b>注意</b>：{@code WITHDRAW} 属阶段 4 契约（H-14），届时补枚举值与下方映射。
 */
@Getter
public enum TxnTypeEnum {

    FREEZE("冻结", Direction.LOCK),
    UNFREEZE("解冻", Direction.LOCK),
    PAY("支付", Direction.OUT),
    INCOME("收入", Direction.IN),
    REFUND("退款", Direction.IN),
    RECHARGE("充值", Direction.IN),
    /** 提现（阶段 4 / H-14 补齐）：申请时即扣减 balance，出账方向 */
    WITHDRAW("提现", Direction.OUT);

    /** 中文名（§3.7 typeName） */
    private final String typeName;
    private final Direction direction;

    TxnTypeEnum(String typeName, Direction direction) {
        this.typeName = typeName;
        this.direction = direction;
    }

    /** 资金方向（§3.7 direction） */
    public enum Direction {
        /** 入账 */
        IN,
        /** 出账 */
        OUT,
        /** 冻结类：只影响 frozen */
        LOCK
    }

    /** 中文名；未知类型回退为枚举名本身，保证展示不空白 */
    public static String nameOf(String type) {
        TxnTypeEnum value = of(type);
        return value == null ? type : value.typeName;
    }

    /** 方向名；未知类型返回 null（前端据此不渲染正负号） */
    public static String directionOf(String type) {
        TxnTypeEnum value = of(type);
        return value == null ? null : value.direction.name();
    }

    private static TxnTypeEnum of(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
