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
    private final PronounResolverService pronounResolverService; // 🔥 DB 지시어 서비스
    private final OrderContextService orderContextService;       // 🔥 Redis 문맥 서비스

    @Transactional(readOnly = true)
    public List<OrderResult> parseMultiOrder(String sessionId, String input) { // 🔥 sessionId 추가
        List<OrderResult> results = new ArrayList<>();
        if (input == null || input.isBlank()) return results;

        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 분석 시작 (Session: {}): [{}]", sessionId, cleanInput);

        // 1. 메뉴 매칭 시도 (Greedy Match)
        boolean[] occupied = new boolean[cleanInput.length()];
        List<MenuMatch> matches = new ArrayList<>();

        List<MenuCandidate> candidates = new ArrayList<>();
        menuRepository.findAll().forEach(m -> candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""))));
        menuSynonymRepository.findAll().forEach(s -> candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""))));
        
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        for (MenuCandidate cand : candidates) {
            int idx = cleanInput.indexOf(cand.text);
            while (idx != -1) {
                boolean isAlreadyOccupied = false;
                for (int i = idx; i < idx + cand.text.length(); i++) {
                    if (occupied[i]) { isAlreadyOccupied = true; break; }
                }

                if (!isAlreadyOccupied) {
                    matches.add(new MenuMatch(cand.menu, idx, cand.text.length()));
                    for (int i = idx; i < idx + cand.text.length(); i++) occupied[i] = true;
                }
                idx = cleanInput.indexOf(cand.text, idx + 1);
            }
        }

        // 2. 🔥 메뉴 매칭이 하나도 없을 때
        if (matches.isEmpty()) {
            
            // A. 🔥 [지시어 우선 체크] "이거", "그거", "방금" 등이 있는지 먼저 확인
            if (pronounResolverService.hasPronoun(cleanInput)) {
                Menu lastMenu = orderContextService.getContext(sessionId); // Redis 호출
                
                if (lastMenu != null) {
                    log.info("🎯 지시어 + Redis 문맥 결합 성공: {}", lastMenu.getName());
                    
                    Integer qty = quantityResolverService.resolveQuantity(cleanInput);
                    boolean isThisMenuCanceled = cancelResolverService.hasCancelKeyword(cleanInput);
                    
                    // 🔥 여기서 '전부'는 '해당 메뉴 전체 삭제'로 해석됨
                    boolean isSpecificMenuAllCancel = cancelResolverService.isAllCancelRequest(cleanInput);

                    results.add(new OrderResult(lastMenu, (qty == null ? 1 : qty), isThisMenuCanceled, isSpecificMenuAllCancel, false));
                    return results; // 지시어 처리가 끝났으므로 반환
                }
            }

            // B. 🔥 지시어가 없을 때만 "장바구니 전체 삭제"인지 확인
            if (cancelResolverService.isAllCancelRequest(cleanInput)) {
                log.info("🗑️ 장바구니 전체 비우기 감지 (지시어 없음)");
                results.add(new OrderResult(null, 0, true, true, true)); 
                return results;
            }
        }

        // 3. 메뉴가 있는 경우 구역별 분석 및 Redis 컨텍스트 업데이트
        matches.sort(Comparator.comparingInt(m -> m.startIdx));
        for (int i = 0; i < matches.size(); i++) {
            MenuMatch current = matches.get(i);
            int nextMatchStart = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
            String subText = cleanInput.substring(current.startIdx, nextMatchStart);

            Integer qty = quantityResolverService.resolveQuantity(subText);
            boolean isThisMenuCanceled = cancelResolverService.hasCancelKeyword(subText);
            boolean isSpecificMenuAllCancel = cancelResolverService.isAllCancelRequest(subText);
            
            // 🔥 [문맥 업데이트] 가장 마지막에 언급된 메뉴를 Redis에 저장
            orderContextService.updateContext(sessionId, current.menu.getId());
            
            results.add(new OrderResult(current.menu, (qty == null) ? 1 : qty, isThisMenuCanceled, isSpecificMenuAllCancel, false));
        }
        
        return results;
    }

    // --- 내부 헬퍼 클래스 ---
    
    @AllArgsConstructor
    private static class MenuMatch {
        Menu menu;
        int startIdx;
        int length;
    }

    @AllArgsConstructor
    @Getter
    public static class OrderResult {
        private Menu menu;
        private int quantity;
        private boolean isCancel;
        private boolean isMenuAllCancel;
        private boolean isAllCancel;
    }

    @AllArgsConstructor
    private static class MenuCandidate {
        Menu menu;
        String text;
    }
}