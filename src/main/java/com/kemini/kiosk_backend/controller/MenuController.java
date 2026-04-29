package com.kemini.kiosk_backend.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.request.MenuRequestDto;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.MenuService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    // 메뉴 생성
    @PostMapping
    public ApiResponse<MenuResponseDto> createMenu(@ModelAttribute MenuRequestDto dto) throws IOException {
        return ApiResponse.success("메뉴가 생성되었습니다.", menuService.saveMenu(dto));
    }

    // 메뉴 추가
    @GetMapping
    public ApiResponse<List<MenuResponseDto>> getMenus(@RequestParam(value = "categoryId", required = false) Long categoryId) {
        return ApiResponse.success(menuService.getMenus(categoryId));
    }

    // 메뉴 수정
    @PutMapping(value = "/{id}", consumes = {"multipart/form-data"})
    public ApiResponse<MenuResponseDto> updateMenu(
            @PathVariable("id") Long id, 
            @ModelAttribute MenuRequestDto dto) throws IOException {
        
        return ApiResponse.success("메뉴가 성공적으로 수정되었습니다.", menuService.updateMenu(id, dto));
    }

    // 메뉴 삭제
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable("id") Long id) {
        menuService.deleteMenu(id);
        return ApiResponse.success("메뉴가 삭제되었습니다.", null);
    }
}