package com.kemini.kiosk_backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.domain.repository.MenuSynonymRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MenuResolverService {
    private final MenuSynonymRepository menuSynonymRepository;

    @Transactional(readOnly = true) // 🔥 이 어노테이션을 추가해서 DB 세션을 유지합니다.
    public Menu resolve(String input) {
        return menuSynonymRepository.findBySynonym(input)
                .map(synonym -> {
                    Menu menu = synonym.getMenu();
                    // 🔥 Lazy 로딩 방지를 위해 메뉴 이름을 한 번 호출해서 미리 로딩합니다.
                    menu.getName(); 
                    return menu;
                })
                .orElse(null);
    }
}