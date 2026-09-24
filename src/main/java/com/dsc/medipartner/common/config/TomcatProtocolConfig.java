package com.dsc.medipartner.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * 内嵌 Tomcat 协议选择。
 *
 * 背景：JDK 21 在 Windows 上把 NIO Selector 的唤醒管道改用 AF_UNIX（Unix domain socket）环回连接，
 * 而部分 Windows 机器（多因安全软件/EDR 挂钩 Winsock）AF_UNIX 的 connect() 会返回 EINVAL，
 * 导致 Selector.open() 抛 "Unable to establish loopback connection"，Tomcat NIO Poller 无法启动。
 *
 * 规避：改用 Http11Nio2Protocol（基于 Windows IOCP 的异步 I/O，不依赖 Selector 环回管道）。
 * 仅在 Windows 且未显式配置时自动启用；Linux/生产保持平台默认 NIO；
 * 也可用 medi.tomcat.protocol 显式覆盖（留空则完全交给平台默认）。
 */
@Configuration
public class TomcatProtocolConfig {

    /** Http11Nio2Protocol：基于 IOCP 的异步连接器，绕开 AF_UNIX Selector 环回 */
    static final String NIO2_PROTOCOL = "org.apache.coyote.http11.Http11Nio2Protocol";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatProtocolCustomizer(
            @Value("${medi.tomcat.protocol:}") String configuredProtocol) {
        return factory -> {
            String protocol = configuredProtocol;
            if (protocol == null || protocol.isBlank()) {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("windows")) {
                    protocol = NIO2_PROTOCOL;
                }
            }
            if (protocol != null && !protocol.isBlank()) {
                factory.setProtocol(protocol);
            }
        };
    }
}
