package com.kemini.kiosk_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FrameBufferService {

    // 15fps × 5초 = 최대 75프레임 보관
    private static final int MAX_FRAMES = 75;

    private final ConcurrentLinkedDeque<byte[]> buffer = new ConcurrentLinkedDeque<>();

    public void addFrame(byte[] frame) {
        buffer.addLast(frame);
        while (buffer.size() > MAX_FRAMES) {
            buffer.pollFirst();
        }
    }

    public List<byte[]> drainFrames() {
        List<byte[]> frames = new ArrayList<>(buffer.size());
        byte[] frame;
        while ((frame = buffer.pollFirst()) != null) {
            frames.add(frame);
        }
        log.debug("프레임 버퍼 드레인: {}프레임", frames.size());
        return frames;
    }

    public int size() {
        return buffer.size();
    }
}
