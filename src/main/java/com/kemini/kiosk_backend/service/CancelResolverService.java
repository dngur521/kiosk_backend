package com.kemini.kiosk_backend.service;

import org.springframework.stereotype.Service;

import com.kemini.kiosk_backend.domain.entity.CancelSynonym;
import com.kemini.kiosk_backend.domain.repository.CancelSynonymRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CancelResolverService {
    private final CancelSynonymRepository cancelSynonymRepository;

    // 특정 구역에 취소 키워드가 있는지 확인
    public boolean hasCancelKeyword(String subText) {
        return cancelSynonymRepository.findAll().stream()
                .anyMatch(s -> subText.contains(s.getSynonym()));
    }

    // 문장에 '전부', '전체' 같은 키워드가 있는지 확인
    public boolean isAllCancelRequest(String input) {
        return cancelSynonymRepository.findAll().stream()
                .filter(CancelSynonym::isAll)
                .anyMatch(s -> input.contains(s.getSynonym()));
    }
}