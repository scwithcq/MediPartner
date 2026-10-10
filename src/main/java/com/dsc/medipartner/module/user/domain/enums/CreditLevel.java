package com.dsc.medipartner.module.user.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 信用等级（契约 §6.1）：EXCELLENT >= 90、GOOD 80~89、NORMAL 60~79、POOR < 60。
 */
@Getter
@AllArgsConstructor
public enum CreditLevel {

    EXCELLENT(90, "信用极好，可正常接单，上限 3 单"),
    GOOD(80, "信用良好，可正常接单，上限 3 单"),
    NORMAL(60, "信用一般，接单上限降为 1 单"),
    POOR(0, "信用分低于 60，已暂停接单，请联系客服");

    private final int minScore;
    private final String levelDesc;

    public static CreditLevel of(int score) {
        if (score >= EXCELLENT.minScore) {
            return EXCELLENT;
        }
        if (score >= GOOD.minScore) {
            return GOOD;
        }
        if (score >= NORMAL.minScore) {
            return NORMAL;
        }
        return POOR;
    }

    /** 距下一等级还差的分数；已满级（EXCELLENT）返回 null，VO 键缺失 */
    public Integer nextLevelScore(int score) {
        return this == EXCELLENT ? null : Math.max(0, minScoreOfNext() - score);
    }

    private int minScoreOfNext() {
        return switch (this) {
            case POOR -> NORMAL.minScore;
            case NORMAL -> GOOD.minScore;
            case GOOD -> EXCELLENT.minScore;
            case EXCELLENT -> EXCELLENT.minScore;
        };
    }
}
