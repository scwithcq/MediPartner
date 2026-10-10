package com.dsc.medipartner.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 静态资源映射：/files/** -> ${medi.file.base-dir}（契约 §8.1 备注 5）。
 * 上传接口返回的相对路径以 /files/ 开头，前端拼接服务地址即可访问。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String baseDir;

    public WebMvcConfig(@Value("${medi.file.base-dir:./data/files}") String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(baseDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}
