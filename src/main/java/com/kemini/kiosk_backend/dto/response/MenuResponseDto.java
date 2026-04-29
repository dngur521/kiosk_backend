package com.kemini.kiosk_backend.dto.response;

import com.kemini.kiosk_backend.domain.entity.Menu;

import lombok.Getter;

@Getter
public class MenuResponseDto {
    private Long id;
    private String name;
    private Integer price;
    private String description;
    private String imageUrl; // 프론트엔드가 사용할 풀 경로
    private String categoryName;
    private String semanticContext;

    public MenuResponseDto(Menu menu, String baseUrl) {
        this.id = menu.getId();
        this.name = menu.getName();
        this.price = menu.getPrice();
        this.description = menu.getDescription();
        this.categoryName = menu.getCategory().getName();
        this.imageUrl = baseUrl + "/uploads/menu/" + menu.getImageName(); // 이미지 경로 조립
        this.semanticContext = menu.getSemanticContext();
    }
}