package com.kemini.kiosk_backend.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.kemini.kiosk_backend.dto.response.CartItem;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CartService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String CART_PREFIX = "cart:";

    // 장바구니 담기 (수량 합치기 로직 포함)
    public void addToCart(String sessionId, CartItem newItem) {
        System.out.println("디버깅 - 장바구니 담기 시도: " + newItem.getMenuName());
        String key = CART_PREFIX + sessionId;
        String menuIdStr = String.valueOf(newItem.getMenuId());

        // 1. 기존에 해당 메뉴가 있는지 확인
        Object existingObj = redisTemplate.opsForHash().get(key, menuIdStr);

        if (existingObj != null) {
            // 2. 이미 있다면 수량 업데이트
            CartItem existingItem = objectMapper.convertValue(existingObj, CartItem.class);
            existingItem.setQuantity(existingItem.getQuantity() + newItem.getQuantity());
            redisTemplate.opsForHash().put(key, menuIdStr, existingItem);
        } else {
            // 3. 없다면 새로 추가
            redisTemplate.opsForHash().put(key, menuIdStr, newItem);
        }
        redisTemplate.expire(key, Duration.ofMinutes(30));
    }

    // 장바구니 전체 조회
    public List<CartItem> getCart(String sessionId) {
        String key = CART_PREFIX + sessionId;
        // Hash의 모든 Value를 가져와서 CartItem 리스트로 변환
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        return entries.values().stream()
                .map(obj -> objectMapper.convertValue(obj, CartItem.class))
                .collect(Collectors.toList());
    }

    // 특정 메뉴 삭제 (ID 기반으로 즉시 삭제)
    public void removeFromCart(String sessionId, Long menuId) {
        String key = CART_PREFIX + sessionId;
        redisTemplate.opsForHash().delete(key, String.valueOf(menuId));
    }

    public void clearCart(String sessionId) {
        redisTemplate.delete(CART_PREFIX + sessionId);
    }

    // 특정 아이템 수량 조절 (delta가 1이면 증가, -1이면 감소)
    public void updateQuantity(String sessionId, Long menuId, int delta) {
        String key = CART_PREFIX + sessionId;
        String field = String.valueOf(menuId);

        Object existingObj = redisTemplate.opsForHash().get(key, field);
        if (existingObj != null) {
            CartItem item = objectMapper.convertValue(existingObj, CartItem.class);
            int newQuantity = item.getQuantity() + delta;

            if (newQuantity <= 0) {
                // 수량이 0 이하면 장바구니에서 자동 삭제
                redisTemplate.opsForHash().delete(key, field);
            } else {
                // 수량 업데이트 후 다시 저장
                item.setQuantity(newQuantity);
                redisTemplate.opsForHash().put(key, field, item);
            }
        }
    }
}
