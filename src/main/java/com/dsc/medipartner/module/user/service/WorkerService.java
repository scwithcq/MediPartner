package com.dsc.medipartner.module.user.service;

import com.dsc.medipartner.module.user.domain.entity.WorkerProfile;

public interface WorkerService {

    WorkerProfile getByUserId(Long userId);
}
