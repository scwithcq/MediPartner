package com.dsc.medipartner.module.dict.service.impl;

import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.dict.domain.entity.SysDict;
import com.dsc.medipartner.module.dict.domain.vo.DictVO;
import com.dsc.medipartner.module.dict.mapper.SysDictMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DictService unit tests (contract §8.2).
 * An unknown dictType must fail loudly with 1001 rather than silently returning an empty dict.
 */
class DictServiceImplTest {

    private SysDictMapper dictMapper;
    private DictServiceImpl service;

    @BeforeEach
    void setUp() {
        dictMapper = mock(SysDictMapper.class);
        service = new DictServiceImpl(dictMapper, new ObjectMapper());
    }

    private SysDict item(String type, String value, String label, int sort, String extra,
                         LocalDateTime updatedAt) {
        SysDict dict = new SysDict();
        dict.setDictType(type);
        dict.setItemValue(value);
        dict.setItemLabel(label);
        dict.setSort(sort);
        dict.setEnabled(1);
        dict.setExtra(extra);
        dict.setUpdatedAt(updatedAt);
        return dict;
    }

    @Test
    void returnsItemsWithParsedExtra() {
        when(dictMapper.selectList(any())).thenReturn(List.of(
                item("SERVICE_TYPE", "ACCOMPANY", "全程陪诊", 1,
                        "{\"basePrice\":\"200.00\",\"unit\":\"小时\"}",
                        LocalDateTime.of(2026, 9, 21, 10, 0))));

        DictVO vo = service.getDict("SERVICE_TYPE");

        assertThat(vo.getDictType()).isEqualTo("SERVICE_TYPE");
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getValue()).isEqualTo("ACCOMPANY");
        assertThat(vo.getItems().get(0).getLabel()).isEqualTo("全程陪诊");
        assertThat(vo.getItems().get(0).getExtra()).containsEntry("basePrice", "200.00");
    }

    /** Version is the MAX updatedAt, so editing any item busts the client cache. */
    @Test
    void versionIsMaxUpdatedAtMillis() {
        LocalDateTime earlier = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime later = LocalDateTime.of(2026, 9, 21, 12, 0);
        when(dictMapper.selectList(any())).thenReturn(List.of(
                item("CITY", "A", "城市A", 1, null, later),
                item("CITY", "B", "城市B", 2, null, earlier)));

        DictVO vo = service.getDict("CITY");

        long expected = later.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        assertThat(vo.getVersion()).isEqualTo(String.valueOf(expected));
    }

    /** Empty dict is valid (e.g. no cities configured yet); version falls back to "0". */
    @Test
    void emptyDictReturnsZeroVersion() {
        when(dictMapper.selectList(any())).thenReturn(List.of());

        DictVO vo = service.getDict("CITY");

        assertThat(vo.getItems()).isEmpty();
        assertThat(vo.getVersion()).isEqualTo("0");
    }

    @Test
    void unknownDictTypeThrows1001() {
        assertThatThrownBy(() -> service.getDict("NOT_A_DICT"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    @Test
    void nullDictTypeThrows1001() {
        assertThatThrownBy(() -> service.getDict(null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }

    /** dictType lookup is case-insensitive; the response echoes the canonical name. */
    @Test
    void dictTypeLookupIsCaseInsensitive() {
        when(dictMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.getDict("review_tag").getDictType()).isEqualTo("REVIEW_TAG");
    }

    /** Malformed extra JSON must not break the whole dictionary (it is an optional enhancement). */
    @Test
    void malformedExtraIsIgnoredNotFatal() {
        when(dictMapper.selectList(any())).thenReturn(List.of(
                item("CITY", "A", "城市A", 1, "{not-json", LocalDateTime.now())));

        DictVO vo = service.getDict("CITY");

        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getExtra()).isNull();
        assertThat(vo.getItems().get(0).getLabel()).isEqualTo("城市A");
    }

    /** Every contract dictType must be accepted. */
    @Test
    void allContractDictTypesAreAccepted() {
        when(dictMapper.selectList(any())).thenReturn(List.of());
        for (String type : List.of("CITY", "SERVICE_TYPE", "CANCEL_REASON",
                "ABNORMAL_TYPE", "REVIEW_TAG", "CREDIT_RULE")) {
            assertThat(service.getDict(type).getDictType()).isEqualTo(type);
        }
    }
}
