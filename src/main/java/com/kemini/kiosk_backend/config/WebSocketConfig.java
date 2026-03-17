package com.kemini.kiosk_backend.config;

import com.kemini.kiosk_backend.handler.VoiceStreamHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceStreamHandler voiceStreamHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceStreamHandler, "/ws/voice") // 웹소켓 접속 엔드포인트
                .setAllowedOrigins("*"); // 테스트를 위해 모든 도메인 허용 (나중에 DuckDNS 주소로 제한 가능)
    }
}