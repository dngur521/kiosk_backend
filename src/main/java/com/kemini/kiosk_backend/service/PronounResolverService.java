package com.kemini.kiosk_backend.service;

import org.springframework.stereotype.Service;

import com.kemini.kiosk_backend.domain.repository.PronounSynonymRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PronounResolverService {
    private final PronounSynonymRepository pronounSynonymRepository;

    public boolean hasPronoun(String input) {
        return pronounSynonymRepository.findAll().stream()
                .anyMatch(s -> input.contains(s.getSynonym()));
    }
}
