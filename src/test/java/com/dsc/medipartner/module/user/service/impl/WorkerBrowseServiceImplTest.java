package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.module.review.domain.vo.PublicReviewVO;
import com.dsc.medipartner.module.review.service.ReviewService;
import com.dsc.medipartner.module.user.domain.dto.WorkerQuery;
import com.dsc.medipartner.module.user.domain.row.WorkerBrowseRow;
import com.dsc.medipartner.module.user.domain.vo.WorkerCardVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerDetailVO;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorkerBrowseServiceImpl 纯单测：详情 2001/2003 闸门、脱敏与徽章组装、列表 intro 截断。
 */
class WorkerBrowseServiceImplTest {

    private static final long WORKER_ID = 7L;

    private WorkerProfileMapper workerProfileMapper;
    private ReviewService reviewService;
    private WorkerBrowseServiceImpl service;

    @BeforeEach
    void setUp() {
        workerProfileMapper = mock(WorkerProfileMapper.class);
        reviewService = mock(ReviewService.class);
        service = new WorkerBrowseServiceImpl(workerProfileMapper, reviewService);
    }

    private WorkerBrowseRow row() {
        WorkerBrowseRow row = new WorkerBrowseRow();
        row.setUserId(WORKER_ID);
        row.setNickname("张护师");
        row.setAvatar("/files/AVATAR/w.png");
        row.setRealName("张三");
        row.setHealthCertUrl("/files/HEALTH_CERT/c.jpg");
        row.setAuditStatus("PASS");
        row.setCreditScore(95);
        row.setCity("重庆");
        row.setServiceTypes("ACCOMPANY,HALF_DAY");
        row.setIntro("从业八年，擅长心血管内科陪诊，耐心细致，全程陪同就诊并协助沟通医患。");
        row.setGoodRate(98);
        row.setReviewCount(10);
        row.setServiceCount(120);
        row.setAvgStar(new BigDecimal("4.9"));
        row.setIdCardVerified(true);
        row.setStatus(1);
        return row;
    }

    @Test
    void detailMissingThrows2001() {
        when(workerProfileMapper.browseDetail(WORKER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getDetail(WORKER_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void detailUnapprovedThrows2003() {
        WorkerBrowseRow row = row();
        row.setAuditStatus("PENDING");
        when(workerProfileMapper.browseDetail(WORKER_ID)).thenReturn(row);
        assertThatThrownBy(() -> service.getDetail(WORKER_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKER_AUDIT_NOT_PASS));
    }

    @Test
    void detailDisabledThrows2003() {
        WorkerBrowseRow row = row();
        row.setStatus(0);
        when(workerProfileMapper.browseDetail(WORKER_ID)).thenReturn(row);
        assertThatThrownBy(() -> service.getDetail(WORKER_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKER_AUDIT_NOT_PASS));
    }

    @Test
    void detailBuildsFullVo() {
        when(workerProfileMapper.browseDetail(WORKER_ID)).thenReturn(row());
        when(reviewService.starDistribution(WORKER_ID)).thenReturn(Map.of("5", 8, "4", 2));
        when(reviewService.recentPublicReviews(anyLong(), anyInt())).thenReturn(List.of(new PublicReviewVO()));

        WorkerDetailVO vo = service.getDetail(WORKER_ID);

        assertThat(vo.getRealName()).isEqualTo("张某某");
        assertThat(vo.getCreditLevel()).isEqualTo("EXCELLENT");
        assertThat(vo.getAvgStar()).isEqualTo("4.9");
        assertThat(vo.getServiceCities()).containsExactly("重庆");
        assertThat(vo.getCertVerified()).isTrue();
        assertThat(vo.getIdCardVerified()).isTrue();
        assertThat(vo.getBadges()).contains("REAL_NAME", "HEALTH_CERT", "TOP_RATED");
        assertThat(vo.getTags()).contains("实名认证", "健康证已验");
        assertThat(vo.getServiceTypeNames()).containsExactly("全程陪诊", "半日陪护");
        assertThat(vo.getAuditStatus()).isEqualTo("PASS");
        assertThat(vo.getIntro()).contains("从业八年");
    }

    @Test
    void listTruncatesIntroTo60() {
        WorkerBrowseRow row = row();
        row.setIntro("a".repeat(100));
        Page<WorkerBrowseRow> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(workerProfileMapper.browse(any(), any())).thenReturn(page);

        var result = service.pageWorkers(new WorkerQuery());

        assertThat(result.getRecords()).hasSize(1);
        WorkerCardVO card = result.getRecords().get(0);
        assertThat(card.getIntro()).hasSize(60);
        assertThat(card.getRealName()).isEqualTo("张某某");
        assertThat(card.getAccepting()).isTrue();
    }
}