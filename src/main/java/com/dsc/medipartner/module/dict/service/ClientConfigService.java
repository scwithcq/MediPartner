package com.dsc.medipartner.module.dict.service;

import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;
import com.dsc.medipartner.module.dict.domain.vo.VersionVO;

/**
 * 客户端配置下发（契约 §8.3）。
 *
 * <p><b>契约硬要求</b>：读不到配置时返回<b>代码内置默认值</b>，保证小程序任何情况下都能启动
 * （"绝不能因为配置接口失败而白屏"）。因此实现必须对每一项都提供兜底，且单项解析失败
 * 只影响该项、不影响整包。
 */
public interface ClientConfigService {

    ClientConfigVO getClientConfig();

    /**
     * §8.4 版本与契约兼容检查（阶段 5）。
     *
     * <p>同样遵循「读不到就用内置默认值」的降级原则：本接口在 {@code onLaunch} 与 §8.3
     * 并行调用，一旦失败会阻断启动，因此绝不能因为配置缺失而报错。
     */
    VersionVO getVersion();
}
