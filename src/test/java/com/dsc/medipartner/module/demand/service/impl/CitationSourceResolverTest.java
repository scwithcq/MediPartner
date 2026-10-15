package com.dsc.medipartner.module.demand.service.impl;

import com.dsc.medipartner.module.demand.domain.vo.CitationVO;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeDoc;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeSlice;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeDocMapper;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeSliceMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CitationSourceResolverTest {
    private final KnowledgeDocMapper docs = mock(KnowledgeDocMapper.class);
    private final KnowledgeSliceMapper slices = mock(KnowledgeSliceMapper.class);
    private final CitationSourceResolver resolver = new CitationSourceResolver(docs, slices);
    private final String excerpt = "就医流程说明".repeat(40);
    private CitationVO citation() {
        var c = new CitationVO(); c.setDocId("10"); c.setSliceText(excerpt); return c;
    }
    private void resolve(CitationVO c) {
        var dept = new DepartmentVO(); dept.setCitations(List.of(c));
        resolver.fillHistoricalSources(List.of(dept));
    }
    private KnowledgeSlice slice(String text) {
        var s = new KnowledgeSlice(); s.setDocId(10L); s.setContent(text); return s;
    }
    private void active() {
        var doc = new KnowledgeDoc(); doc.setStatus("ACTIVE"); when(docs.selectById(10L)).thenReturn(doc);
    }
    @Test void restoresFullTextForHistoricalExcerpt() {
        active(); when(slices.selectList(any())).thenReturn(List.of(slice(excerpt + "后续完整文字")));
        var c = citation(); resolve(c);
        assertThat(c.getFullSliceText()).isEqualTo(excerpt + "后续完整文字");
        assertThat(c.getSliceText()).isEqualTo(excerpt);
    }
    @Test void doesNotGuessWhenPrefixesAreAmbiguous() {
        active(); when(slices.selectList(any())).thenReturn(List.of(slice(excerpt + "甲"), slice(excerpt + "乙")));
        var c = citation(); resolve(c); assertThat(c.getFullSliceText()).isNull();
    }
    @Test void unpublishedSourceIsNotExposed() {
        var doc = new KnowledgeDoc(); doc.setStatus("INACTIVE"); when(docs.selectById(10L)).thenReturn(doc);
        var c = citation(); resolve(c); assertThat(c.getFullSliceText()).isNull(); verifyNoInteractions(slices);
    }
    @Test void retainsStoredFullTextWithoutLookup() {
        var c = citation(); c.setFullSliceText(excerpt + "快照原文"); resolve(c);
        assertThat(c.getFullSliceText()).endsWith("快照原文"); verifyNoInteractions(docs, slices);
    }
}
