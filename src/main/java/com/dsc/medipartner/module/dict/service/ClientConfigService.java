package com.dsc.medipartner.module.dict.service;

import com.dsc.medipartner.module.dict.domain.vo.ClientConfigVO;

/**
 * 客户端配置下发（契约 §8.3）。
 *
 * <p><b>契约硬要求</b>：读不到配置时返回<b>代码内置默认值</b>，保证小程序任何情况下都能启动
 * （"绝不能因为配置接口失败而白屏"）。因此实现必须对每一项都提供兜底，且单项解析失败
 * 只影响该项、不影响整包。
 */
public interface ClientConfigService {

    ClientConfigVO getClientConfig();
}
