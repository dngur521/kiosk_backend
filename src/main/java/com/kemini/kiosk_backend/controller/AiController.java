package com.kemini.kiosk_backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.response.MenuResponseDto;
import com.kemini.kiosk_backend.service.RecommendationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final RecommendationService recommendationService;

    /**
     * 사용자가 기존 매칭을 거절했을 때, 원본 텍스트로 AI 시맨틱 추천을 다시 수행합니다.
     */
    @PostMapping("/recommend")
    public ResponseEntity<List<MenuResponseDto>> getAiRecommendations(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        // 우리가 만든 0.85점 필터링 로직이 적용된 AI 추천 리스트 반환
        List<MenuResponseDto> suggestions = recommendationService.getSemanticRecommendations(query);
        return ResponseEntity.ok(suggestions);
    }
}