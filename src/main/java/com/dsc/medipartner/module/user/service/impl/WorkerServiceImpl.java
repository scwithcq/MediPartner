package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dsc.medipartner.common.exception.BizException;
import com.dsc.medipartner.common.result.ErrorCode;
import com.dsc.medipartner.common.util.SensitiveWords;
import com.dsc.medipartner.module.order.domain.enums.ServiceType;
import com.dsc.medipartner.module.user.domain.dto.UpdateWorkerProfileRequest;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.domain.enums.AuditStatusEnum;
import com.dsc.medipartner.module.user.domain.enums.CreditLevel;
import com.dsc.medipartner.module.user.domain.vo.WorkerProfileVO;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.WorkerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class WorkerServiceImpl implements WorkerService {

    /** 无评价时平均星级兜底（契约 §6.2） */
    private static final BigDecimal DEFAULT_AVG_STAR = new BigDecimal("5.0");
    private static final int DEFAULT_CREDIT = 100;

    private final WorkerProfileMapper workerProfileMapper;

    public WorkerServiceImpl(WorkerProfileMapper workerProfileMapper) {
        this.workerProfileMapper = workerProfileMapper;
    }

    @Override
    public WorkerProfile getByUserId(Long userId) {
        return workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, userId));
    }

    @Override
    public WorkerProfileVO getProfile(Long workerUserId) {
        return toProfileVO(requireProfile(workerUserId));
    }

    @Override
    @Transactional
    public WorkerProfileVO updateProfile(Long workerUserId, UpdateWorkerProfileRequest request) {
        WorkerProfile profile = requireProfile(workerUserId);
        if (AuditStatusEnum.PENDING.name().equals(profile.getAuditStatus())) {
            throw new BizException(ErrorCode.WORKER_AUDIT_NOT_PASS, "审核中，暂不可修改资料");
        }

        LambdaUpdateWrapper<WorkerProfile> update = new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId);
        boolean changed = false;

        if (request.getCity() != null) {
            update.set(WorkerProfile::getCity, request.getCity().trim());
            changed = true;
        }
        if (request.getServiceTypes() != null) {
            if (request.getServiceTypes().isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "serviceTypes 至少 1 项");
            }
            update.set(WorkerProfile::getServiceTypes, joinServiceTypes(request.getServiceTypes()));
            changed = true;
        }
        if (request.getIntro() != null) {
            String intro = request.getIntro().trim();
            String hit = SensitiveWords.firstHit(intro);
            if (hit != null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "intro 含不适当内容：" + hit);
            }
            update.set(WorkerProfile::getIntro, intro);
            changed = true;
        }
        if (request.getHealthCertUrl() != null) {
            update.set(WorkerProfile::getHealthCertUrl, request.getHealthCertUrl().trim());
            changed = true;
        }
        // geo_hash 由阶段 5 的定位上报（§7.4）维护，city 变更不产生坐标，故此处不刷新
        if (changed) {
            workerProfileMapper.update(null, update);
        }
        return toProfileVO(requireProfile(workerUserId));
    }

    @Override
    public void updateReviewStats(Long workerUserId, int reviewCount, int goodCount, BigDecimal avgStar) {
        int goodRate = reviewCount <= 0 ? 100
                : BigDecimal.valueOf(goodCount * 100L)
                .divide(BigDecimal.valueOf(reviewCount), 0, RoundingMode.HALF_UP)
                .intValue();
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .set(WorkerProfile::getReviewCount, Math.max(0, reviewCount))
                .set(WorkerProfile::getGoodRate, Math.max(0, Math.min(100, goodRate)))
                .set(WorkerProfile::getAvgStar, avgStar == null
                        ? DEFAULT_AVG_STAR : avgStar.setScale(1, RoundingMode.HALF_UP)));
    }

    @Override
    public void incrementServiceCount(Long workerUserId) {
        if (workerUserId == null) {
            return;
        }
        workerProfileMapper.update(null, new LambdaUpdateWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId)
                .setSql("service_count = service_count + 1"));
    }

    private WorkerProfile requireProfile(Long workerUserId) {
        WorkerProfile profile = getByUserId(workerUserId);
        if (profile == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return profile;
    }

    private WorkerProfileVO toProfileVO(WorkerProfile profile) {
        WorkerProfileVO vo = new WorkerProfileVO();
        vo.setWorkerUserId(String.valueOf(profile.getUserId()));
        vo.setRealName(profile.getRealName());
        vo.setIdCardMasked(maskIdCard(profile.getIdCardNo()));
        vo.setIdCardVerified(StringUtils.hasText(profile.getIdCardNo()));
        vo.setHealthCertUrl(profile.getHealthCertUrl());
        vo.setAuditStatus(profile.getAuditStatus());
        vo.setAuditRemark(profile.getAuditRemark());
        vo.setAuditTime(profile.getAuditTime());
        int score = profile.getCreditScore() == null ? DEFAULT_CREDIT : profile.getCreditScore();
        vo.setCreditScore(score);
        vo.setCreditLevel(CreditLevel.of(score).name());
        vo.setCity(profile.getCity());
        List<String> types = splitCsv(profile.getServiceTypes());
        vo.setServiceTypes(types);
        vo.setServiceTypeNames(serviceTypeNames(types));
        vo.setIntro(profile.getIntro());
        vo.setCanEdit(!AuditStatusEnum.PENDING.name().equals(profile.getAuditStatus()));
        vo.setJoinedAt(profile.getAuditTime());
        vo.setUpdatedAt(profile.getUpdatedAt());
        vo.setGoodRate(profile.getGoodRate() == null ? 100 : profile.getGoodRate());
        vo.setReviewCount(profile.getReviewCount() == null ? 0 : profile.getReviewCount());
        vo.setServiceCount(profile.getServiceCount() == null ? 0 : profile.getServiceCount());
        vo.setAvgStar(oneDecimal(profile.getAvgStar()));
        return vo;
    }

    static String oneDecimal(BigDecimal value) {
        return (value == null ? DEFAULT_AVG_STAR : value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    static String maskIdCard(String idCardNo) {
        if (!StringUtils.hasText(idCardNo)) {
            return null;
        }
        String value = idCardNo.trim();
        if (value.length() <= 8) {
            return value.charAt(0) + "****";
        }
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    static List<String> splitCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    static List<String> serviceTypeNames(List<String> codes) {
        List<String> names = new ArrayList<>(codes.size());
        for (String code : codes) {
            for (ServiceType type : ServiceType.values()) {
                if (type.name().equals(code)) {
                    names.add(type.getLabel());
                    break;
                }
            }
        }
        return names;
    }

    private String joinServiceTypes(List<String> serviceTypes) {
        List<String> codes = new ArrayList<>(serviceTypes.size());
        for (String raw : serviceTypes) {
            String code = raw == null ? "" : raw.trim();
            if (!ServiceType.isValid(code)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "serviceTypes 含非法值：" + raw);
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return String.join(",", codes);
    }
}
