package com.kemini.kiosk_backend.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class VoiceStreamHandler extends BinaryWebSocketHandler {

    // 클라이언트가 웹소켓에 연결되었을 때
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("웹소켓 연결 성공: session id = {}", session.getId());
    }

    // 실시간 음성(Binary) 데이터를 받았을 때 핵심 로직
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        // 여기서 AI 모델(Whisper 등)로 데이터를 넘겨주게 됩니다.
        byte[] payload = message.getPayload().array();
        log.info("음성 데이터 수신 중... 크기: {} bytes", payload.length);
        
        // 일단 잘 받았다고 클라이언트에게 에코(Echo) 메시지 보내기 테스트
        // session.sendMessage(new TextMessage("서버가 데이터를 잘 받았습니다!"));
    }

    // 연결이 끊겼을 때
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("웹소켓 연결 종료: session id = {}", session.getId());
    }
}