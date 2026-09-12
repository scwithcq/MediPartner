package com.dsc.medipartner.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsc.medipartner.module.order.domain.entity.ServiceOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ServiceOrderMapper extends BaseMapper<ServiceOrder> {
}
