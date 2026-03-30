package com.kemini.kiosk_backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kemini.kiosk_backend.domain.entity.QuantitySynonym;

@Repository
public interface QuantitySynonymRepository extends JpaRepository<QuantitySynonym, Long> {
}