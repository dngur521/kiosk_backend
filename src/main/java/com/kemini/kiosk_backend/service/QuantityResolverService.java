package com.kemini.kiosk_backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kemini.kiosk_backend.domain.repository.QuantitySynonymRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuantityResolverService {
    private final QuantitySynonymRepository quantitySynonymRepository;

    @Transactional(readOnly = true)
    public Integer resolveQuantity(String input) {
        if (input == null || input.isBlank()) return null;
        
        String cleanInput = input.replaceAll("\\s", "").replace(".", "");

        // 1단계: DB의 한글 별칭 대조 (긴 단어 우선 매칭)
        Integer synonymQty = quantitySynonymRepository.findAll().stream()
                .sorted((a, b) -> Integer.compare(b.getSynonym().length(), a.getSynonym().length()))
                .filter(s -> cleanInput.contains(s.getSynonym().replaceAll("\\s", "")))
                .findFirst()
                .map(s -> s.getQuantityValue())
                .orElse(null);

        if (synonymQty != null) {
            log.info("🔢 한글 수량 매칭 성공: {}", synonymQty);
            return synonymQty;
        }

        // 2단계: 🔥 아라비아 숫자(21, 5 등) 추출 로직 추가
        // 정규표현식으로 문장에서 숫자 부분만 찾아냅니다.
        Pattern pattern = Pattern.compile("\\d+"); // 하나 이상의 숫자 패턴
        Matcher matcher = pattern.matcher(cleanInput);

        if (matcher.find()) {
            try {
                int numericQty = Integer.parseInt(matcher.group());
                log.info("🔢 아라비아 숫자 매칭 성공: {}", numericQty);
                return numericQty;
            } catch (NumberFormatException e) {
                log.error("숫자 변환 실패: {}", matcher.group());
            }
        }

        return null;
    }
}