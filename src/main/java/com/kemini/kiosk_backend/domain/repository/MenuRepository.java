package com.kemini.kiosk_backend.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kemini.kiosk_backend.domain.entity.Menu;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    // 1. 전체 조회 시 ID 순 정렬
    List<Menu> findAllByOrderByIdAsc();

    // 2. 카테고리별 조회 시 ID 순 정렬
    List<Menu> findByCategoryIdOrderByIdAsc(Long categoryId);
}