package com.dsc.medipartner.module.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片：段落优先聚合到 maxChars，超长段落按句子切，句子仍超长则硬切；
 * 相邻切片保留 overlap 字符重叠，避免语义在边界被截断。
 */
@Component
public class TextSplitter {

    private final int maxChars;
    private final int overlap;

    public TextSplitter(@Value("${medi.knowledge.split-max-chars:500}") int maxChars,
                        @Value("${medi.knowledge.split-overlap:50}") int overlap) {
        this.maxChars = Math.max(100, maxChars);
        this.overlap = Math.min(Math.max(0, overlap), this.maxChars / 2);
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder current = new StringBuilder();
        // current 是否只含 overlap 种子（文档以超长段落结尾时，末尾 flush 会产出纯重复切片，需跳过）
        boolean seedOnly = false;
        for (String paragraph : normalized.split("\n+")) {
            String unit = paragraph.trim();
            if (unit.isEmpty()) {
                continue;
            }
            if (unit.length() > maxChars) {
                // 超长段落：先冲掉当前缓冲，再按句子/硬切处理
                flush(current, chunks);
                splitLongParagraph(unit, chunks);
                seedOverlap(chunks, current);
                seedOnly = current.length() > 0;
                continue;
            }
            if (current.length() + unit.length() + 1 > maxChars) {
                if (seedOnly) {
                    // 缓冲里只有 overlap 种子且装不下新段落：丢弃种子，避免产出纯重复小切片
                    current.setLength(0);
                } else {
                    flush(current, chunks);
                    seedOverlap(chunks, current);
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
            seedOnly = false;
        }
        if (!seedOnly) {
            flush(current, chunks);
        }
        return chunks;
    }

    private void splitLongParagraph(String paragraph, List<String> chunks) {
        StringBuilder current = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[。！？；!?;])")) {
            String unit = sentence.trim();
            if (unit.isEmpty()) {
                continue;
            }
            while (unit.length() > maxChars) {
                // 单句仍超长：硬切。回退 maxChars-overlap 保证相邻硬切切片自带重叠，
                // 此处不做 seedOverlap（否则会插入纯重复小切片）
                flush(current, chunks);
                chunks.add(unit.substring(0, maxChars));
                unit = unit.substring(maxChars - overlap);
            }
            if (current.length() + unit.length() > maxChars) {
                flush(current, chunks);
                seedOverlap(chunks, current);
            }
            current.append(unit);
        }
        flush(current, chunks);
    }

    private void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    /** 用上一切片尾部 overlap 字符作为新切片开头，保持上下文连续 */
    private void seedOverlap(List<String> chunks, StringBuilder current) {
        if (overlap <= 0 || chunks.isEmpty()) {
            return;
        }
        String last = chunks.get(chunks.size() - 1);
        current.append(last.substring(Math.max(0, last.length() - overlap)));
    }
}
