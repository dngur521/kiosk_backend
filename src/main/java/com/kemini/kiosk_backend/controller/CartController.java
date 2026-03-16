package com.kemini.kiosk_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.response.CartItem;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.CartService; // 추가됨

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    // 장바구니 담기
    @PostMapping("/{sessionId}")
    public ApiResponse<String> addToCart(@PathVariable String sessionId, @RequestBody CartItem item) {
        cartService.addToCart(sessionId, item);
        return ApiResponse.success("장바구니 담기 성공!", null);
    }

    // 장바구니 조회
    @GetMapping("/{sessionId}")
    public ApiResponse<List<CartItem>> getCart(@PathVariable String sessionId) {
        List<CartItem> cart = cartService.getCart(sessionId);
        return ApiResponse.success(cart);
    }

    // 특정 메뉴 삭제
    @DeleteMapping("/{sessionId}/{menuId}")
    public ApiResponse<String> removeFromCart(@PathVariable String sessionId, @PathVariable Long menuId) {
        cartService.removeFromCart(sessionId, menuId);
        return ApiResponse.success("메뉴 삭제 성공!", null);
    }

    // 장바구니 전체 초기화
    @DeleteMapping("/{sessionId}")
    public ApiResponse<String> clearCart(@PathVariable String sessionId) {
        cartService.clearCart(sessionId);
        return ApiResponse.success("장바구니가 비워졌습니다.", null);
    }

    // 수량 변경 API
    @PatchMapping("/{sessionId}/{menuId}")
    public ApiResponse<List<CartItem>> updateQuantity(
            @PathVariable String sessionId, 
            @PathVariable Long menuId, 
            @RequestParam int delta) {
        
        cartService.updateQuantity(sessionId, menuId, delta);
        List<CartItem> updatedCart = cartService.getCart(sessionId);
        return ApiResponse.success("수량이 변경되었습니다.", updatedCart);
    }
}