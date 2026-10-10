package com.dsc.medipartner.module.ticket.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.ticket.domain.dto.CreateAppealRequest;
import com.dsc.medipartner.module.ticket.domain.vo.AppealVO;
import com.dsc.medipartner.module.ticket.domain.vo.CreateAppealVO;
import com.dsc.medipartner.module.ticket.service.AppealService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 申诉工单（契约 §5.14、§5.15）。
 *
 * <p>挂在 {@code /api/order/**} 前缀下（要求 ROLE_USER），与订单接口同域，
 * 前端从订单详情「发起申诉」直接调用。
 */
@RestController
@RequestMapping("/api/order")
public class OrderAppealController {

    private final AppealService appealService;

    public OrderAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    /** §5.14 发起申诉：状态须为 40/50/70，一单一诉 */
    @PostMapping("/{orderNo}/appeal")
    public R<CreateAppealVO> appeal(@PathVariable String orderNo,
                                    @Valid @RequestBody CreateAppealRequest request) {
        return R.ok(appealService.create(UserContext.userId(), orderNo, request));
    }

    /**
     * §5.15 查询申诉进度。
     * <b>未申诉过时返回 {@code code=0} + {@code data} 为空对象 {@code {}}</b>（契约要求，
     * 避免前端把正常态当错误），因此这里显式返回空 Map 而不是 null。
     */
    @GetMapping("/{orderNo}/appeal")
    public R<Object> appealStatus(@PathVariable String orderNo) {
        AppealVO vo = appealService.getByOrderNo(UserContext.userId(), orderNo);
        return R.ok(vo == null ? Map.of() : vo);
    }
}
