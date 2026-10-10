package com.dsc.medipartner.module.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.order.domain.dto.FinishOrderRequest;
import com.dsc.medipartner.module.order.domain.dto.UploadTraceRequest;
import com.dsc.medipartner.module.order.domain.entity.OrderTrace;
import com.dsc.medipartner.module.order.domain.vo.TraceVO;

import java.util.List;

/**
 * 服务留痕（契约 §5.9 / §7.12 / §7.13）：TRACE 过程留痕与 VOUCHER 结束凭证同存 order_trace。
 * 归属校验口径：3001 订单不存在、3008 非本人接的单、3002 非服务中状态、8004 水印时间异常。
 */
public interface TraceService {

    /** §7.12 上传过程留痕：仅 IN_SERVICE(30)，每单 TRACE 上限 20 条 */
    TraceVO uploadTrace(Long workerId, String orderNo, UploadTraceRequest request);

    /** §7.14 结束服务时落 1~3 张凭证（在 finish 事务内调用，水印校验失败整体回滚） */
    void saveVouchers(Long workerId, String orderNo, FinishOrderRequest request);

    /** §5.9 / §7.13 留痕分页，createdAt 倒序（归属校验由调用方完成） */
    IPage<TraceVO> pageTraces(String orderNo, long pageNum, long pageSize);

    /** 全量留痕按 seq 升序（AI 报告还原服务时间轴用） */
    List<OrderTrace> listByOrder(String orderNo);
}
