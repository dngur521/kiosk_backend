package com.kemini.kiosk_backend.config;

import java.io.File;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 경로에 대해
                .allowedOrigins("http://localhost:5173", "https://kemini-kiosk-frontend.duckdns.org") // 허용할 도메인 (Vite 기본 포트 포함)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 허용할 HTTP 메서드
                .allowedHeaders("*") // 모든 헤더 허용
                .allowCredentials(true) // 쿠키나 인증 정보를 포함할지 여부
                .maxAge(3600); // 프리플라이트(Preflight) 요청 결과 캐싱 시간
    }
}