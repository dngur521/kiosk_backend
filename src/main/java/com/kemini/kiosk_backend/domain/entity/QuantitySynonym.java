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
public class QuantitySynonym {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer quantityValue; // 실제 숫자 (1, 2, 3...) / 모호한 경우 0
    private String synonym;        // "하나", "두개", "많이", "듬뿍"
}