package com.kemini.kiosk_backend.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
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
    private final MenuRepository menuRepository;

    @Transactional
    public void recordOrder(List<CartItem> items) {
        for (CartItem item : items) {
            MenuStatistics stats = statisticsRepository.findById(item.getMenuId())
                    .orElseGet(() -> {
                        Menu menu = menuRepository.findById(item.getMenuId())
                                .orElseThrow(() -> new RuntimeException("메뉴를 찾을 수 없습니다."));
                        return new MenuStatistics(menu);
                    });
            stats.increment(item.getQuantity());
            statisticsRepository.save(stats);
        }
    }

    /**
     * 🔥 데이터가 없어도 무조건 3개를 채워주는 지능형 추천 로직
     */
    @Transactional(readOnly = true)
    public List<MenuResponseDto> getTop3Menus(String categoryName, String baseUrl) {
        // 1. 통계 DB에서 주문량 많은 순으로 추출 (필터링 적용)
        List<Menu> topMenus = statisticsRepository.findAll(Sort.by(Sort.Direction.DESC, "orderCount"))
                .stream()
                .map(MenuStatistics::getMenu)
                .filter(menu -> {
                    if (categoryName == null || categoryName.isBlank()) return true;
                    return menu.getCategory().getName().equalsIgnoreCase(categoryName);
                })
                .limit(3)
                .collect(Collectors.toList());

        // 2. 만약 3개가 안 된다면? (주문 기록이 부족한 경우)
        if (topMenus.size() < 3) {
            // 이미 뽑힌 메뉴 ID 세트 (중복 방지용)
            Set<Long> pickedIds = topMenus.stream().map(Menu::getId).collect(Collectors.toSet());
            
            // 해당 카테고리(혹은 전체)의 모든 메뉴를 가져옴
            List<Menu> fallbackPool;
            if (categoryName == null || categoryName.isBlank()) {
                fallbackPool = menuRepository.findAll();
            } else {
                // 특정 카테고리 내의 메뉴들을 가져옴 (미리 정의된 Repository 메서드 활용)
                fallbackPool = menuRepository.findByCategoryName(categoryName);
            }

            // 부족한 만큼 채워 넣기
            for (Menu m : fallbackPool) {
                if (topMenus.size() >= 3) break;
                if (!pickedIds.contains(m.getId())) {
                    topMenus.add(m);
                    pickedIds.add(m.getId());
                }
            }
        }

        // 3. 최종적으로 DTO 변환
        return topMenus.stream()
                .map(m -> new MenuResponseDto(m, baseUrl))
                .collect(Collectors.toList());
    }
}