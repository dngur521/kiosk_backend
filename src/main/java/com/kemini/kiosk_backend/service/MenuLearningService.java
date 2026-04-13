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

    @Transactional
    public int learnAndAddToCart(String sessionId, LearningRequestDto request) {
        String rawText = request.getText().trim();
        if (rawText.isEmpty()) return 1;

        // 1. 띄어쓰기 기준 분리
        String[] parts = rawText.split("\\s+");
        String synonymToLearn = parts[0]; // "바닐라"
        
        // 2. 첫 단어 학습 (이미 존재하는지 체크 후 저장)
        if (!synonymRepository.existsBySynonym(synonymToLearn)) {
            Menu menu = menuRepository.findById(request.getMenuId())
                    .orElseThrow(() -> new RuntimeException("메뉴 없음"));
            synonymRepository.save(new MenuSynonym(menu, synonymToLearn));
            log.info("🎓 신규 시노님 학습: {}", synonymToLearn);
        }

        // 3. 🔥 남은 텍스트에서 수량 맥락 추출
        int quantity = 1;
        if (parts.length > 1) {
            // 첫 단어 제외한 나머지 합치기 (예: "두 개 줘")
            String contextText = rawText.substring(rawText.indexOf(parts[1]));
            Integer resolvedQty = quantityResolverService.resolveQuantity(contextText);
            quantity = (resolvedQty == null) ? 1 : resolvedQty;
        }

        // 4. 🔥 백엔드 장바구니에 즉시 반영 (맥락 저장 후 실행)
        Menu menu = menuRepository.findById(request.getMenuId()).get();
        addToCart(sessionId, menu, quantity);
        
        return quantity; // 프론트 UI 업데이트를 위해 반환
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