package com.dsc.medipartner.module.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextSplitter 纯单测：切片上限、段落聚合、overlap 连续性、硬切与末尾重复切片回归。
 */
class TextSplitterTest {

    private final TextSplitter splitter = new TextSplitter(500, 50);

    @Test
    void blankTextProducesNoChunks() {
        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split("   ")).isEmpty();
    }

    @Test
    void shortParagraphsJoinIntoOneChunk() {
        List<String> chunks = splitter.split("第一段。\n\n第二段。\n\n第三段。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("第一段。\n第二段。\n第三段。");
    }

    @Test
    void chunksNeverExceedMaxChars() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("段落").append(i).append("。").append("内容".repeat(50)).append("\n\n");
        }
        List<String> chunks = splitter.split(text.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(500));
    }

    @Test
    void consecutiveChunksKeepOverlapOnLongParagraph() {
        // 单段 40 句 x 40 字 = 1600 字，走句子切分路径
        StringBuilder paragraph = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            paragraph.append("句").append(i).append("内容".repeat(18)).append("。");
        }
        List<String> chunks = splitter.split(paragraph.toString());
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(500));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String tail = prev.substring(Math.max(0, prev.length() - 50));
            assertThat(chunks.get(i)).startsWith(tail);
        }
    }

    @Test
    void hardCutKeepsOverlapAndChunkLimit() {
        // 1200 字无标点单段 -> 硬切
        String text = "a".repeat(1200);
        List<String> chunks = splitter.split(text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(500);
        assertThat(chunks.get(1)).hasSize(500);
        assertThat(chunks.get(2)).hasSize(300);
        assertThat(chunks.get(1)).startsWith(chunks.get(0).substring(450));
        assertThat(chunks.get(2)).startsWith(chunks.get(1).substring(450));
    }

    @Test
    void noDuplicateTailChunkAfterTrailingLongParagraph() {
        // 文档以超长段落结尾：不能产出纯 overlap 种子的重复尾切片（PDF 常见形态）
        String text = "这是一个普通的短段落。\n\n" + "b".repeat(1200);
        List<String> chunks = splitter.split(text);
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0)).isEqualTo("这是一个普通的短段落。");
        assertThat(chunks.get(1)).hasSize(500);
        assertThat(chunks.get(2)).hasSize(500);
        assertThat(chunks.get(3)).isEqualTo("b".repeat(300));
        // 回归点：末尾 flush 不能把 50 字符的纯种子缓冲再产出一个重复切片
        assertThat(chunks).doesNotContain("b".repeat(50));
    }
}
