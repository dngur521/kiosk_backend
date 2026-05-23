package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.dto.response.CartItem;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LipReadingService {

    private final MenuRepository menuRepository;
    private final LipReadingSessionContext sessionContext;
    private final CartService cartService;

    @Value("${app.base-url}")
    private String baseUrl;

    // 한국어 중성(모음) 인덱스 0~20을 코드에 매핑
    // offset = (char - 0xAC00), vowelIdx = (offset % 588) / 28
    private static final String[] VOWEL_MAP = {
        "A","E","A","I","EO","E","EO","I",   // ㅏ ㅐ ㅑ ㅒ ㅓ ㅔ ㅕ ㅖ
        "O","O","O","O","O",                   // ㅗ ㅘ ㅙ ㅚ ㅛ
        "U","U","U","U","U",                   // ㅜ ㅝ ㅞ ㅟ ㅠ
        "EU","EU","I"                          // ㅡ ㅢ ㅣ
    };

    // Python이 한국어 문자(ㅏ, ㅓ 등)로 보낼 경우 영어 코드로 변환
    private static final Map<String, String> KO_TO_CODE = Map.ofEntries(
        Map.entry("ㅏ", "A"), Map.entry("ㅑ", "A"),
        Map.entry("ㅓ", "EO"), Map.entry("ㅕ", "EO"),
        Map.entry("ㅗ", "O"), Map.entry("ㅛ", "O"),
        Map.entry("ㅜ", "U"), Map.entry("ㅠ", "U"),
        Map.entry("ㅡ", "EU"),
        Map.entry("ㅣ", "I"), Map.entry("ㅖ", "I"), Map.entry("ㅒ", "I"),
        Map.entry("ㅔ", "E"), Map.entry("ㅐ", "E")
    );

    public synchronized void processResult(List<String> lipVowels) {
        if (!sessionContext.tryConsumeLipReading()) {
            log.debug("립리딩 결과 중복 수신 — 무시 (STT 한 번당 1회만 처리)");
            return;
        }

        String sttText = sessionContext.getSttText();
        float confidence = sessionContext.getSttConfidence();

        if (sttText == null || sttText.isBlank()) {
            log.warn("립리딩 콜백 수신했으나 STT 컨텍스트 없음 — 무시");
            return;
        }

        List<String> sttVowels = extractVowels(sttText);
        // Python이 한국어 문자로 보낸 경우 영어 코드로 정규화
        List<String> normalizedLipVowels = lipVowels.stream()
                .map(v -> KO_TO_CODE.getOrDefault(v, v))
                .toList();
        log.info("STT 모음: {}, 립리딩 모음: {}, confidence: {}", sttVowels, normalizedLipVowels, confidence);

        List<Menu> allMenus = menuRepository.findAllByOrderByIdAsc();

        Menu bestMenu = null;
        double bestScore = -1;

        for (Menu menu : allMenus) {
            List<String> menuVowels = extractVowels(menu.getName());
            double score = fusedScore(sttVowels, normalizedLipVowels, menuVowels, confidence);
            if (score > bestScore) {
                bestScore = score;
                bestMenu = menu;
            }
        }

        if (bestMenu == null || bestScore < 0.5) {
            log.info("립리딩 매칭 실패 (최고점: {})", bestScore);
            return;
        }

        log.info("립리딩 매칭: {} (score={})", bestMenu.getName(), bestScore);

        // 저신뢰도로 보류된 주문이 있으면 립리딩 결과로 장바구니에 담음
        if (sessionContext.hasPendingOrders()) {
            addMatchedPendingOrders(bestMenu);
            sessionContext.clearPendingOrders();
        }

        try {
            var ws = sessionContext.getSession();
            if (ws != null && ws.isOpen()) {
                MenuResponseDto dto = new MenuResponseDto(bestMenu, baseUrl);
                String payload = String.format("SYSTEM:LIPREADING_MATCH:%d:%s:%.2f",
                        dto.getId(), bestMenu.getName(), bestScore);
                ws.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("립리딩 결과 WebSocket 전송 실패", e);
        }
    }

    public List<String> extractVowels(String text) {
        List<String> vowels = new ArrayList<>();
        for (char c : text.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int offset = c - 0xAC00;
                int vowelIdx = (offset % (21 * 28)) / 28;
                vowels.add(VOWEL_MAP[vowelIdx]);
            }
        }
        return vowels;
    }

    private double fusedScore(List<String> sttVowels, List<String> lipVowels,
                               List<String> menuVowels, float confidence) {
        if (confidence >= 0.8) {
            // STT 신뢰도 높음 → STT 모음으로 매칭, 립리딩은 교차검증만
            return similarity(sttVowels, menuVowels);
        } else if (confidence < 0.5) {
            // STT 신뢰도 낮음 → 립리딩 모음으로 직접 매칭
            return similarity(lipVowels, menuVowels);
        } else {
            // 중간 → 두 유사도 평균
            double sttSim = similarity(sttVowels, menuVowels);
            double lipSim = similarity(lipVowels, menuVowels);
            return (sttSim + lipSim) / 2.0;
        }
    }

    // 보류된 STT 주문 중 립리딩 매칭 메뉴와 유사한 항목을 장바구니에 추가
    private void addMatchedPendingOrders(Menu lipMenu) {
        var ws = sessionContext.getSession();
        if (ws == null) return;
        String sessionId = ws.getId();
        List<String> lipMenuVowels = extractVowels(lipMenu.getName());

        for (OrderParserService.OrderResult order : sessionContext.getPendingOrders()) {
            if (order.isUnknown() || order.isLearnedMatch() || order.isAllCancel()) continue;
            if (order.getMenuDto() == null || order.getQuantity() <= 0) continue;

            // STT가 인식한 메뉴와 립리딩 메뉴가 유사한지 확인
            List<String> sttMenuVowels = extractVowels(order.getMenuDto().getName());
            double matchScore = similarity(lipMenuVowels, sttMenuVowels);

            if (matchScore >= 0.5) {
                CartItem item = new CartItem();
                item.setMenuId(order.getMenuDto().getId());
                item.setMenuName(order.getMenuDto().getName());
                item.setPrice(order.getMenuDto().getPrice());
                item.setQuantity(order.getQuantity());
                cartService.addToCart(sessionId, item);
                log.info("🛒 립리딩 확인 후 장바구니 추가: {} ({}개, 유사도={})",
                        order.getMenuDto().getName(), order.getQuantity(), matchScore);
            } else {
                log.info("⚠️ 립리딩 불일치로 장바구니 미추가: STT={}, 립리딩={} (유사도={})",
                        order.getMenuDto().getName(), lipMenu.getName(), matchScore);
            }
        }
    }

    // Levenshtein 편집거리 기반 유사도 (0.0 ~ 1.0)
    private double similarity(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        if (n == 0 && m == 0) return 1.0;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) dp[i][0] = i;
        for (int j = 0; j <= m; j++) dp[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return 1.0 - (double) dp[n][m] / Math.max(n, m);
    }
}
