package com.dsc.medipartner.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DesensitizeUtil unit tests (contract §1.11).
 * Masking happens server-side only, so these pin the exact wire format.
 */
class DesensitizeUtilTest {

    // ---------------- phone ----------------

    @Test
    void phoneMasksMiddleFourDigits() {
        assertThat(DesensitizeUtil.phone("13800001234")).isEqualTo("138****1234");
    }

    @Test
    void phoneKeepsFirstThreeAndLastFour() {
        String masked = DesensitizeUtil.phone("18612345678");
        assertThat(masked).startsWith("186");
        assertThat(masked).endsWith("5678");
        assertThat(masked).hasSize(11);
    }

    @Test
    void phoneLeavesNullAndBlankUntouched() {
        assertThat(DesensitizeUtil.phone(null)).isNull();
        assertThat(DesensitizeUtil.phone("")).isEmpty();
        assertThat(DesensitizeUtil.phone("   ")).isEqualTo("   ");
    }

    /** Already-masked or malformed values are returned as-is rather than double-mangled. */
    @Test
    void phonePassesThroughNonElevenDigitValues() {
        assertThat(DesensitizeUtil.phone("138****1234")).isEqualTo("138****1234");
        assertThat(DesensitizeUtil.phone("12345")).isEqualTo("12345");
        assertThat(DesensitizeUtil.phone("138000012345678")).isEqualTo("138000012345678");
    }

    // ---------------- realName ----------------

    @Test
    void realNameKeepsSurnameOnly() {
        assertThat(DesensitizeUtil.realName("张三")).isEqualTo("张*");
        assertThat(DesensitizeUtil.realName("张三丰")).isEqualTo("张**");
        assertThat(DesensitizeUtil.realName("欧阳修文")).isEqualTo("欧***");
    }

    @Test
    void realNameSingleCharUnchanged() {
        assertThat(DesensitizeUtil.realName("张")).isEqualTo("张");
    }

    @Test
    void realNameLeavesNullAndBlankUntouched() {
        assertThat(DesensitizeUtil.realName(null)).isNull();
        assertThat(DesensitizeUtil.realName("")).isEmpty();
    }

    @Test
    void realNameTrimsBeforeMasking() {
        assertThat(DesensitizeUtil.realName("  张三  ")).isEqualTo("张*");
    }

    // ---------------- idCard ----------------

    @Test
    void idCardKeepsFirstAndLastFour() {
        String masked = DesensitizeUtil.idCard("330102199001011234");
        assertThat(masked).startsWith("3301");
        assertThat(masked).endsWith("1234");
        assertThat(masked).hasSize(18);
        assertThat(masked).contains("*");
    }

    @Test
    void idCardShortValueIsFullyMasked() {
        assertThat(DesensitizeUtil.idCard("1234567")).isEqualTo("*******");
    }

    @Test
    void idCardNullSafe() {
        assertThat(DesensitizeUtil.idCard(null)).isNull();
        assertThat(DesensitizeUtil.idCard("")).isEmpty();
    }

    // ---------------- nickname (anonymous reviews) ----------------

    @Test
    void nicknameFollowsRealNameRule() {
        assertThat(DesensitizeUtil.nickname("王小明")).isEqualTo("王**");
    }
}
