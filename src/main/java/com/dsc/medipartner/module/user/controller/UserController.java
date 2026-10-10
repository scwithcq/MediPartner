package com.dsc.medipartner.module.user.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.user.domain.dto.UpdateProfileRequest;
import com.dsc.medipartner.module.user.domain.dto.WorkerApplyRequest;
import com.dsc.medipartner.module.user.domain.vo.UserOverviewVO;
import com.dsc.medipartner.module.user.domain.vo.UserVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerApplyStatusVO;
import com.dsc.medipartner.module.user.service.UserService;
import com.dsc.medipartner.module.user.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final WorkerService workerService;

    public UserController(UserService userService, WorkerService workerService) {
        this.userService = userService;
        this.workerService = workerService;
    }

    /** §3.1 我的资料：返回 UserVO，手机号脱敏（附录 H-5 整改，不再裸出 SysUser 实体） */
    @GetMapping("/profile")
    public R<UserVO> profile() {
        return R.ok(userService.getProfile(UserContext.userId()));
    }

    /** §3.3 「我的」页聚合：一次请求渲染整页，避免首屏 4~5 个并发请求 */
    @GetMapping("/overview")
    public R<UserOverviewVO> overview() {
        return R.ok(userService.getOverview(UserContext.userId()));
    }

    /** §3.4 陪诊师入驻申请：PASS 后重复提交 1003，PENDING 期间重复提交 5002 */
    @PostMapping("/worker/apply")
    public R<Map<String, Object>> workerApply(@Valid @RequestBody WorkerApplyRequest request) {
        return R.ok(workerService.apply(UserContext.userId(), request));
    }

    /**
     * §3.5 查询入驻申请状态。
     * <b>从未申请过时返回 {@code code=0} + {@code data} 为空对象 {@code {}}</b>（契约要求，
     * 避免提交后的轮询页把正常态当错误）。
     */
    @GetMapping("/worker/apply/status")
    public R<Object> workerApplyStatus() {
        WorkerApplyStatusVO vo = workerService.getApplyStatus(UserContext.userId());
        return R.ok(vo == null ? Map.of() : vo);
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
        userService.updateProfile(UserContext.userId(), request.getNickname(), request.getAvatar());
        return R.ok();
    }
}
