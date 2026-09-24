package com.dsc.medipartner.module.ai.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.common.security.UserContext;
import com.dsc.medipartner.module.ai.domain.dto.AiAssistRequest;
import com.dsc.medipartner.module.ai.domain.dto.AssistRegenerateRequest;
import com.dsc.medipartner.module.ai.domain.vo.AiAssistVO;
import com.dsc.medipartner.module.ai.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力接口（接口文档 §4.1 生成推荐 / §4.2 重新生成），需 USER 角色登录。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/assist")
    public R<AiAssistVO> assist(@Valid @RequestBody AiAssistRequest request) {
        return R.ok(aiService.assist(UserContext.userId(), request));
    }

    @PostMapping("/assist/regenerate")
    public R<AiAssistVO> regenerate(@Valid @RequestBody AssistRegenerateRequest request) {
        return R.ok(aiService.regenerate(UserContext.userId(), request));
    }
}
