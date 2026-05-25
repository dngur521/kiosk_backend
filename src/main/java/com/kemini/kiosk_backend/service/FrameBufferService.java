package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

// @Service // 립리딩 비활성화
@Slf4j
public class FrameBufferService {

    // 15fps × 7초 = 최대 105프레임 보관 (STT 트리거 기준 -7초까지 커버)
    private static final int MAX_FRAMES = 105;
    // drain 시 마지막 1초(15프레임) 제외 — STT 이후 무음 구간 제거
    private static final int TAIL_DROP_FRAMES = 15;

    private final ConcurrentLinkedDeque<byte[]> buffer = new ConcurrentLinkedDeque<>();

    public void addFrame(byte[] frame) {
        buffer.addLast(frame);
        while (buffer.size() > MAX_FRAMES) {
            buffer.pollFirst();
        }
    }

    public List<byte[]> drainFrames() {
        List<byte[]> all = new ArrayList<>(buffer.size());
        byte[] frame;
        while ((frame = buffer.pollFirst()) != null) {
            all.add(frame);
        }
        // 마지막 TAIL_DROP_FRAMES개 제거 (발화 후 무음 구간)
        int sendCount = Math.max(0, all.size() - TAIL_DROP_FRAMES);
        List<byte[]> frames = all.subList(0, sendCount);
        log.debug("프레임 버퍼 드레인: 전체 {}프레임 중 {}프레임 전송 (뒤 {}프레임 제거)",
                all.size(), frames.size(), TAIL_DROP_FRAMES);
        return new ArrayList<>(frames);
    }

    public int size() {
        return buffer.size();
    }
}
