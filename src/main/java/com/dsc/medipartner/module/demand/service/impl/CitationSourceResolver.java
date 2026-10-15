package com.dsc.medipartner.module.demand.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.module.demand.domain.vo.DepartmentVO;
import com.dsc.medipartner.module.knowledge.domain.entity.KnowledgeSlice;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeDocMapper;
import com.dsc.medipartner.module.knowledge.mapper.KnowledgeSliceMapper;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 旧引用只存 200 字：只从同一已发布文档中唯一匹配原摘录，不猜测引用来源。 */
@Component
public class CitationSourceResolver {
    private final KnowledgeDocMapper docs;
    private final KnowledgeSliceMapper slices;
    public CitationSourceResolver(KnowledgeDocMapper docs, KnowledgeSliceMapper slices) {
        this.docs = docs;
        this.slices = slices;
    }
    public void fillHistoricalSources(List<DepartmentVO> departments) {
        if (departments == null) return;
        Map<Long, List<KnowledgeSlice>> cache = new HashMap<>();
        for (var dept : departments) {
            if (dept.getCitations() == null) continue;
            for (var citation : dept.getCitations()) {
                if (citation.getFullSliceText() != null || citation.getSliceText() == null) continue;
                long docId;
                try { docId = Long.parseLong(citation.getDocId()); }
                catch (NumberFormatException e) { continue; }
                List<KnowledgeSlice> candidates = cache.computeIfAbsent(docId, id -> {
                    var doc = docs.selectById(id);
                    if (doc == null || !"ACTIVE".equals(doc.getStatus())) return List.of();
                    return slices.selectList(new LambdaQueryWrapper<KnowledgeSlice>()
                        .eq(KnowledgeSlice::getDocId, id).eq(KnowledgeSlice::getStatus, "ACTIVE"));
                });
                var matches = candidates.stream().filter(slice -> slice.getContent() != null &&
                    !citation.getSliceText().isBlank() && slice.getContent().startsWith(citation.getSliceText()))
                    .map(KnowledgeSlice::getContent).distinct().toList();
                if (matches.size() == 1) citation.setFullSliceText(matches.get(0));
            }
        }
    }
}
