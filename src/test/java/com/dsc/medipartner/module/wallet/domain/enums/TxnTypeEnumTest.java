package com.dsc.medipartner.module.wallet.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TxnTypeEnum unit tests (contract §3.7, appendix B.3).
 * The Chinese name and direction are sent to the client so it never maintains its own mapping.
 */
class TxnTypeEnumTest {

    @Test
    void typeNamesMatchContract() {
        assertThat(TxnTypeEnum.nameOf("FREEZE")).isEqualTo("冻结");
        assertThat(TxnTypeEnum.nameOf("UNFREEZE")).isEqualTo("解冻");
        assertThat(TxnTypeEnum.nameOf("PAY")).isEqualTo("支付");
        assertThat(TxnTypeEnum.nameOf("INCOME")).isEqualTo("收入");
        assertThat(TxnTypeEnum.nameOf("REFUND")).isEqualTo("退款");
        assertThat(TxnTypeEnum.nameOf("RECHARGE")).isEqualTo("充值");
    }

    /** Directions drive the +/- sign and colour in the client. */
    @Test
    void directionsMatchContract() {
        assertThat(TxnTypeEnum.directionOf("INCOME")).isEqualTo("IN");
        assertThat(TxnTypeEnum.directionOf("REFUND")).isEqualTo("IN");
        assertThat(TxnTypeEnum.directionOf("RECHARGE")).isEqualTo("IN");
        assertThat(TxnTypeEnum.directionOf("PAY")).isEqualTo("OUT");
        // freeze/unfreeze only move the frozen bucket, balance is unchanged
        assertThat(TxnTypeEnum.directionOf("FREEZE")).isEqualTo("LOCK");
        assertThat(TxnTypeEnum.directionOf("UNFREEZE")).isEqualTo("LOCK");
    }

    @Test
    void lookupIsCaseInsensitiveAndTrimmed() {
        assertThat(TxnTypeEnum.nameOf("  income  ")).isEqualTo("收入");
        assertThat(TxnTypeEnum.nameOf("pay")).isEqualTo("支付");
    }

    /** Unknown or missing types fall back gracefully instead of throwing on a read path. */
    @Test
    void unknownTypeDegradesGracefully() {
        assertThat(TxnTypeEnum.nameOf("WITHDRAW")).isEqualTo("WITHDRAW");
        assertThat(TxnTypeEnum.directionOf("WITHDRAW")).isNull();
        assertThat(TxnTypeEnum.nameOf(null)).isNull();
        assertThat(TxnTypeEnum.directionOf(null)).isNull();
    }

    /** Every declared constant must expose both fields. */
    @Test
    void everyConstantHasNameAndDirection() {
        Arrays.stream(TxnTypeEnum.values()).forEach(type -> {
            assertThat(type.getTypeName()).isNotBlank();
            assertThat(type.getDirection()).isNotNull();
        });
    }
}
