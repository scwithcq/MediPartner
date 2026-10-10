package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开始服务（契约 §7.11）。
 *
 * <p>阶段 1.5 补 GPS 与到场信息：定位写入首条留痕与流转日志的 remark，
 * 用于「开始服务位置偏离」的风控标记（§7.11 备注：距离过远<b>不硬拦</b>，只记日志交管理端事后审查，
 * 因为医院地下停车场定位漂移很常见，硬拦会误伤正常陪诊师）。
 */
@Data
public class StartServiceRequest {

    @NotBlank(message = "gpsLat 不能为空")
    @Pattern(regexp = "^-?\\d{1,3}(\\.\\d{1,7})?$", message = "gpsLat 格式不正确")
    private String gpsLat;

    @NotBlank(message = "gpsLng 不能为空")
    @Pattern(regexp = "^-?\\d{1,3}(\\.\\d{1,7})?$", message = "gpsLng 格式不正确")
    private String gpsLng;

    /** 逆地理编码得到的地址描述 */
    @Size(max = 255, message = "address 长度不能超过 255")
    private String address;

    @Size(max = 200, message = "remark 长度不能超过 200")
    private String remark;

    /** 到场照片相对路径（bizType=ORDER_TRACE，走 §8.1 上传） */
    @Size(max = 255, message = "imageUrl 长度不能超过 255")
    private String imageUrl;

    /** 照片水印时间，与服务器偏差 > 5 分钟返回 8004 */
    private String watermarkTime;
}
