package com.kemini.kiosk_backend.service;

import java.time.Duration;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderContextService {
    private final RedisTemplate<String, String> redisTemplate;
    private final MenuRepository menuRepository;

    private static final String CONTEXT_KEY_PREFIX = "order_context:";

    // 마지막 메뉴 ID를 Redis에 저장 (유효기간 10분 설정)
    public void updateContext(String sessionId, Long menuId) {
        redisTemplate.opsForValue().set(CONTEXT_KEY_PREFIX + sessionId, String.valueOf(menuId), Duration.ofMinutes(10));
    }

    // Redis에서 메뉴 ID를 가져와서 메뉴 엔티티 반환
    public Menu getContext(String sessionId) {
        String menuIdStr = redisTemplate.opsForValue().get(CONTEXT_KEY_PREFIX + sessionId);
        if (menuIdStr == null) return null;
        
        return menuRepository.findById(Long.parseLong(menuIdStr)).orElse(null);
    }

    public void clearContext(String sessionId) {
        redisTemplate.delete(CONTEXT_KEY_PREFIX + sessionId);
    }
}
