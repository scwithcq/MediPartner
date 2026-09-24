package com.dsc.medipartner.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.ParseTaskPublisher;
import com.dsc.medipartner.infra.vector.VectorStoreAdapter;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeSlice;
import com.dsc.medipartner.module.knowledge.domain.enums.KnowledgeDocStatus;
import com.dsc.medipartner.module.knowledge.domain.enums.KnowledgeSliceStatus;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeDocMapper;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeSliceMapper;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import com.dsc.medipartner.module.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final String SOURCE_SEED = "SEED";
    private static final String SOURCE_UPLOAD = "UPLOAD";
    private static final String SEED_PATTERN = "classpath*:knowledge/*.md";

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeSliceMapper sliceMapper;
    private final VectorStoreAdapter vectorStoreAdapter;
    private final ParseTaskPublisher parseTaskPublisher;

    public KnowledgeServiceImpl(KnowledgeDocMapper docMapper,
                                KnowledgeSliceMapper sliceMapper,
                                VectorStoreAdapter vectorStoreAdapter,
                                ParseTaskPublisher parseTaskPublisher) {
        this.docMapper = docMapper;
        this.sliceMapper = sliceMapper;
        this.vectorStoreAdapter = vectorStoreAdapter;
        this.parseTaskPublisher = parseTaskPublisher;
    }

    @Override
    public KnowledgeDoc importDocument(String title, String fileUrl, String source) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文档标题不能为空");
        }
        KnowledgeDoc latest = docMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getTitle, title)
                .orderByDesc(KnowledgeDoc::getVersion)
                .last("LIMIT 1"));
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title);
        doc.setFileUrl(fileUrl);
        doc.setSource(source == null ? SOURCE_UPLOAD : source);
        doc.setVersion(latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1);
        doc.setStatus(KnowledgeDocStatus.PENDING.name());
        docMapper.insert(doc);
        return doc;
    }

    @Override
    public List<KnowledgeDoc> importSeedDocuments() {
        List<KnowledgeDoc> imported = new ArrayList<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        } catch (IOException e) {
            log.warn("[knowledge] 未找到内置语料 {}: {}", SEED_PATTERN, e.getMessage());
            return imported;
        }
        Arrays.sort(resources, Comparator.comparing(r -> Objects.toString(r.getFilename(), "")));
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".md")) {
                continue;
            }
            String title = filename.substring(0, filename.length() - 3);
            Long exists = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                    .eq(KnowledgeDoc::getTitle, title)
                    .eq(KnowledgeDoc::getSource, SOURCE_SEED));
            if (exists != null && exists > 0) {
                continue;
            }
            String fileUrl;
            try {
                fileUrl = resource.getURI().toString();
            } catch (IOException e) {
                fileUrl = "classpath:knowledge/" + filename;
            }
            imported.add(importDocument(title, fileUrl, SOURCE_SEED));
        }
        return imported;
    }

    @Override
    public void publish(Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOC_PARSE_FAILED, "文档不存在: " + docId);
        }
        if (KnowledgeDocStatus.ACTIVE.name().equals(doc.getStatus())
                || KnowledgeDocStatus.PARSING.name().equals(doc.getStatus())) {
            return;
        }
        parseTaskPublisher.publishParseTask(docId);
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        if (vectorStoreAdapter.ready()) {
            List<KnowledgeSearchHit> hits = mapVectorHits(vectorStoreAdapter.search(query, topK));
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return keywordSearch(query, topK);
    }

    @Override
    public long countActiveSlices() {
        Long count = sliceMapper.selectCount(new LambdaQueryWrapper<KnowledgeSlice>()
                .eq(KnowledgeSlice::getStatus, KnowledgeSliceStatus.ACTIVE.name()));
        return count == null ? 0 : count;
    }

    @Override
    public void rebuildVectorStoreIfEmpty() {
        if (!vectorStoreAdapter.ready() || vectorStoreAdapter.hasPersistedData()) {
            return;
        }
        List<KnowledgeSlice> slices = activeSlicesOfActiveDocs();
        if (slices.isEmpty()) {
            return;
        }
        List<VectorStoreAdapter.VectorDoc> entries = slices.stream()
                .map(s -> new VectorStoreAdapter.VectorDoc(
                        String.valueOf(s.getId()), s.getContent(), String.valueOf(s.getDocId())))
                .toList();
        vectorStoreAdapter.addBatch(entries);
        for (KnowledgeSlice slice : slices) {
            if (slice.getVectorId() == null) {
                slice.setVectorId(String.valueOf(slice.getId()));
                sliceMapper.updateById(slice);
            }
        }
        log.info("[knowledge] 向量库为空，已全量重建 {} 条切片向量", slices.size());
    }

    /** 向量命中 -> 回查 DB 过滤（切片与文档都必须 ACTIVE），保持向量得分排序 */
    private List<KnowledgeSearchHit> mapVectorHits(List<VectorStoreAdapter.VectorHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Long> sliceIds = hits.stream()
                .map(h -> parseLongSafely(h.id()))
                .filter(Objects::nonNull)
                .toList();
        if (sliceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeSlice> sliceMap = new HashMap<>();
        for (KnowledgeSlice slice : sliceMapper.selectBatchIds(sliceIds)) {
            sliceMap.put(slice.getId(), slice);
        }
        Map<Long, KnowledgeDoc> activeDocs = activeDocMap(sliceMap.values().stream()
                .map(KnowledgeSlice::getDocId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        List<KnowledgeSearchHit> result = new ArrayList<>();
        for (VectorStoreAdapter.VectorHit hit : hits) {
            Long sliceId = parseLongSafely(hit.id());
            if (sliceId == null) {
                continue;
            }
            KnowledgeSlice slice = sliceMap.get(sliceId);
            if (slice == null || !KnowledgeSliceStatus.ACTIVE.name().equals(slice.getStatus())) {
                continue;
            }
            KnowledgeDoc doc = activeDocs.get(slice.getDocId());
            if (doc == null) {
                continue;
            }
            result.add(new KnowledgeSearchHit(String.valueOf(slice.getId()),
                    String.valueOf(doc.getId()), doc.getTitle(), slice.getContent(), hit.score()));
        }
        return result;
    }

    /** 关键词兜底：CJK bigram + ASCII 词元与查询的重合度打分 */
    private List<KnowledgeSearchHit> keywordSearch(String query, int topK) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<KnowledgeSlice> slices = activeSlicesOfActiveDocs();
        Map<Long, KnowledgeDoc> activeDocs = activeDocMap(slices.stream()
                .map(KnowledgeSlice::getDocId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        record Scored(KnowledgeSlice slice, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (KnowledgeSlice slice : slices) {
            KnowledgeDoc doc = activeDocs.get(slice.getDocId());
            if (doc == null) {
                continue;
            }
            Set<String> contentTokens = tokenize(slice.getContent());
            long matched = queryTokens.stream().filter(contentTokens::contains).count();
            if (matched > 0) {
                scored.add(new Scored(slice, (double) matched / queryTokens.size()));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<KnowledgeSearchHit> result = new ArrayList<>();
        for (Scored item : scored.subList(0, Math.min(topK, scored.size()))) {
            KnowledgeDoc doc = activeDocs.get(item.slice().getDocId());
            result.add(new KnowledgeSearchHit(String.valueOf(item.slice().getId()),
                    String.valueOf(doc.getId()), doc.getTitle(), item.slice().getContent(), item.score()));
        }
        return result;
    }

    private List<KnowledgeSlice> activeSlicesOfActiveDocs() {
        List<KnowledgeSlice> slices = sliceMapper.selectList(new LambdaQueryWrapper<KnowledgeSlice>()
                .eq(KnowledgeSlice::getStatus, KnowledgeSliceStatus.ACTIVE.name()));
        if (slices.isEmpty()) {
            return slices;
        }
        Set<Long> docIds = slices.stream()
                .map(KnowledgeSlice::getDocId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, KnowledgeDoc> activeDocs = activeDocMap(docIds);
        return slices.stream()
                .filter(s -> activeDocs.containsKey(s.getDocId()))
                .toList();
    }

    private Map<Long, KnowledgeDoc> activeDocMap(Set<Long> docIds) {
        Map<Long, KnowledgeDoc> map = new HashMap<>();
        if (docIds.isEmpty()) {
            return map;
        }
        for (KnowledgeDoc doc : docMapper.selectBatchIds(docIds)) {
            if (KnowledgeDocStatus.ACTIVE.name().equals(doc.getStatus())) {
                map.put(doc.getId(), doc);
            }
        }
        return map;
    }

    /** 中日韩文字取 bigram，ASCII 取长度 >= 2 的小写词 */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                if (i + 1 < chars.length && isCjk(chars[i + 1])) {
                    tokens.add("" + c + chars[i + 1]);
                }
            } else if (Character.isLetterOrDigit(c)) {
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, tokens);
            }
        }
        flushAscii(ascii, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder ascii, Set<String> tokens) {
        if (ascii.length() >= 2) {
            tokens.add(ascii.toString());
        }
        ascii.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private Long parseLongSafely(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
