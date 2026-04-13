package com.kemini.kiosk_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    // 1. TOP 3 메뉴 조회 (프론트 Fallback 모달용)
    @GetMapping("/top3")
    public ApiResponse<List<MenuResponseDto>> getTop3Menus() {
        String baseUrl = "https://kemini-kiosk-api.duckdns.org"; 
        
        // 서비스에서 DTO 리스트를 가져옵니다.
        List<MenuResponseDto> top3 = statisticsService.getTop3Menus(baseUrl);
        
        return ApiResponse.success(top3);
    }

    // 2. 결제 시 주문량 기록 (프론트 결제하기 버튼 클릭 시 호출)
    @PostMapping("/order")
    public ApiResponse<String> recordOrder(@RequestBody List<CartItem> items) {
        statisticsService.recordOrder(items);
        return ApiResponse.success("통계 기록 완료");
    }
}