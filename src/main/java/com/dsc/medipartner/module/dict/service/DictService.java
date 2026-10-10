package com.dsc.medipartner.module.dict.service;

import com.dsc.medipartner.module.dict.domain.vo.DictVO;

/**
 * 字典查询（契约 §8.2）。
 * 未知 dictType 抛 1001；未知类型在实现里由枚举白名单拦截。
 */
public interface DictService {

    /**
     * 按类型查询字典项。
     *
     * @param dictType 见 {@code DictType} 枚举
     * @return 条目按 sort 升序、只含 enabled=1；version 取最大 updatedAt 毫秒时间戳
     * @throws com.dsc.medipartner.common.exception.BizException 1001 未知 dictType
     */
    DictVO getDict(String dictType);
}
