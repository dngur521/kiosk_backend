package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.dto.response.CartItem;

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

    private static final String[] VOWEL_MAP = {
        "A","E","A","I","EO","E","EO","I",
        "O","O","O","O","O",
        "U","U","U","U","U",
        "EU","EU","I"
    };

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
            log.debug("립리딩 결과 중복 수신 — 무시");
            return;
        }

        List<String> normalizedLipVowels = lipVowels.stream()
                .map(v -> KO_TO_CODE.getOrDefault(v, v))
                .toList();
        log.info("립리딩 모음: {}", normalizedLipVowels);

        if (sessionContext.hasPendingOrders()) {
            // [Case 1] NLP 매칭 성공 → 보류 메뉴와 립리딩 비교
            processCrossValidation(normalizedLipVowels);
        } else {
            // [Case 2] NLP 매칭 실패 → 전체 메뉴 스캔 후 TOP 3 추천
            processRecommendation(normalizedLipVowels);
        }
    }

    // NLP가 찾은 메뉴와 립리딩 모음 비교 → 일치하면 장바구니 자동 추가
    private void processCrossValidation(List<String> normalizedLipVowels) {
        OrderParserService.OrderResult bestOrder = null;
        double bestScore = -1;

        for (OrderParserService.OrderResult order : sessionContext.getPendingOrders()) {
            if (order.isUnknown() || order.isLearnedMatch() || order.isAllCancel()) continue;
            if (order.getMenuDto() == null || order.getQuantity() <= 0) continue;

            List<String> menuVowels = extractVowels(order.getMenuDto().getName());
            double score = similarity(menuVowels, normalizedLipVowels);
            log.info("  '{}' 모음: {} → 유사도: {}", order.getMenuDto().getName(), menuVowels, String.format("%.2f", score));

            if (score > bestScore) {
                bestScore = score;
                bestOrder = order;
            }
        }

        sessionContext.clearPendingOrders();

        if (bestOrder == null) {
            // 비교할 유효한 메뉴가 없음 (레벤슈타인만 매칭 등) → 전체 메뉴 추천으로 전환
            log.info("립리딩 교차검증 대상 없음 — 전체 메뉴 추천으로 전환");
            processRecommendation(normalizedLipVowels);
            return;
        }
        if (bestScore < 0.5) {
            log.info("립리딩 교차검증 실패 (최고점: {})", String.format("%.2f", bestScore));
            sendFailed();
            return;
        }

        log.info("립리딩 교차검증 성공: {} (score={})", bestOrder.getMenuDto().getName(), String.format("%.2f", bestScore));

        var ws = sessionContext.getSession();
        if (ws == null) return;

        CartItem item = new CartItem();
        item.setMenuId(bestOrder.getMenuDto().getId());
        item.setMenuName(bestOrder.getMenuDto().getName());
        item.setPrice(bestOrder.getMenuDto().getPrice());
        item.setQuantity(bestOrder.getQuantity());
        cartService.addToCart(ws.getId(), item);
        log.info("🛒 립리딩 확인 후 장바구니 추가: {} ({}개)", bestOrder.getMenuDto().getName(), bestOrder.getQuantity());

        try {
            if (ws.isOpen()) {
                String payload = String.format("SYSTEM:LIPREADING_MATCH:%d:%s:%.2f",
                        bestOrder.getMenuDto().getId(), bestOrder.getMenuDto().getName(), bestScore);
                ws.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.error("립리딩 결과 WebSocket 전송 실패", e);
        }
    }

    // NLP 실패 시 전체 메뉴 스캔 → TOP 3 후보 전송 (프론트에서 사용자 확인)
    private void processRecommendation(List<String> normalizedLipVowels) {
        record MenuScore(Menu menu, double score) {}

        List<MenuScore> top3 = menuRepository.findAllByOrderByIdAsc().stream()
                .filter(m -> !m.getName().isBlank())
                .map(m -> new MenuScore(m, similarity(extractVowels(m.getName()), normalizedLipVowels)))
                .sorted((a, b) -> Comparator.<Double>reverseOrder().compare(a.score(), b.score()))
                .limit(3)
                .toList();

        if (top3.isEmpty() || top3.get(0).score() < 0.3) {
            log.info("립리딩 추천 실패 (최고점: {})",
                    top3.isEmpty() ? "없음" : String.format("%.2f", top3.get(0).score()));
            sendFailed();
            return;
        }

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < top3.size(); i++) {
            if (i > 0) json.append(",");
            MenuScore ms = top3.get(i);
            json.append(String.format("{\"id\":%d,\"name\":\"%s\",\"score\":%.2f,\"quantity\":1}",
                    ms.menu().getId(), ms.menu().getName(), ms.score()));
        }
        json.append("]");

        log.info("립리딩 후보 TOP {}: {}", top3.size(), json);

        try {
            var ws = sessionContext.getSession();
            if (ws != null && ws.isOpen()) {
                ws.sendMessage(new TextMessage("SYSTEM:LIPREADING_CANDIDATES:" + json));
            }
        } catch (Exception e) {
            log.error("립리딩 후보 WebSocket 전송 실패", e);
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

    private void sendFailed() {
        try {
            var ws = sessionContext.getSession();
            if (ws != null && ws.isOpen()) {
                ws.sendMessage(new TextMessage("SYSTEM:LIPREADING_FAILED"));
            }
        } catch (Exception e) {
            log.warn("립리딩 실패 알림 전송 실패", e);
        }
    }

    private double similarity(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        if (n == 0 && m == 0) return 1.0;
        if (n == 0 || m == 0) return 0.0;
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
