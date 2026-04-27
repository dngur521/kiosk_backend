package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * [핵심 메서드] 사용자의 음성 텍스트를 분석하여 다중 주문 결과를 반환합니다.
     */
    @Transactional(readOnly = true)
    public List<OrderResult> parseMultiOrder(String sessionId, String input, String baseUrl) {
        List<OrderResult> results = new ArrayList<>();
        if (input == null || input.isBlank()) return results;

        // 공백 및 마침표 제거하여 분석 효율화
        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 [분석 시작] 세션: {}, 입력: [{}]", sessionId, cleanInput);

        // 1. 매칭 후보군 생성 (정식 명칭 + 학습된 별칭)
        List<MenuCandidate> candidates = new ArrayList<>();
        menuRepository.findAll().forEach(m -> 
            candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""), false)));
        menuSynonymRepository.findAll().forEach(s -> 
            candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""), true)));
        
        // 긴 단어부터 매칭하기 위해 내림차순 정렬 (Greedy Match 준비)
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        // 2. 텍스트 내 메뉴 위치 매칭 (Greedy Match)
        boolean[] occupied = new boolean[cleanInput.length()];
        List<MenuMatch> matches = new ArrayList<>();

        for (MenuCandidate cand : candidates) {
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

        // 3. 매칭된 메뉴별 문맥 분석 (수량, 취소 여부 등)
        if (!matches.isEmpty()) {
            // 문장 내 등장 순서대로 정렬
            matches.sort(Comparator.comparingInt(m -> m.startIdx));

            int lastEndIdx = 0; // 이전 메뉴 분석이 끝난 지점 추적

            for (int i = 0; i < matches.size(); i++) {
                MenuMatch current = matches.get(i);
                
                // 다음 메뉴의 시작 지점 혹은 문장 끝 지점
                int nextMatchStart = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
                
                // 🔥 [수정 포인트] 분석 범위를 이전 메뉴 종료 지점부터 확장하여 "A 빼고 B" 문맥을 완벽히 포착
                // 첫 번째 메뉴만 0부터, 나머지는 자기 이름(current.startIdx)부터 분석
                // 이렇게 해야 "아메리카노 빼고"의 "빼고"가 카페라떼 분석 범위에 안 들어갑니다.
                int startSearchIdx = (i == 0) ? 0 : current.startIdx;
                String subText = cleanInput.substring(startSearchIdx, nextMatchStart);

                log.info("🎯 메뉴 [{}]의 분석 범위: {}", current.menu.getName(), subText);

                // 수량 및 취소 키워드 분석
                Integer qty = quantityResolverService.resolveQuantity(subText);
                boolean isCancel = cancelResolverService.hasCancelKeyword(subText);
                boolean isMenuAllCancel = cancelResolverService.isAllCancelRequest(subText);
                
                // 대화 문맥(Context) 업데이트 및 결과 추가
                orderContextService.updateContext(sessionId, current.menu.getId());
                results.add(new OrderResult(
                    new MenuResponseDto(current.menu, baseUrl), 
                    (qty == null ? 1 : qty), 
                    isCancel, 
                    isMenuAllCancel, 
                    false, 
                    false, 
                    null, 
                    current.isSynonym
                ));

                // 현재 메뉴가 끝난 지점을 저장 (다음 메뉴 분석의 시작점)
                lastEndIdx = current.startIdx + current.length;
            }
        } else {
            // 4. 메뉴 직접 매칭 실패 시 (지시어 처리 및 전체 취소 확인)
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

        // 5. 최종 결과가 없을 경우 유사도 기반 추천 리스트 추출
        if (results.isEmpty()) {
            log.info("❓ 매칭 실패 -> 유사 메뉴 검색 시작");
            
            List<MenuResponseDto> suggestions = candidates.stream()
                .map(cand -> new Object() {
                    Menu menu = cand.menu;
                    double score = calculateSimilarity(cleanInput, cand.text);
                })
                .filter(obj -> obj.score > 0.3) // 유사도 임계값
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .map(obj -> obj.menu)
                .collect(java.util.stream.Collectors.toMap(
                    Menu::getId,
                    m -> m,
                    (existing, replacement) -> existing, // 중복 ID 제거
                    java.util.LinkedHashMap::new
                ))
                .values().stream()
                .limit(3)
                .map(m -> new MenuResponseDto(m, baseUrl))
                .collect(java.util.stream.Collectors.toList());

            results.add(new OrderResult(null, 0, false, false, false, true, suggestions, false)); 
        }
        
        return results;
    }

    /**
     * 레벤슈타인 거리를 활용한 문자열 유사도 계산 (0.0 ~ 1.0)
     */
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
        for (int j = 0; j <= m; d[0][j] = j++);
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = (t.charAt(j - 1) == s.charAt(i - 1)) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[n][m];
    }

    // --- 내부 헬퍼 클래스 및 DTO ---
    
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