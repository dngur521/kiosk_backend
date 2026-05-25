package com.kemini.kiosk_backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kemini.kiosk_backend.dto.request.LipReadingResultRequest;
import com.kemini.kiosk_backend.global.ApiResponse;
import com.kemini.kiosk_backend.service.LipReadingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// @RestController // 립리딩 비활성화
// @RequestMapping("/api/lipreading")
@RequiredArgsConstructor
@Slf4j
public class LipReadingController {

    private final LipReadingService lipReadingService;

    @PostMapping("/result")
    public ResponseEntity<ApiResponse<Void>> receiveResult(@RequestBody LipReadingResultRequest request) {
        log.info("립리딩 결과 수신: vowels={}, frames={}", request.getVowelSequence(), request.getFrameCount());
        lipReadingService.processResult(request.getVowelSequence());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
