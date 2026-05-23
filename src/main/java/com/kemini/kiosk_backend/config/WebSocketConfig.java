package com.kemini.kiosk_backend.config;

import com.kemini.kiosk_backend.handler.LipReadingFrameHandler;
import com.kemini.kiosk_backend.handler.VoiceStreamHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceStreamHandler voiceStreamHandler;
    private final LipReadingFrameHandler lipReadingFrameHandler;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(5 * 1024 * 1024); // 5MB (캔버스 프레임 대응)
        container.setMaxSessionIdleTimeout(3600000L); // 1시간
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceStreamHandler, "/ws/voice")
                .setAllowedOrigins("*");
        registry.addHandler(lipReadingFrameHandler, "/ws/lipreading")
                .setAllowedOrigins("*");
    }
}