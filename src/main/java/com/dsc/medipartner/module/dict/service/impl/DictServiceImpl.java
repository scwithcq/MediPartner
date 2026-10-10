package com.dsc.medipartner.module.dict.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.dict.domain.entity.SysDict;
import com.dsc.medipartner.module.dict.domain.enums.DictType;
import com.dsc.medipartner.module.dict.domain.vo.DictVO;
import com.dsc.medipartner.module.dict.mapper.SysDictMapper;
import com.dsc.medipartner.module.dict.service.DictService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典查询实现（契约 §8.2）。
 *
 * <p>版本号取该字典最大 {@code updatedAt} 的毫秒时间戳，前端据此判断是否需要刷新缓存。
 * 注意用<b>最大</b>而非最新一条：某条被改过但排序靠前时，仍必须触发前端刷新。
 */
@Slf4j
@Service
public class DictServiceImpl implements DictService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SysDictMapper dictMapper;
    private final ObjectMapper objectMapper;

    public DictServiceImpl(SysDictMapper dictMapper, ObjectMapper objectMapper) {
        this.dictMapper = dictMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public DictVO getDict(String dictType) {
        DictType type = DictType.of(dictType);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未知的字典类型：" + dictType);
        }

        List<SysDict> rows = dictMapper.selectList(new LambdaQueryWrapper<SysDict>()
                .eq(SysDict::getDictType, type.name())
                .eq(SysDict::getEnabled, 1)
                .orderByAsc(SysDict::getSort)
                .orderByAsc(SysDict::getId));

        DictVO vo = new DictVO();
        vo.setDictType(type.name());
        List<DictVO.Item> items = new ArrayList<>(rows.size());
        long maxUpdatedAt = 0L;
        for (SysDict row : rows) {
            DictVO.Item item = new DictVO.Item();
            item.setValue(row.getItemValue());
            item.setLabel(row.getItemLabel());
            item.setSort(row.getSort());
            item.setEnabled(true);
            item.setExtra(parseExtra(row.getExtra()));
            items.add(item);
            if (row.getUpdatedAt() != null) {
                long millis = row.getUpdatedAt().atZone(ZONE).toInstant().toEpochMilli();
                maxUpdatedAt = Math.max(maxUpdatedAt, millis);
            }
        }
        vo.setItems(items);
        vo.setVersion(String.valueOf(maxUpdatedAt));
        return vo;
    }

    /** extra 存 JSON 字符串；解析失败不阻断字典下发（该字段是可选增强） */
    private Map<String, Object> parseExtra(String extra) {
        if (!StringUtils.hasText(extra)) {
            return null;
        }
        try {
            return objectMapper.readValue(extra, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[dict] extra JSON 解析失败，已忽略: {}", extra);
            return null;
        }
    }
}
