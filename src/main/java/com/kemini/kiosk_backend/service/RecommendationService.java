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

            if (response == null || response.recommendations() == null || response.recommendations().isEmpty()) {
                return Collections.emptyList();
            }

            // 1. [상대적 필터링 로직]
            // 파이썬 엔진에서 이미 정렬해서 보내주므로, 첫 번째 요소가 최고점입니다.
            double maxScore = response.recommendations().get(0).score();
            log.info("🎯 AI 추천 최고점: {}, 필터 기준점(max - 0.05): {}", maxScore, (maxScore - 0.05));

            // 최고점과의 차이가 0.05 이내인 메뉴만 필터링
            List<RecommendationResponse.MenuInfo> filteredAiResults = response.recommendations().stream()
                    .filter(m -> m.score() >= 0.5 && m.score() >= (maxScore - 0.05))
                    .toList();

            // 2. DB에서 메뉴 정보 조회
            List<Long> ids = filteredAiResults.stream().map(RecommendationResponse.MenuInfo::id).toList();
            List<Menu> menus = menuRepository.findAllById(ids);

            // 3. AI가 준 순서대로 다시 정렬 (기존 로직 유지)
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
