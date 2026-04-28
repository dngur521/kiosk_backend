package com.kemini.kiosk_backend.service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.dto.request.RecommendationRequest;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;
import com.kemini.kiosk_backend.dto.response.RecommendationResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private final MenuRepository menuRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String PYTHON_SERVER_URL = "http://localhost:8000/recommend";
    private final String BASE_URL = "https://kemini-kiosk-api.duckdns.org";

    public List<MenuResponseDto> getSemanticRecommendations(String query) {
        try {
            RecommendationRequest request = new RecommendationRequest(query);
            RecommendationResponse response = restTemplate.postForObject(
                    PYTHON_SERVER_URL, request, RecommendationResponse.class);

            if (response == null || response.recommendations() == null) return Collections.emptyList();

            // 1. 신뢰도 필터링 (0.75 이상) 및 AI가 준 순서(ID 리스트) 보존
            List<RecommendationResponse.MenuInfo> filteredAiResults = response.recommendations().stream()
                    .filter(m -> m.score() >= 0.75)
                    .toList();

            if (filteredAiResults.isEmpty()) return Collections.emptyList();

            List<Long> ids = filteredAiResults.stream().map(RecommendationResponse.MenuInfo::id).toList();

            // 2. DB에서 메뉴 정보 조회
            List<Menu> menus = menuRepository.findAllById(ids);

            // 3. 🔥 [핵심] AI가 준 순서대로 다시 정렬
            // AI 결과 리스트(filteredAiResults)를 순회하며 매칭되는 메뉴를 DTO로 변환
            return filteredAiResults.stream()
                    .map(aiInfo -> {
                        Menu menu = menus.stream()
                                .filter(m -> m.getId().equals(aiInfo.id()))
                                .findFirst()
                                .orElse(null);
                        return menu != null ? new MenuResponseDto(menu, BASE_URL) : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception e) {
            log.error("❌ AI 추천 서버 호출 중 오류: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
