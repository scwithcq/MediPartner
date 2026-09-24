package com.dsc.medipartner.module.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.DocumentParseHandler;
import com.dsc.medipartner.infra.oss.OssService;
import com.dsc.medipartner.infra.vector.VectorStoreAdapter;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeSlice;
import com.dsc.medipartner.module.knowledge.domain.enums.KnowledgeDocStatus;
import com.dsc.medipartner.module.knowledge.domain.enums.KnowledgeSliceStatus;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeDocMapper;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeSliceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理流水线：取内容 -> 解析 -> 切片 -> 入库 -> 向量化 -> 文档 ACTIVE。
 * 幂等：ACTIVE/PARSING 状态直接跳过；任何失败落 doc.status=FAILED 不上抛（毒消息保护）。
 * 向量化失败 ≠ 文档失败：切片仍为 ACTIVE，检索降级关键词打分，remark 留痕。
 */
@Slf4j
@Component
public class KnowledgePipeline implements DocumentParseHandler {

    private static final int CONTENT_MAX = 2000;
    private static final int REMARK_MAX = 500;

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeSliceMapper sliceMapper;
    private final OssService ossService;
    private final DocumentParser documentParser;
    private final TextSplitter textSplitter;
    private final VectorStoreAdapter vectorStoreAdapter;

    public KnowledgePipeline(KnowledgeDocMapper docMapper,
                             KnowledgeSliceMapper sliceMapper,
                             OssService ossService,
                             DocumentParser documentParser,
                             TextSplitter textSplitter,
                             VectorStoreAdapter vectorStoreAdapter) {
        this.docMapper = docMapper;
        this.sliceMapper = sliceMapper;
        this.ossService = ossService;
        this.documentParser = documentParser;
        this.textSplitter = textSplitter;
        this.vectorStoreAdapter = vectorStoreAdapter;
    }

    @Override
    public void handle(Long docId) {
        processDocument(docId);
    }

    public void processDocument(Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            log.warn("[knowledge] 解析任务对应文档不存在 docId={}", docId);
            return;
        }
        if (KnowledgeDocStatus.ACTIVE.name().equals(doc.getStatus())
                || KnowledgeDocStatus.PARSING.name().equals(doc.getStatus())) {
            log.info("[knowledge] 文档已处理/处理中，跳过 docId={} status={}", docId, doc.getStatus());
            return;
        }
        doc.setStatus(KnowledgeDocStatus.PARSING.name());
        doc.setRemark(null);
        docMapper.updateById(doc);
        try {
            byte[] content = fetchContent(doc.getFileUrl());
            String text = documentParser.parse(doc.getFileUrl(), content);
            if (text == null || text.isBlank()) {
                throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档未提取到有效文本");
            }
            List<String> chunks = textSplitter.split(text);
            if (chunks.isEmpty()) {
                throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档切片结果为空");
            }
            replaceSlices(doc, chunks);
            doc.setStatus(KnowledgeDocStatus.ACTIVE.name());
            doc.setPublishTime(LocalDateTime.now());
            docMapper.updateById(doc);
            log.info("[knowledge] 文档处理完成 docId={} title={} 切片数={}", docId, doc.getTitle(), chunks.size());
        } catch (Exception e) {
            log.error("[knowledge] 文档处理失败 docId={} title={}", docId, doc.getTitle(), e);
            doc.setStatus(KnowledgeDocStatus.FAILED.name());
            doc.setRemark(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), REMARK_MAX));
            docMapper.updateById(doc);
        }
    }

    /** classpath:/file:/jar: 直读（内置语料），其余走 OSS 存储路径 */
    private byte[] fetchContent(String fileUrl) throws IOException {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档存储路径为空");
        }
        if (fileUrl.startsWith("classpath:")) {
            try (InputStream in = new ClassPathResource(fileUrl.substring("classpath:".length())).getInputStream()) {
                return in.readAllBytes();
            }
        }
        if (fileUrl.startsWith("file:") || fileUrl.startsWith("jar:")) {
            try (InputStream in = new UrlResource(fileUrl).getInputStream()) {
                return in.readAllBytes();
            }
        }
        return ossService.read(fileUrl);
    }

    private void replaceSlices(KnowledgeDoc doc, List<String> chunks) {
        List<KnowledgeSlice> oldSlices = sliceMapper.selectList(new LambdaQueryWrapper<KnowledgeSlice>()
                .eq(KnowledgeSlice::getDocId, doc.getId()));
        if (!oldSlices.isEmpty()) {
            vectorStoreAdapter.removeBySliceIds(oldSlices.stream()
                    .map(s -> String.valueOf(s.getId()))
                    .toList());
            sliceMapper.delete(new LambdaQueryWrapper<KnowledgeSlice>()
                    .eq(KnowledgeSlice::getDocId, doc.getId()));
        }

        List<KnowledgeSlice> slices = new ArrayList<>();
        int seq = 1;
        for (String chunk : chunks) {
            KnowledgeSlice slice = new KnowledgeSlice();
            slice.setDocId(doc.getId());
            slice.setSeq(seq++);
            slice.setContent(truncate(chunk, CONTENT_MAX));
            slice.setStatus(KnowledgeSliceStatus.ACTIVE.name());
            sliceMapper.insert(slice);
            slices.add(slice);
        }

        if (!vectorStoreAdapter.ready()) {
            doc.setRemark("未配置 AI API Key，检索使用关键词模式");
            return;
        }
        try {
            List<VectorStoreAdapter.VectorDoc> entries = slices.stream()
                    .map(s -> new VectorStoreAdapter.VectorDoc(
                            String.valueOf(s.getId()), s.getContent(), String.valueOf(s.getDocId())))
                    .toList();
            vectorStoreAdapter.addBatch(entries);
            for (KnowledgeSlice slice : slices) {
                slice.setVectorId(String.valueOf(slice.getId()));
                sliceMapper.updateById(slice);
            }
        } catch (Exception e) {
            log.warn("[knowledge] 向量化失败，降级关键词检索 docId={}: {}", doc.getId(), e.getMessage());
            doc.setRemark("向量化失败，检索降级为关键词模式");
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
