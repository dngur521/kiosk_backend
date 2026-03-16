package com.kemini.kiosk_backend.config;

import java.io.File;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 사용자의 홈 디렉토리 경로 추출 (예: /home/hyeok)
        String homeDir = System.getProperty("user.home");
        // 실제 파일이 저장될 절대 경로 조립
        String uploadPath = homeDir + File.separator + "kiosk_uploads" + File.separator + "menu" + File.separator;

        // /uploads/menu/** 주소로 들어오면 홈 디렉토리의 kiosk_uploads 폴더를 보여줌
        registry.addResourceHandler("/uploads/menu/**")
                .addResourceLocations("file:" + uploadPath);
    }
}