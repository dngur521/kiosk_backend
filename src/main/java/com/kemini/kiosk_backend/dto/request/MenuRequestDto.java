package com.kemini.kiosk_backend.dto.request;

import org.springframework.web.multipart.MultipartFile;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class MenuRequestDto {
    private String name;
    private Integer price;
    private String description;
    private Long categoryId;
    private MultipartFile imageFile; // 실제 파일 데이터
    private String semanticContext;
}