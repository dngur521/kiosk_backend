package com.kemini.kiosk_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.request.CategoryRequestDto;
import com.kemini.kiosk_backend.dto.response.CategoryResponseDto;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.CategoryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    // 1. 카테고리 생성 (CREATE)
    @PostMapping
    public ApiResponse<CategoryResponseDto> createCategory(@RequestBody CategoryRequestDto dto) {
        return ApiResponse.success("카테고리가 생성되었습니다.", categoryService.saveCategory(dto));
    }

    // 2. 카테고리 목록 조회 (READ)
    @GetMapping
    public ApiResponse<List<CategoryResponseDto>> getCategories() {
        return ApiResponse.success(categoryService.getAllCategories());
    }

    // 3. 카테고리 삭제 (DELETE)
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ApiResponse.success("카테고리가 성공적으로 삭제되었습니다.", null);
    }
}