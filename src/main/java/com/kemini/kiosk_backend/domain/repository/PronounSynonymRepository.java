package com.kemini.kiosk_backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kemini.kiosk_backend.domain.entity.PronounSynonym;

public interface PronounSynonymRepository extends JpaRepository<PronounSynonym, Long> {
}