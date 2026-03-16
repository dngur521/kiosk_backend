package com.kemini.kiosk_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CategoryRequestDto {
    private String name; // 카테고리 이름 (예: 커피, 디저트)
}