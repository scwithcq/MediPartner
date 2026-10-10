package com.dsc.medipartner.common.util;

import java.util.List;

/**
 * 本地敏感词库（契约 §7.21 备注 4：阶段 3 用本地词库，命中返回 1001）。
 * 词表刻意精简，只拦明显不当内容；生产环境可替换为云端内容安全 API。
 */
public final class SensitiveWords {

    private static final List<String> WORDS = List.of(
            "傻逼", "煞笔", "妈的", "他妈的", "fuck", "shit",
            "骗子", "诈骗", "传销", "赌博", "博彩",
            "代开发票", "办证刻章", "迷药", "器官买卖");

    private SensitiveWords() {
    }

    /** 返回命中的第一个敏感词，未命中返回 null（大小写不敏感） */
    public static String firstHit(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String word : WORDS) {
            if (lower.contains(word.toLowerCase())) {
                return word;
            }
        }
        return null;
    }

    public static boolean contains(String text) {
        return firstHit(text) != null;
    }
}
