package com.dsc.medipartner.module.knowledge.service;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档解析：MD/TXT 按 UTF-8 读文本，PDF 走 PDFBox 抽取文本层。
 * 解析失败统一抛 6003 DOC_PARSE_FAILED，由流水线捕获落 doc.status=FAILED。
 */
@Slf4j
@Component
public class DocumentParser {

    public String parse(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档内容为空");
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query > 0) {
            lower = lower.substring(0, query);
        }
        try {
            if (lower.endsWith(".pdf")) {
                return parsePdf(content);
            }
            if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
                return new String(content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("[knowledge] 文档解析失败: {}", filename, e);
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档解析失败: " + e.getMessage());
        }
        throw new BizException(ErrorCode.DOC_PARSE_FAILED, "不支持的文件类型: " + filename);
    }

    private String parsePdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
