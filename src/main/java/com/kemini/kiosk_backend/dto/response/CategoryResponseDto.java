package com.kemini.kiosk_backend.dto.response;

import com.kemini.kiosk_backend.domain.entity.MenuCategory;

import lombok.Getter;

@Getter
public class CategoryResponseDto {
    private Long id;
    private String name;

    public CategoryResponseDto(MenuCategory category) {
        this.id = category.getId();
        this.name = category.getName();
    }
}