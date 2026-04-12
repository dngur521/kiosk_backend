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

    @Transactional(readOnly = true)
    public List<OrderResult> parseMultiOrder(String input) {
        List<OrderResult> results = new ArrayList<>();
        if (input == null || input.isBlank()) return results;

        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 분석 시작: [{}]", cleanInput);

        // 1. 🔥 [취소 판별] 문장에 취소 관련 키워드가 있는지 확인
        boolean isCancelRequest = cleanInput.contains("취소") || 
                                 cleanInput.contains("빼줘") || 
                                 cleanInput.contains("삭제") || 
                                 cleanInput.contains("빼주세요");

        // 2. 매칭된 글자 범위를 기억할 배열 (중복 매칭 방지용)
        boolean[] occupied = new boolean[cleanInput.length()];
        List<MenuMatch> matches = new ArrayList<>();

        // 3. 모든 후보(이름+별칭)를 가져와서 '글자 길이'가 긴 순서대로 정렬
        List<MenuCandidate> candidates = new ArrayList<>();
        menuRepository.findAll().forEach(m -> candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""))));
        menuSynonymRepository.findAll().forEach(s -> candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""))));
        
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        // 4. 긴 단어부터 매칭 시도 (Greedy Match)
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

        // 5. 문장에 나타난 순서대로 정렬하여 수량 파악 및 결과 생성
        matches.sort(Comparator.comparingInt(m -> m.startIdx));
        for (int i = 0; i < matches.size(); i++) {
            MenuMatch current = matches.get(i);
            int nextMatchStart = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
            
            // 현재 메뉴 단어 이후부터 다음 메뉴 단어 이전까지의 텍스트에서 수량 추출
            String subText = cleanInput.substring(current.startIdx, nextMatchStart);

            Integer qty = quantityResolverService.resolveQuantity(subText);
            
            // 🔥 OrderResult 생성 시 isCancelRequest 정보를 함께 전달
            results.add(new OrderResult(current.menu, (qty == null) ? 1 : qty, isCancelRequest));
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
        private boolean isCancel; // 🔥 취소 여부 필드 추가
    }

    @AllArgsConstructor
    private static class MenuCandidate {
        Menu menu;
        String text;
    }
}