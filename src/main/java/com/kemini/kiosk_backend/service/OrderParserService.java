package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.domain.repository.MenuSynonymRepository;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderParserService {

    private final MenuRepository menuRepository;
    private final MenuSynonymRepository menuSynonymRepository;
    private final QuantityResolverService quantityResolverService;
    private final CancelResolverService cancelResolverService;
    private final PronounResolverService pronounResolverService;
    private final OrderContextService orderContextService;
    private final RecommendationService recommendationService;

    @Transactional(readOnly = true)
    public List<OrderResult> parseMultiOrder(String sessionId, String input, String baseUrl) {
        List<OrderResult> results = new ArrayList<>();
        if (input == null || input.isBlank()) return results;

        String cleanInput = input.replaceAll("\\s", "").replace(".", "");

        String semanticQuery = input.trim();

        log.info("🔍 [분석 시작] 세션: {}, 원본(AI용): [{}], 정제(규칙용): [{}]", sessionId, semanticQuery, cleanInput);

        // --- [기존 로직 1] 매칭 후보군 생성 (유지) ---
        List<MenuCandidate> candidates = new ArrayList<>();
        menuRepository.findAll().forEach(m -> 
            candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""), false)));
        menuSynonymRepository.findAll().forEach(s -> 
            candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""), true)));
        
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        // --- [기존 로직 2] Greedy Match (유지) ---
        boolean[] occupied = new boolean[cleanInput.length()];
        List<MenuMatch> matches = new ArrayList<>();

        for (MenuCandidate cand : candidates) {

            if (cand.text.isEmpty()) {
                log.warn("⚠️ 비어있는 이름의 메뉴/시노님 발견! ID: {}", cand.menu.getId());
                continue;
            }
            int idx = cleanInput.indexOf(cand.text);
            while (idx != -1) {
                boolean isAlreadyOccupied = false;
                for (int i = idx; i < idx + cand.text.length(); i++) {
                    if (occupied[i]) { isAlreadyOccupied = true; break; }
                }

                if (!isAlreadyOccupied) {
                    matches.add(new MenuMatch(cand.menu, idx, cand.text.length(), cand.isSynonym));
                    for (int i = idx; i < idx + cand.text.length(); i++) occupied[i] = true;
                }
                idx = cleanInput.indexOf(cand.text, idx + 1);
            }
        }
        log.info("✅ Greedy Match 완료 - 매칭 개수: {}", matches.size());

        // --- [기존 로직 3] 매칭 결과 처리 (유지) ---
        if (!matches.isEmpty()) {
            matches.sort(Comparator.comparingInt(m -> m.startIdx));
            for (int i = 0; i < matches.size(); i++) {
                MenuMatch current = matches.get(i);
                int nextMatchStart = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
                int startSearchIdx = (i == 0) ? 0 : current.startIdx;
                String subText = cleanInput.substring(startSearchIdx, nextMatchStart);

                Integer qty = quantityResolverService.resolveQuantity(subText);
                boolean isCancel = cancelResolverService.hasCancelKeyword(subText);
                boolean isMenuAllCancel = cancelResolverService.isAllCancelRequest(subText);

                log.info("💾 Redis 컨텍스트 업데이트 시도 중... 세션: {}", sessionId);
                orderContextService.updateContext(sessionId, current.menu.getId());
                log.info("✅ Redis 업데이트 완료!");
                results.add(new OrderResult(new MenuResponseDto(current.menu, baseUrl), (qty == null ? 1 : qty), isCancel, isMenuAllCancel, false, false, null, current.isSynonym));
            }
        } else {
            // --- [기존 로직 4] 지시어 및 전체 취소 처리 (유지) ---
            if (pronounResolverService.hasPronoun(cleanInput)) {
                Menu lastMenu = orderContextService.getContext(sessionId);
                if (lastMenu != null) {
                    Integer qty = quantityResolverService.resolveQuantity(cleanInput);
                    results.add(new OrderResult(new MenuResponseDto(lastMenu, baseUrl), (qty == null ? 1 : qty), cancelResolverService.hasCancelKeyword(cleanInput), cancelResolverService.isAllCancelRequest(cleanInput), false, false, null, false));
                    return results;
                }
            }
            if (cancelResolverService.isAllCancelRequest(cleanInput)) {
                results.add(new OrderResult(null, 0, true, true, true, false, null, false)); 
                return results;
            }
        }

        // --- [하이브리드 로직 5] 매칭 실패 시 추천 엔진 가동 ---
        if (results.isEmpty()) {
            log.info("❓ 직접 매칭 실패 -> 하이브리드 추천 단계 진입");
            
            // 1단계: 파이썬 AI 시맨틱 서치 시도
            List<MenuResponseDto> suggestions = recommendationService.getSemanticRecommendations(semanticQuery);
            log.info("🎯 AI 추천 서버 응답 완료 - 추천 개수: {}", suggestions.size());

            // 2단계: AI 결과가 없을 경우, 기존의 레벤슈타인 거리 기반 유사도 로직으로 백업 (기존 로직 보존)
            if (suggestions.isEmpty()) {
                log.info("⚠️ AI 추천 결과 없음 (Query: {}) -> 레벤슈타인 백업 가동", semanticQuery);
                suggestions = candidates.stream()
                    .map(cand -> new Object() {
                        Menu menu = cand.menu;
                        double score = calculateSimilarity(cleanInput, cand.text);
                    })
                    .filter(obj -> obj.score > 0.3) 
                    .sorted((a, b) -> Double.compare(b.score, a.score))
                    .map(obj -> obj.menu)
                    .distinct()
                    .limit(3)
                    .map(m -> new MenuResponseDto(m, baseUrl))
                    .collect(Collectors.toList());
            }

            results.add(new OrderResult(null, 0, false, false, false, true, suggestions, false)); 
        }
        
        return results;
    }

    //레벤슈타인 거리를 활용한 문자열 유사도 계산
    private double calculateSimilarity(String s1, String s2) {
        int distance = getLevenshteinDistance(s1, s2);
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0;
        return 1.0 - ((double) distance / maxLength);
    }

    private int getLevenshteinDistance(String s, String t) {
        int n = s.length();
        int m = t.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; d[i][0] = i++);
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = (t.charAt(j - 1) == s.charAt(i - 1)) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[n][m];
    }


    @AllArgsConstructor
    private static class MenuMatch {
        Menu menu;
        int startIdx;
        int length;
        boolean isSynonym;
    }

    @AllArgsConstructor
    @Getter
    public static class OrderResult {
        private MenuResponseDto menuDto;
        private int quantity;
        private boolean isCancel;
        private boolean isMenuAllCancel;
        private boolean isAllCancel;
        private boolean isUnknown;
        private List<MenuResponseDto> suggestedMenus;
        private boolean isLearnedMatch;
    }

    @AllArgsConstructor
    private static class MenuCandidate {
        Menu menu;
        String text;
        boolean isSynonym;
    }
}