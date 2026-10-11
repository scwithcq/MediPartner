package com.dsc.medipartner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 阶段 5 起开启定时任务（§7.2）：24h 未接单自动取消、48h 未确认自动完成、
 * 12h 未结束标异常、留痕超时告警。全部复用状态机已有事件，不新增流转逻辑。
 */
@EnableScheduling
@SpringBootApplication
public class MediPartnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediPartnerApplication.class, args);
    }
}
