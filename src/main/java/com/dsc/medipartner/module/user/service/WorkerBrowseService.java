package com.dsc.medipartner.module.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsc.medipartner.module.user.domain.dto.WorkerQuery;
import com.dsc.medipartner.module.user.domain.vo.WorkerCardVO;
import com.dsc.medipartner.module.user.domain.vo.WorkerDetailVO;

/**
 * Worker browse (contract 6.1 list / 6.2 detail).
 * Read-only projection of worker_profile JOIN sys_user; star distribution and recent
 * reviews are delegated to ReviewService so statistics are computed in one place.
 * The list only returns bookable workers; detail returns 2003 for unapproved/disabled ones.
 */
public interface WorkerBrowseService {

    /** 6.1 worker list: filter by city / serviceType / keyword / goodRate / creditScore and sort */
    IPage<WorkerCardVO> pageWorkers(WorkerQuery query);

    /** 6.2 worker detail: 2001 when missing, 2003 when not approved or disabled */
    WorkerDetailVO getDetail(Long workerUserId);
}