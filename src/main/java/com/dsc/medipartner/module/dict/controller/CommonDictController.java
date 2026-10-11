package com.dsc.medipartner.module.dict.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;
import com.dsc.medipartner.module.dict.domain.vo.DictVO;
import com.dsc.medipartner.module.dict.domain.vo.VersionVO;
import com.dsc.medipartner.module.dict.service.ClientConfigService;
import com.dsc.medipartner.module.dict.service.DictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序公共能力（契约 §8.2 字典 / §8.3 配置）。
 *
 * <p>这两个接口是<b>前端启动链路</b>：onLaunch 先并行拉配置与字典并缓存，再进业务页面。
 * 因此 §8.3 必须 {@code permitAll}（见 SecurityConfig），§8.2 需登录。
 */
@RestController
@RequestMapping("/api/common")
public class CommonDictController {

    private final DictService dictService;
    private final ClientConfigService clientConfigService;

    public CommonDictController(DictService dictService, ClientConfigService clientConfigService) {
        this.dictService = dictService;
        this.clientConfigService = clientConfigService;
    }

    /**
     * §8.2 字典查询：未知 dictType 返回 1001（不返回空字典，避免前端下拉静默变空）。
     */
    @GetMapping("/dict/{dictType}")
    public R<DictVO> dict(@PathVariable String dictType) {
        return R.ok(dictService.getDict(dictType));
    }

    /**
     * §8.3 客户端配置下发：匿名可访问（登录页也要读）。
     * 读不到配置时返回代码内置默认值，保证小程序任何情况下都能启动。
     */
    @GetMapping("/config")
    public R<ClientConfigVO> config() {
        return R.ok(clientConfigService.getClientConfig());
    }

    /**
     * §8.4 版本与契约兼容检查（阶段 5）：匿名，onLaunch 与 §8.3 并行调用。
     *
     * <p>真正的价值是 {@code apiCompatVersion}：前端发现服务端值变大即清缓存重拉
     * 字典与配置，避免旧缓存渲染新契约（契约 §8.4 备注 1）。
     */
    @GetMapping("/version")
    public R<VersionVO> version() {
        return R.ok(clientConfigService.getVersion());
    }
}
