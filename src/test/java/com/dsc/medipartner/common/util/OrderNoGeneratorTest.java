package com.dsc.medipartner.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNoGeneratorTest {

    @Test
    void nextHasPrefixAndLength() {
        String orderNo = OrderNoGenerator.next("MP");
        assertThat(orderNo).startsWith("MP");
        assertThat(orderNo).hasSize(22);
    }
}
