package com.dsc.medipartner.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;
import com.dsc.medipartner.module.user.mapper.WorkerProfileMapper;
import com.dsc.medipartner.module.user.service.WorkerService;
import org.springframework.stereotype.Service;

@Service
public class WorkerServiceImpl implements WorkerService {

    private final WorkerProfileMapper workerProfileMapper;

    public WorkerServiceImpl(WorkerProfileMapper workerProfileMapper) {
        this.workerProfileMapper = workerProfileMapper;
    }

    @Override
    public WorkerProfile getByUserId(Long userId) {
        return workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, userId));
    }
}
