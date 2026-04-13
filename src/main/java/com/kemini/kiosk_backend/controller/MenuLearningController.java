package com.kemini.kiosk_backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.request.LearningRequestDto;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.MenuLearningService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class MenuLearningController {

    private final MenuLearningService learningService;

    @PostMapping
    public ApiResponse<Integer> learn(@RequestBody LearningRequestDto request, HttpServletRequest httpRequest) {
        // 세션 ID 추출 (웹소켓 세션과 맞추기 위해)
        String sessionId = httpRequest.getHeader("X-Session-ID"); 
        
        // 학습하고 수량 반환
        int quantity = learningService.learnAndAddToCart(sessionId, request);
        
        return ApiResponse.success(quantity);
    }
}