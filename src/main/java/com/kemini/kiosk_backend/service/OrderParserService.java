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
        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 분석 시작: [{}]", cleanInput);

        // 1. 매칭된 글자 범위를 기억할 배열 (문자열 길이만큼)
        boolean[] occupied = new boolean[cleanInput.length()];
        List<MenuMatch> matches = new ArrayList<>();

        // 2. 모든 후보(이름+별칭)를 가져와서 '글자 길이'가 긴 순서대로 정렬합니다.
        List<MenuCandidate> candidates = new ArrayList<>();
        menuRepository.findAll().forEach(m -> candidates.add(new MenuCandidate(m, m.getName().replaceAll("\\s", ""))));
        menuSynonymRepository.findAll().forEach(s -> candidates.add(new MenuCandidate(s.getMenu(), s.getSynonym().replaceAll("\\s", ""))));
        
        // 🔥 긴 단어부터 먼저 처리해야 '허니브레드'가 '허니'와 '브레드'를 먹어버립니다.
        candidates.sort((a, b) -> Integer.compare(b.text.length(), a.text.length()));

        // 3. 긴 단어부터 매칭 시도
        for (MenuCandidate cand : candidates) {
            int idx = cleanInput.indexOf(cand.text);
            while (idx != -1) {
                // 이미 매칭된 영역인지 확인
                boolean isAlreadyOccupied = false;
                for (int i = idx; i < idx + cand.text.length(); i++) {
                    if (occupied[i]) { isAlreadyOccupied = true; break; }
                }

                if (!isAlreadyOccupied) {
                    matches.add(new MenuMatch(cand.menu, idx));
                    // 🚩 이 영역은 이제 내꺼! (방어막 설치)
                    for (int i = idx; i < idx + cand.text.length(); i++) occupied[i] = true;
                }
                // 다음 동일 단어 찾기 (ex: 아메리카노 2잔, 아메리카노 1잔)
                idx = cleanInput.indexOf(cand.text, idx + 1);
            }
        }

        // 4. 문장에 나타난 순서대로 정렬하여 수량 파악
        matches.sort(Comparator.comparingInt(m -> m.startIdx));
        for (int i = 0; i < matches.size(); i++) {
            MenuMatch current = matches.get(i);
            int endIdx = (i + 1 < matches.size()) ? matches.get(i + 1).startIdx : cleanInput.length();
            String subText = cleanInput.substring(current.startIdx, endIdx);

            Integer qty = quantityResolverService.resolveQuantity(subText);
            results.add(new OrderResult(current.menu, (qty == null) ? 1 : qty));
        }
        return results;
    }

    /**
     * 핵심 메서드: 모든 메뉴/별칭의 위치를 찾습니다.
     */
    private List<MenuMatch> findMenuMatches(String cleanInput) {
        List<MenuMatch> matches = new ArrayList<>();

        // A. 메뉴판의 실제 이름들 뒤지기
        menuRepository.findAll().forEach(menu -> {
            String name = menu.getName().replaceAll("\\s", "");
            int idx = cleanInput.indexOf(name);
            if (idx != -1) {
                matches.add(new MenuMatch(menu, idx));
            }
        });

        // B. 별칭(Synonym) 테이블 뒤지기
        menuSynonymRepository.findAll().forEach(synonym -> {
            String syn = synonym.getSynonym().replaceAll("\\s", "");
            int idx = cleanInput.indexOf(syn);
            if (idx != -1) {
                // 이미 동일한 위치에 메뉴가 등록되었다면 패스 (중복 방지)
                if (matches.stream().noneMatch(m -> m.startIdx == idx)) {
                    matches.add(new MenuMatch(synonym.getMenu(), idx));
                }
            }
        });

        return matches;
    }

    // --- 내부 헬퍼 클래스 ---
    
    @AllArgsConstructor
    private static class MenuMatch {
        Menu menu;
        int startIdx;
    }

    @AllArgsConstructor
    @Getter
    public static class OrderResult {
        private Menu menu;
        private int quantity;
    }
    @AllArgsConstructor
    
    private static class MenuCandidate {
        Menu menu;
        String text;
    }
}