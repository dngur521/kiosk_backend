package com.kemini.kiosk_backend.global;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleAllExceptions(Exception e) {
        // 실제 운영 시에는 로깅을 하고 메시지는 보안상 필터링하는 것이 좋음
        return ApiResponse.error("서버 내부 오류: " + e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        return ApiResponse.error(e.getMessage());
    }
}