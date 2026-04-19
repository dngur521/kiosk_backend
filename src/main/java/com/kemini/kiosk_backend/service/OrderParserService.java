package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.domain.repository.MenuSynonymRepository;

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

    @Transactional(readOnly = true)
    public List<OrderResult> parseMultiOrder(String sessionId, String input, String baseUrl) {
        List<OrderResult> results = new ArrayList<>();
        if (input == null || input.isBlank()) return results;

        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 분석 시작 (Session: {}): [{}]", sessionId, cleanInput);

        // 1. 후보군 생성 (이름 + 시노님)
        // 1. 후보군 생성 (직접 이름 vs 학습된 별명 구분)
        List<MenuCandidate> candidates = new ArrayList<>();
        // 직접 이름은 isSynonym = false
        menuRepository.findAll().forEach(m -> 
            candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""), false)));
        // 학습된 별명은 isSynonym = true
        menuSynonymRepository.findAll().forEach(s -> 
            candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""), true)));
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        // 2. 정확 매칭 (Greedy Match)
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
                    // 🔥 매칭 시 시노님 여부를 함께 저장
                    matches.add(new MenuMatch(cand.menu, idx, cand.text.length(), cand.isSynonym));
                    for (int i = idx; i < idx + cand.text.length(); i++) occupied[i] = true;
                }
                idx = cleanInput.indexOf(cand.text, idx + 1);
            }
        }

        // 3. 매칭 결과 처리
        if (!matches.isEmpty()) {
            matches.sort(Comparator.comparingInt(m -> m.startIdx));
            for (int i = 0; i < matches.size(); i++) {
                MenuMatch current = matches.get(i);
                int nextMatchStart = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
                String subText = cleanInput.substring(current.startIdx, nextMatchStart);

                Integer qty = quantityResolverService.resolveQuantity(subText);
                boolean isCancel = cancelResolverService.hasCancelKeyword(subText);
                boolean isMenuAllCancel = cancelResolverService.isAllCancelRequest(subText);
                
                orderContextService.updateContext(sessionId, current.menu.getId());
                results.add(new OrderResult(new com.kemini.kiosk_backend.dto.response.MenuResponseDto(current.menu, baseUrl), (qty == null ? 1 : qty), isCancel, isMenuAllCancel, false, false, null, current.isSynonym));
            }
        } else {
            // 매칭 실패 시 지시어/전체취소 체크
            if (pronounResolverService.hasPronoun(cleanInput)) {
                Menu lastMenu = orderContextService.getContext(sessionId);
                if (lastMenu != null) {
                    Integer qty = quantityResolverService.resolveQuantity(cleanInput);
                    results.add(new OrderResult(new com.kemini.kiosk_backend.dto.response.MenuResponseDto(lastMenu, baseUrl), (qty == null ? 1 : qty), cancelResolverService.hasCancelKeyword(cleanInput), cancelResolverService.isAllCancelRequest(cleanInput), false, false, null, false));
                    return results;
                }
            }
            if (cancelResolverService.isAllCancelRequest(cleanInput)) {
                results.add(new OrderResult(null, 0, true, true, true, false, null, false)); 
                return results;
            }
        }

        // 4. 🔥 [핵심 수정] 최종 분석 실패 시 유사도 기반 추천 리스트 추출
        if (results.isEmpty()) {
            log.info("❓ 매칭 실패 -> 유사 메뉴 검색 시작");
            
            // 🔥 [수정] Map을 활용하여 메뉴 ID 기준으로 중복을 제거하고 순서를 유지합니다.
            List<com.kemini.kiosk_backend.dto.response.MenuResponseDto> suggestions = candidates.stream()
                .map(cand -> new Object() {
                    Menu menu = cand.menu;
                    double score = calculateSimilarity(cleanInput, cand.text);
                })
                .filter(obj -> obj.score > 0.3)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                // --- 여기서부터 중복 제거 핵심 ---
                .map(obj -> obj.menu) // 1. 우선 메뉴 엔티티만 꺼냅니다.
                .collect(java.util.stream.Collectors.toMap(
                    Menu::getId,      // 2. 메뉴 ID를 키로 사용 (중복 제거 기준)
                    m -> m,           // 3. 값은 메뉴 객체
                    (existing, replacement) -> existing, // 4. 중복 ID 발견 시 기존 것 유지
                    java.util.LinkedHashMap::new         // 5. 정렬 순서(점수 순) 유지
                ))
                .values().stream()    // 6. 중복이 제거된 메뉴들만 다시 스트림으로
                .limit(3)
                .map(m -> new com.kemini.kiosk_backend.dto.response.MenuResponseDto(m, baseUrl)) // 7. 마지막에 DTO 변환
                .collect(java.util.stream.Collectors.toList());

            results.add(new OrderResult(null, 0, false, false, false, true, suggestions, false)); 
        }
        
        return results;
    }

    /**
     * 🔥 레벤슈타인 거리를 이용한 문자열 유사도 계산 (0.0 ~ 1.0)
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

    // --- 내부 헬퍼 클래스 ---
    
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
        private com.kemini.kiosk_backend.dto.response.MenuResponseDto menuDto;
        private int quantity;
        private boolean isCancel;
        private boolean isMenuAllCancel;
        private boolean isAllCancel;
        private boolean isUnknown;
        private List<com.kemini.kiosk_backend.dto.response.MenuResponseDto> suggestedMenus;
        private boolean isLearnedMatch;
    }

    @AllArgsConstructor
    private static class MenuCandidate {
        Menu menu;
        String text;
        boolean isSynonym;
    }
}