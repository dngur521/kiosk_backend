package com.kemini.kiosk_backend.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LearningRequestDto {
    private Long menuId;
    private String text; // 예: "아메리칸 애"
}