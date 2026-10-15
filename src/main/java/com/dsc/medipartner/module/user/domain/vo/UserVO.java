package com.dsc.medipartner.module.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户信息统一出参（契约 §2.1，全文档统一）。
 *
 * <p>阶段 1.5 由裸 {@code SysUser} 实体改造而来（附录 H-5）：修正 {@code phone} 明文外泄、
 * 去掉 {@code status} 之外的内部分字段，并补 {@code roles}。
 */
@Data
public class UserVO {

    /** 用户 ID（雪花，JSON 里序列化为字符串） */
    private Long id;
    private Boolean passwordSet;
    private String nickname;
    private String avatar;
    /** <b>脱敏后</b>手机号 138****1234（§1.11） */
    private String phone;
    /** 1 用户 / 2 陪诊师 / 4 管理员，可叠加（陪诊师为 3） */
    private Integer roleMask;
    /** 角色名数组，前端免位运算，如 ["USER","WORKER"] */
    private List<String> roles;
    /** 1 正常 / 0 禁用 */
    private Integer status;
    /** 入驻审核状态：PENDING/PASS/REJECT；仅 roleMask 含 2 或已申请时有值 */
    private String workerAuditStatus;
    private LocalDateTime createdAt;
}
