package com.dsc.medipartner.module.order.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上传过程留痕（契约 §7.12）：图片先走 §8.1 上传（bizType=ORDER_TRACE）拿相对路径，
 * 水印时间由小程序 canvas 合成，服务端按 ±5 分钟窗口校验（8004）。
 */
@Data
public class UploadTraceRequest {

    @NotBlank(message = "imageUrl 不能为空")
    @Size(max = 255, message = "imageUrl 长度不能超过 255")
    private String imageUrl;

    @NotBlank(message = "gpsLat 不能为空")
    private String gpsLat;

    @NotBlank(message = "gpsLng 不能为空")
    private String gpsLng;

    @NotBlank(message = "watermarkTime 不能为空")
    private String watermarkTime;

    @Size(max = 255, message = "address 长度不能超过 255")
    private String address;

    @Size(max = 200, message = "remark 长度不能超过 200")
    private String remark;

    /** 服务节点：ARRIVED/REGISTER/WAITING/VISIT/PHARMACY/OTHER，可空 */
    private String node;
}
