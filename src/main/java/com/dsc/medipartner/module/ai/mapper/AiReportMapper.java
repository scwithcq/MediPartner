package com.dsc.medipartner.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsc.medipartner.module.ai.domain.entity.AiReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 报告持久化（契约 V10 ai_report 表）。一单一报告，按 order_no 唯一。
 */
@Mapper
public interface AiReportMapper extends BaseMapper<AiReport> {
}
