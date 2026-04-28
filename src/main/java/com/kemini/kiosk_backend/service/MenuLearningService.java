package com.kemini.kiosk_backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.entity.MenuSynonym;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.domain.repository.MenuSynonymRepository;
import com.kemini.kiosk_backend.dto.request.LearningRequestDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class MenuLearningService {

    private final MenuRepository menuRepository;
    private final MenuSynonymRepository synonymRepository;
    private final QuantityResolverService quantityResolverService;
    private final CartService cartService; // 🔥 장바구니 직접 수정을 위해 추가
    private final OrderContextService orderContextService; // 🔥 맥락 공유를 위해 추가!

    @Transactional
    public int learnAndAddToCart(String sessionId, LearningRequestDto request) {
        String rawText = request.getText().trim();
        if (rawText.isEmpty()) return 1;

        // 1. 띄어쓰기 기준 분리
        String[] parts = rawText.split("\\s+");
        
        // 2. 🔥 [핵심] 어디서부터 수량인지 경계선 찾기
        int splitIdx = parts.length; // 기본값은 전체를 메뉴명으로 간주
        for (int i = 0; i < parts.length; i++) {
            // 단어 하나가 수량으로 해석되는지 확인 (예: "하나", "두", "1")
            if (quantityResolverService.resolveQuantity(parts[i]) != null) {
                splitIdx = i; 
                break; // 수량 단어가 처음 발견되면 그 전까지가 메뉴 이름!
            }
        }

        // 3. 🔥 메뉴 이름 합치기 (수량 전까지 모든 단어)
        // 예: "달콤한 빵 하나 줘" -> splitIdx는 2 (단어 "하나"의 위치)
        // synonymToLearn -> "달콤한 빵"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < splitIdx; i++) {
            sb.append(parts[i]).append(" ");
        }
        String synonymToLearn = sb.toString().trim(); // 공백 제거
        String synonymForMatch = synonymToLearn.replaceAll("\\s", ""); // "달콤한빵" (매칭용)

        // 4. 시노님 학습 (0순위 긴 단어로 저장)
        if (!synonymRepository.existsBySynonym(synonymForMatch)) {
            Menu menu = menuRepository.findById(request.getMenuId())
                    .orElseThrow(() -> new RuntimeException("메뉴 없음"));
            synonymRepository.save(new MenuSynonym(menu, synonymForMatch));
            log.info("🎓 신규 시노님 학습 완료: [{}]", synonymForMatch);
        }

        // 5. 🔥 남은 텍스트(수량 부분)에서 수량 맥락 추출
        int quantity = 1;
        if (splitIdx < parts.length) {
            // splitIdx부터 끝까지 합쳐서 수량 분석 (예: "하나 줘")
            StringBuilder contextBuilder = new StringBuilder();
            for (int i = splitIdx; i < parts.length; i++) {
                contextBuilder.append(parts[i]).append(" ");
            }
            Integer resolvedQty = quantityResolverService.resolveQuantity(contextBuilder.toString().trim());
            quantity = (resolvedQty == null) ? 1 : resolvedQty;
        }

        // 6. Redis 맥락 업데이트 및 장바구니 추가 (기존 로직 유지)
        orderContextService.updateContext(sessionId, request.getMenuId());
        Menu menu = menuRepository.findById(request.getMenuId()).get();
        addToCart(sessionId, menu, quantity);
        
        return quantity;
    }

    private void addToCart(String sessionId, Menu menu, int qty) {
        com.kemini.kiosk_backend.dto.response.CartItem newItem = new com.kemini.kiosk_backend.dto.response.CartItem();
        newItem.setMenuId(menu.getId());
        newItem.setMenuName(menu.getName());
        newItem.setPrice(menu.getPrice());
        newItem.setQuantity(qty);
        cartService.addToCart(sessionId, newItem);
        log.info("🛒 학습과 동시에 장바구니 담기 완료: {} ({}개)", menu.getName(), qty);
    }
}