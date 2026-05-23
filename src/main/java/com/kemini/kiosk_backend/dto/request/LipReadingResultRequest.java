package com.kemini.kiosk_backend.dto.request;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Getter
@NoArgsConstructor
public class LipReadingResultRequest {

    @JsonProperty("vowel_sequence")
    private List<String> vowelSequence;

    @JsonProperty("frame_count")
    private int frameCount;

    private String timestamp;
}
