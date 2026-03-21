package com.kemini.kiosk_backend.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kemini.kiosk_backend.domain.entity.MenuSynonym;

@Repository
public interface MenuSynonymRepository extends JpaRepository<MenuSynonym, Long> {
    // 사용자가 말한 '아아' 같은 단어로 DB에서 데이터를 찾아오는 기능을 추가합니다.
    Optional<MenuSynonym> findBySynonym(String synonym);
}