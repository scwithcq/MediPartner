package com.dsc.medipartner.module.user.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.UpdateWorkerProfileRequest;
import com.dsc.medipartner.module.user.domain.vo.CreditVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerProfileVO;
import com.dsc.medipartner.module.user.service.CreditService;
import com.dsc.medipartner.module.user.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 陪诊师本人的资料与信用（契约 §7.2 / §7.3 / §7.16）。workerUserId 一律取自登录态，
 * 不接受前端传入，避免越权查看他人资料。
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerProfileController {

    private final WorkerService workerService;
    private final CreditService creditService;

    public WorkerProfileController(WorkerService workerService, CreditService creditService) {
        this.workerService = workerService;
        this.creditService = creditService;
    }

    /** §7.1 工作台首页聚合：一次请求返回首页全部数据，避免弱网下首屏发四五个请求 */
    @GetMapping("/overview")
    public R<WorkerOverviewVO> overview() {
        return R.ok(workerService.getOverview(UserContext.userId()));
    }

    /** §7.2 我的陪诊师资料 */
    @GetMapping("/profile")
    public R<WorkerProfileVO> profile() {
        return R.ok(workerService.getProfile(UserContext.userId()));
    }

    /** §7.3 修改接单资料：PENDING 期间 2003，intro 命中敏感词 1001 */
    @PutMapping("/profile")
    public R<WorkerProfileVO> updateProfile(@Valid @RequestBody UpdateWorkerProfileRequest request) {
        return R.ok(workerService.updateProfile(UserContext.userId(), request));
    }

    /** §7.16 信用中心：等级 / 上限 / 30 天曲线 / 最近流水 / 规则 */
    @GetMapping("/credit")
    public R<CreditVO> credit() {
        return R.ok(creditService.getCreditCenter(UserContext.userId()));
    }
}