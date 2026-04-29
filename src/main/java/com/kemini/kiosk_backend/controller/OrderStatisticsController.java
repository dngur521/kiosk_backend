package com.kemini.kiosk_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.response.CartItem;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.OrderStatisticsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class OrderStatisticsController {

    private final OrderStatisticsService statisticsService;

    // 1. TOP 3 메뉴 조회 (필터링 기능 추가)
    @GetMapping("/top3")
    public ApiResponse<List<MenuResponseDto>> getTop3Menus(
            @RequestParam(value = "categoryName", required = false) String categoryName) {
        
        String baseUrl = "https://kemini-kiosk-api.duckdns.org"; 
        
        List<MenuResponseDto> top3 = statisticsService.getTop3Menus(categoryName, baseUrl);
        
        return ApiResponse.success(top3);
    }

    // 2. 결제 시 주문량 기록
    @PostMapping("/order")
    public ApiResponse<String> recordOrder(@RequestBody List<CartItem> items) {
        statisticsService.recordOrder(items);
        return ApiResponse.success("통계 기록 완료");
    }
}