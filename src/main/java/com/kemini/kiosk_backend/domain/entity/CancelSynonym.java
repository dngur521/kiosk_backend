package com.kemini.kiosk_backend.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class CancelSynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String synonym;    // "취소", "빼줘", "전부", "다"
    private boolean isAll;     // '전체 취소' 여부 (전부, 전체, 다 등은 true)
}