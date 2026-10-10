package com.dsc.medipartner.module.dict.domain.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字典查询结果（契约 §8.2）。
 */
@Data
public class DictVO {

    /** 回显请求的字典类型 */
    private String dictType;
    /** 按 sort asc 排列；enabled=false 的不下发 */
    private List<Item> items;
    /** 字典版本号：该字典最大 updatedAt 的毫秒时间戳，供前端缓存比对 */
    private String version;

    @Data
    public static class Item {
        private String value;
        private String label;
        private Integer sort;
        private Boolean enabled;
        /** 扩展字段，按 dictType 约定键（§8.2） */
        private Map<String, Object> extra;
    }
}
