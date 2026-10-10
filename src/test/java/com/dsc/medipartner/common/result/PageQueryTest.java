package com.dsc.medipartner.common.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PageQuery unit tests (contract §1.3): field names, defaults and bounds clamping.
 */
class PageQueryTest {

    @Test
    void defaultsMatchContract() {
        PageQuery q = new PageQuery();
        assertThat(q.getPageNum()).isEqualTo(1);
        assertThat(q.getPageSize()).isEqualTo(10);
        assertThat(q.getSortBy()).isNull();
        assertThat(q.getOrder()).isNull();
        assertThat(q.getUpdatedSince()).isNull();
    }

    @Test
    void pageSizeIsClampedToUpperBoundOf100() {
        PageQuery q = new PageQuery();
        q.setPageSize(500);
        assertThat(q.getEffectivePageSize()).isEqualTo(100);
    }

    @Test
    void pageSizeKeepsValueWithinRange() {
        PageQuery q = new PageQuery();
        q.setPageSize(37);
        assertThat(q.getEffectivePageSize()).isEqualTo(37);
        q.setPageSize(1);
        assertThat(q.getEffectivePageSize()).isEqualTo(1);
        q.setPageSize(100);
        assertThat(q.getEffectivePageSize()).isEqualTo(100);
    }

    @Test
    void pageSizeBelowOneFallsBackToOne() {
        PageQuery q = new PageQuery();
        q.setPageSize(0);
        assertThat(q.getEffectivePageSize()).isEqualTo(1);
        q.setPageSize(-5);
        assertThat(q.getEffectivePageSize()).isEqualTo(1);
    }

    @Test
    void pageNumBelowOneFallsBackToOne() {
        PageQuery q = new PageQuery();
        q.setPageNum(0);
        assertThat(q.getEffectivePageNum()).isEqualTo(1);
        q.setPageNum(-3);
        assertThat(q.getEffectivePageNum()).isEqualTo(1);
    }

    @Test
    void pageNumKeepsValidValue() {
        PageQuery q = new PageQuery();
        q.setPageNum(7);
        assertThat(q.getEffectivePageNum()).isEqualTo(7);
    }
}
