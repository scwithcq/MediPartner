package com.dsc.medipartner.module.user.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 陪诊师入驻申请（契约 §3.4）。
 * 落库到 {@code worker_profile}（{@code audit_status=PENDING}）；
 * 审核通过后由管理端追加 WORKER 角色位并初始化信用分 100（§9.10，阶段 7）。
 */
@Data
public class WorkerApplyRequest {

    @NotBlank(message = "真实姓名不能为空")
    @Size(min = 2, max = 32, message = "真实姓名长度需在 2~32 之间")
    private String realName;

    /** 身份证号：入库后永不返回（实体上 @JsonIgnore） */
    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
    private String idCardNo;

    @NotBlank(message = "联系手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    /** 身份证人像面（bizType=ID_CARD），走 §8.1 上传 */
    @NotBlank(message = "身份证人像面不能为空")
    @Size(max = 255)
    private String idCardFrontUrl;

    @NotBlank(message = "身份证国徽面不能为空")
    @Size(max = 255)
    private String idCardBackUrl;

    /** 健康证/承诺书（bizType=HEALTH_CERT） */
    @NotBlank(message = "健康证/承诺书不能为空")
    @Size(max = 255)
    private String healthCertUrl;

    @NotBlank(message = "常驻服务城市不能为空")
    @Size(max = 64)
    private String city;

    /** 可提供的服务类型（附录 B.6 ServiceType），1~5 个 */
    @NotEmpty(message = "至少选择一个服务类型")
    @Size(max = 5, message = "服务类型最多 5 个")
    private List<String> serviceTypes;

    @Size(max = 500, message = "个人简介长度不能超过 500")
    private String intro;
}
