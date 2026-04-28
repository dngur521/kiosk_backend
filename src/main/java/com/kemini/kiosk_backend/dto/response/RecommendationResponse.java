package com.kemini.kiosk_backend.dto.response;

import java.util.List;

public record RecommendationResponse(
    List<MenuInfo> recommendations
) {
    public record MenuInfo(Long id, String name, String description, double score) {}
}
