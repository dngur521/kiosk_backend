package com.kemini.kiosk_backend.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu; // 🔥 기존 CartItem 임포트
import com.kemini.kiosk_backend.domain.entity.MenuStatistics;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.domain.repository.MenuStatisticsRepository;
import com.kemini.kiosk_backend.dto.response.CartItem;
import com.kemini.kiosk_backend.dto.response.MenuResponseDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderStatisticsService {
    private final MenuStatisticsRepository statisticsRepository;
    private final MenuRepository menuRepository; // Menu를 찾아오기 위해 추가

    @Transactional
    public void recordOrder(List<CartItem> items) { // 🔥 CartItem 리스트를 받음
        for (CartItem item : items) {
            // 1. 통계 테이블에서 조회, 없으면 생성
            MenuStatistics stats = statisticsRepository.findById(item.getMenuId())
                    .orElseGet(() -> {
                        Menu menu = menuRepository.findById(item.getMenuId())
                                .orElseThrow(() -> new RuntimeException("메뉴를 찾을 수 없습니다."));
                        return new MenuStatistics(menu);
                    });

            // 2. 수량 업데이트
            stats.increment(item.getQuantity());
            statisticsRepository.save(stats);
        }
    }

    // 반환 타입을 List<MenuResponseDto>로 변경
    @Transactional(readOnly = true)
    public List<MenuResponseDto> getTop3Menus(String baseUrl) {
        return statisticsRepository.findAll(Sort.by(Sort.Direction.DESC, "orderCount"))
                .stream()
                .limit(3)
                .map(stats -> new MenuResponseDto(stats.getMenu(), baseUrl)) // DTO 변환 및 경로 조립
                .toList();
    }
}