package com.dsc.medipartner.module.order.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VirtualPhoneService unit tests (contract §5.6, §7.10, appendix F.2).
 *
 * <p>The mock's key promise is <b>determinism</b>: the same order must always yield the same
 * number, because both sides query it independently and must dial the same line.
 */
class VirtualPhoneServiceTest {

    private final VirtualPhoneService service = new VirtualPhoneService();

    @Test
    void sameOrderNumberAlwaysYieldsSameVirtualPhone() {
        String first = service.generate("MP20260921000000123456");
        String second = service.generate("MP20260921000000123456");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentOrdersYieldDifferentNumbers() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            numbers.add(service.generate("MP20260921000000" + String.format("%06d", i)));
        }
        // 50 distinct orders should not all collapse onto the same number
        assertThat(numbers).hasSizeGreaterThan(1);
    }

    @Test
    void numberIsElevenDigitsWith170Prefix() {
        String phone = service.generate("MP20260921000000123456");
        assertThat(phone).hasSize(11);
        assertThat(phone).startsWith("170");
        assertThat(phone).matches("1\\d{10}");
    }

    @Test
    void blankOrderNumberIsRejected() {
        assertThatThrownBy(() -> service.generate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.generate("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Long order numbers (the real MP format) must still produce a valid 11-digit number. */
    @Test
    void realOrderNoFormatProducesValidNumber() {
        String phone = service.generate("MP20260918143000123456");
        assertThat(phone).matches("1\\d{10}");
    }
}
