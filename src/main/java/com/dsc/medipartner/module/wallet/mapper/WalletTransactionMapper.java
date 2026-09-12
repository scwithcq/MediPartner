package com.dsc.medipartner.module.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsc.medipartner.module.wallet.domain.entity.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletTransactionMapper extends BaseMapper<WalletTransaction> {
}
