package com.kemini.kiosk_backend.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import com.kemini.kiosk_backend.service.FrameBufferService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class LipReadingFrameHandler extends BinaryWebSocketHandler {

    private final FrameBufferService frameBufferService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("카메라 프레임 WebSocket 연결: {}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        frameBufferService.addFrame(message.getPayload().array());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("카메라 프레임 WebSocket 종료: {}", session.getId());
    }
}
