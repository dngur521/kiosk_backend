package com.kemini.kiosk_backend.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kemini.kiosk_backend.domain.entity.MenuCategory;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    // 카테고리 전체 조회 시 ID 순 정렬
    List<MenuCategory> findAllByOrderByIdAsc();
    
    // 카테고리 이름으로 중복 체크 등을 할 때 사용 가능
    boolean existsByName(String name);
}