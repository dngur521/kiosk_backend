package com.kemini.kiosk_backend.service;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 키오스크는 단일 사용자이므로 volatile 필드로 현재 세션 컨텍스트를 공유.
 * STT 완료 시 VoiceStreamHandler가 저장, 립리딩 콜백 시 LipReadingService가 읽음.
 */
@Component
public class LipReadingSessionContext {

    private volatile WebSocketSession session;
    private volatile String sttText;
    private volatile float sttConfidence;
    // confidence < 0.8일 때 장바구니 추가를 보류한 주문 목록
    private volatile List<OrderParserService.OrderResult> pendingOrders = Collections.emptyList();
    // STT 한 번당 첫 번째 립리딩 결과만 처리하기 위한 플래그
    private volatile boolean lipReadingConsumed = false;

    public void store(WebSocketSession session, String sttText, float sttConfidence) {
        this.session = session;
        this.sttText = sttText;
        this.sttConfidence = sttConfidence;
        this.lipReadingConsumed = false; // 새 STT마다 초기화
    }

    public boolean tryConsumeLipReading() {
        if (lipReadingConsumed) return false;
        lipReadingConsumed = true;
        return true;
    }

    public void storePendingOrders(List<OrderParserService.OrderResult> orders) {
        this.pendingOrders = List.copyOf(orders);
    }

    public void clearPendingOrders() {
        this.pendingOrders = Collections.emptyList();
    }

    public boolean hasPendingOrders() { return !pendingOrders.isEmpty(); }

    public WebSocketSession getSession() { return session; }
    public String getSttText() { return sttText; }
    public float getSttConfidence() { return sttConfidence; }
    public List<OrderParserService.OrderResult> getPendingOrders() { return pendingOrders; }
}
