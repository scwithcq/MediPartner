package com.dsc.medipartner.module.demand.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.result.PageQuery;
import com.dsc.medipartner.common.result.PageResult;
import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.demand.domain.dto.CloseDemandRequest;
import com.dsc.medipartner.module.demand.domain.vo.DemandVO;
import com.dsc.medipartner.module.demand.service.DemandService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 需求工单接口（接口文档 §4.3 列表 / §4.4 详情 / §4.5 关闭）。
 */
@RestController
@RequestMapping("/api/demand")
public class DemandController {

    private final DemandService demandService;

    public DemandController(DemandService demandService) {
        this.demandService = demandService;
    }

    @GetMapping("/list")
    public R<PageResult<DemandVO>> list(@RequestParam(required = false) String status,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedSince,
                                        PageQuery query) {
        IPage<DemandVO> page = demandService.pageMine(UserContext.userId(), status, updatedSince,
                query.getPageNum(), query.getPageSize());
        return R.ok(PageResult.of(page));
    }

    @GetMapping("/{demandId}")
    public R<DemandVO> detail(@PathVariable String demandId) {
        return R.ok(demandService.detail(UserContext.userId(), parseId(demandId)));
    }

    @PostMapping("/{demandId}/close")
    public R<Void> close(@PathVariable String demandId,
                         @Valid @RequestBody(required = false) CloseDemandRequest request) {
        demandService.close(UserContext.userId(), parseId(demandId), request == null ? null : request.getReason());
        return R.ok();
    }

    private Long parseId(String demandId) {
        try {
            return Long.parseLong(demandId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
    }
}
