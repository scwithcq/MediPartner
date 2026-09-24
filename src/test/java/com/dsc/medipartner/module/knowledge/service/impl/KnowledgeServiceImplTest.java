package com.dsc.medipartner.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.infra.mq.ParseTaskPublisher;
import com.dsc.medipartner.infra.vector.VectorStoreAdapter;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeSlice;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeDocMapper;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeSliceMapper;
import com.dsc.medipartner.module.knowledge.service.KnowledgeSearchHit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeServiceImpl 纯单测：关键词兜底、向量命中回查过滤、发布幂等、导入版本与种子语料去重。
 */
class KnowledgeServiceImplTest {

    private KnowledgeDocMapper docMapper;
    private KnowledgeSliceMapper sliceMapper;
    private VectorStoreAdapter vectorStoreAdapter;
    private ParseTaskPublisher parseTaskPublisher;
    private KnowledgeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析列名依赖实体 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDoc.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeSlice.class);
    }

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        sliceMapper = mock(KnowledgeSliceMapper.class);
        vectorStoreAdapter = mock(VectorStoreAdapter.class);
        parseTaskPublisher = mock(ParseTaskPublisher.class);
        service = new KnowledgeServiceImpl(docMapper, sliceMapper, vectorStoreAdapter, parseTaskPublisher);
    }

    private KnowledgeSlice slice(long id, long docId, String content, String status) {
        KnowledgeSlice slice = new KnowledgeSlice();
        slice.setId(id);
        slice.setDocId(docId);
        slice.setContent(content);
        slice.setStatus(status);
        return slice;
    }

    private KnowledgeDoc doc(long id, String title, String status) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setTitle(title);
        doc.setStatus(status);
        return doc;
    }

    @Test
    void keywordFallbackFiltersInactiveDocs() {
        when(vectorStoreAdapter.ready()).thenReturn(false);
        when(sliceMapper.selectList(any())).thenReturn(List.of(
                slice(11L, 1L, "胸痛应优先前往心血管内科就诊。", "ACTIVE"),
                slice(12L, 1L, "挂号可通过小程序提前完成。", "ACTIVE"),
                slice(13L, 2L, "胸痛患者须知。", "ACTIVE")));
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(
                doc(1L, "陪诊服务规范", "ACTIVE"),
                doc(2L, "已下架文档", "INACTIVE")));

        List<KnowledgeSearchHit> hits = service.search("胸痛就诊", 3);

        // 仅 doc1 的切片 11 命中：切片 12 无重合词元，切片 13 因文档 INACTIVE 被过滤
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sliceId()).isEqualTo("11");
        assertThat(hits.get(0).docId()).isEqualTo("1");
        assertThat(hits.get(0).docTitle()).isEqualTo("陪诊服务规范");
        assertThat(hits.get(0).score()).isGreaterThan(0.0);
        verify(vectorStoreAdapter, never()).search(anyString(), anyInt());
    }

    @Test
    void vectorHitsAreFilteredByActiveDocs() {
        when(vectorStoreAdapter.ready()).thenReturn(true);
        when(vectorStoreAdapter.search("胸痛就诊", 3)).thenReturn(List.of(
                new VectorStoreAdapter.VectorHit("11", "胸痛应优先前往心血管内科就诊。", 0.9),
                new VectorStoreAdapter.VectorHit("13", "胸痛患者须知。", 0.5)));
        when(sliceMapper.selectBatchIds(any())).thenReturn(List.of(
                slice(11L, 1L, "胸痛应优先前往心血管内科就诊。", "ACTIVE"),
                slice(13L, 2L, "胸痛患者须知。", "ACTIVE")));
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(
                doc(1L, "陪诊服务规范", "ACTIVE"),
                doc(2L, "已下架文档", "INACTIVE")));

        List<KnowledgeSearchHit> hits = service.search("胸痛就诊", 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sliceId()).isEqualTo("11");
        assertThat(hits.get(0).score()).isEqualTo(0.9);
        verify(sliceMapper, never()).selectList(any());
    }

    @Test
    void countActiveSlicesHandlesNull() {
        when(sliceMapper.selectCount(any())).thenReturn(7L);
        assertThat(service.countActiveSlices()).isEqualTo(7);

        when(sliceMapper.selectCount(any())).thenReturn(null);
        assertThat(service.countActiveSlices()).isZero();
    }

    @Test
    void publishSkipsActiveDoc() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, "陪诊服务规范", "ACTIVE"));
        service.publish(1L);
        verify(parseTaskPublisher, never()).publishParseTask(any());
    }

    @Test
    void publishDispatchesPendingDoc() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, "陪诊服务规范", "PENDING"));
        service.publish(1L);
        verify(parseTaskPublisher).publishParseTask(1L);
    }

    @Test
    void publishThrows6003WhenDocMissing() {
        when(docMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.publish(1L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOC_PARSE_FAILED));
    }

    @Test
    void importDocumentStartsAtVersionOne() {
        when(docMapper.selectOne(any())).thenReturn(null);
        KnowledgeDoc doc = service.importDocument("陪诊服务规范", "file:///tmp/a.md", null);
        assertThat(doc.getVersion()).isEqualTo(1);
        assertThat(doc.getStatus()).isEqualTo("PENDING");
        assertThat(doc.getSource()).isEqualTo("UPLOAD");
        verify(docMapper).insert(any(KnowledgeDoc.class));
    }

    @Test
    void importDocumentBumpsVersion() {
        KnowledgeDoc latest = doc(5L, "陪诊服务规范", "INACTIVE");
        latest.setVersion(2);
        when(docMapper.selectOne(any())).thenReturn(latest);
        KnowledgeDoc doc = service.importDocument("陪诊服务规范", "file:///tmp/a.md", "UPLOAD");
        assertThat(doc.getVersion()).isEqualTo(3);
    }

    @Test
    void importSeedDocumentsImportsAllMarkdownFiles() {
        when(docMapper.selectCount(any())).thenReturn(0L);
        List<KnowledgeDoc> imported = service.importSeedDocuments();
        assertThat(imported).hasSize(5);
        assertThat(imported).allSatisfy(d -> {
            assertThat(d.getSource()).isEqualTo("SEED");
            assertThat(d.getStatus()).isEqualTo("PENDING");
            assertThat(d.getVersion()).isEqualTo(1);
        });
        assertThat(imported).extracting(KnowledgeDoc::getTitle).contains("陪诊服务规范");
    }

    @Test
    void importSeedDocumentsSkipsExistingTitles() {
        when(docMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.importSeedDocuments()).isEmpty();
        verify(docMapper, never()).insert(any(KnowledgeDoc.class));
    }
}
