package com.kemini.kiosk_backend.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kemini.kiosk_backend.domain.entity.Menu;

public interface MenuRepository extends JpaRepository<Menu, Long> {
    // 전체 조회 시 ID 순 정렬
    List<Menu> findAllByOrderByIdAsc();

    // 카테고리별 조회 시 ID 순 정렬
    List<Menu> findByCategoryIdOrderByIdAsc(Long categoryId);

    // 이름에 특정 단어가 포함된 메뉴 리스트 검색
    List<Menu> findByNameContaining(String name);

    // 카테고리 이름으로 메뉴 리스트 조회
    List<Menu> findByCategoryName(String categoryName);

}