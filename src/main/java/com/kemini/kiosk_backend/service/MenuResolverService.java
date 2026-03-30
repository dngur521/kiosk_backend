package com.kemini.kiosk_backend.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuRepository;
import com.kemini.kiosk_backend.domain.repository.MenuSynonymRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MenuResolverService {
    private final MenuRepository menuRepository; // 👈 MenuRepository 주입 추가
    private final MenuSynonymRepository menuSynonymRepository;

    @Transactional(readOnly = true)
    public Menu resolve(String input) {
        if (input == null || input.isBlank()) return null;

        String cleanInput = input.replaceAll("\\s", "").replace(".", "");
        log.info("🔍 하이브리드 매칭 시작: [{}]", cleanInput);

        // 1단계: Menu 테이블의 '직접 이름'과 대조
        Optional<Menu> directMatch = menuRepository.findAll().stream()
                .filter(m -> cleanInput.contains(m.getName().replaceAll("\\s", "")))
                .findFirst();

        if (directMatch.isPresent()) {
            log.info("✅ 메뉴 이름 직접 매칭 성공: [{}]", directMatch.get().getName());
            return directMatch.get();
        }

        // 2단계: 별칭(Synonym) 테이블과 대조
        return menuSynonymRepository.findAll().stream()
                .filter(s -> {
                    String cleanSynonym = s.getSynonym().replaceAll("\\s", "");
                    return cleanInput.contains(cleanSynonym);
                })
                .findFirst()
                .map(synonym -> {
                    log.info("✅ 별칭 매칭 성공: [{}] -> 메뉴: [{}]", 
                            synonym.getSynonym(), synonym.getMenu().getName());
                    return synonym.getMenu();
                })
                .orElse(null);
    }
}