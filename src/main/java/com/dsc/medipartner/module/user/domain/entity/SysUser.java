package com.dsc.medipartner.module.user.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsc.medipartner.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    @JsonIgnore
    private String openid;

    private String phone;
    private String nickname;
    private String avatar;
    private Integer roleMask;
    private Integer status;
}
