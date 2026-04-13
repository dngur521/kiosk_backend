package com.kemini.kiosk_backend.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.entity.MenuSynonym;

@Repository
public interface MenuSynonymRepository extends JpaRepository<MenuSynonym, Long> {
    // 사용자가 말한 '아아' 같은 단어로 DB에서 데이터를 찾아오는 기능을 추가합니다.
    Optional<MenuSynonym> findBySynonym(String synonym);

    // 특정 메뉴와 시노님이 이미 존재하는지 확인
    boolean existsByMenuAndSynonym(Menu menu, String synonym);

    boolean existsBySynonym(String synonym);
}