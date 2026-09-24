package com.dsc.medipartner.module.ai.service;

import java.time.LocalDateTime;

/**
 * 发单助手统一入参（首次生成与重新生成共用），hint 仅重新生成时有值。
 */
public record AssistInput(String symptomDesc,
                          String city,
                          String hospitalPref,
                          LocalDateTime serviceTime,
                          String serviceType,
                          String hint) {
}
