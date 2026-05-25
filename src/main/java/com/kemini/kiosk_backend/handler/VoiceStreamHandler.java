package com.kemini.kiosk_backend.handler;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechSettings;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.StreamingRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;
import com.kemini.kiosk_backend.service.CancelResolverService;
import com.kemini.kiosk_backend.service.CartService;
import com.kemini.kiosk_backend.service.FrameBufferService;
import com.kemini.kiosk_backend.service.LipReadingSessionContext;
import com.kemini.kiosk_backend.service.OrderParserService;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceStreamHandler extends BinaryWebSocketHandler {

    private final Map<String, ClientStream<StreamingRecognizeRequest>> sttStreams = new ConcurrentHashMap<>();
    private final Map<String, SpeechClient> speechClients = new ConcurrentHashMap<>();
    
    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.vision-server-url}")
    private String visionServerUrl;

    private final OrderParserService orderParserService;
    private final CartService cartService;
    private final CancelResolverService cancelResolverService;
    private final ObjectMapper objectMapper;
    private final LipReadingSessionContext lipReadingSessionContext;
    private final FrameBufferService frameBufferService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("웹소켓 연결 성공: session id = {}", session.getId());
        session.sendMessage(new TextMessage("SYSTEM:SESSION_ID:" + session.getId()));
        initSpeechClient(session);
    }

    private synchronized void startSttStream(WebSocketSession session) {
        String sessionId = session.getId();
        if (sttStreams.containsKey(sessionId)) {
            log.debug("[STT] 스트림 이미 존재 — startSttStream 스킵 (session={})", sessionId);
            return;
        }

        log.info("[STT] 스트림 시작 시도 (session={})", sessionId);
        try {
            SpeechClient speechClient = speechClients.get(sessionId);
            if (speechClient == null || speechClient.isShutdown()) {
                log.info("[STT] SpeechClient 새로 생성 (session={}, 기존={})",
                        sessionId, speechClient == null ? "null" : "shutdown");
                speechClient = initSpeechClient(session);
            }

            // 이 스트림 인스턴스를 onError/onComplete에서 식별하기 위한 참조
            AtomicReference<ClientStream<StreamingRecognizeRequest>> thisStreamRef = new AtomicReference<>();

            ResponseObserver<StreamingRecognizeResponse> responseObserver = new ResponseObserver<>() {
                @Override public void onStart(StreamController controller) {
                    log.info("[STT] onStart — 스트림 활성화 (session={})", session.getId());
                }

                @Override
                public void onResponse(StreamingRecognizeResponse response) {
                    if (response.getResultsCount() > 0) {
                        StreamingRecognitionResult result = response.getResultsList().get(0);
                        String transcript = result.getAlternativesList().get(0).getTranscript();
                        boolean isFinal = result.getIsFinal();

                        try {
                            session.sendMessage(new TextMessage(transcript));

                            if (isFinal) {
                                log.info("🏁 최종 문장 인식: {}", transcript);
                                String sessionId = session.getId();

                                float confidence = result.getAlternativesList().get(0).getConfidence();
                                lipReadingSessionContext.store(session, transcript, confidence);

                                // 스트림 즉시 제거 — 재시작은 다음 오디오 청크가 도착할 때 lazily 수행
                                sttStreams.remove(sessionId);
                                SpeechClient oldClient = speechClients.remove(sessionId);
                                log.info("[STT] isFinal 후 스트림 제거 (session={})", sessionId);
                                CompletableFuture.runAsync(() -> {
                                    if (oldClient != null) try { oldClient.close(); } catch (Exception ignored) {}
                                });

                                // 1. 파서에서 분석 결과 리스트를 가져옵니다.
                                List<OrderParserService.OrderResult> orders = orderParserService.parseMultiOrder(sessionId, transcript, baseUrl);

                                // 실제로 처리 가능한 주문이 있는지 확인 (isUnknown 제외)
                                boolean hasRealOrder = orders.stream().anyMatch(o ->
                                    !o.isUnknown() &&
                                    (o.isAllCancel() || (o.getMenuDto() != null &&
                                     (o.isMenuAllCancel() || o.isCancel() || o.getQuantity() > 0))));

                                // 시노님으로 매칭된 항목이 하나라도 있으면 사용자 확인 필요
                                boolean hasSynonymMatch = orders.stream().anyMatch(o ->
                                    o.isLearnedMatch() && o.getMenuDto() != null);

                                if (hasRealOrder) {
                                    if (hasSynonymMatch) {
                                        // [시노님 매칭] 프론트에 확인 모달 요청
                                        StringBuilder json = new StringBuilder("[");
                                        boolean first = true;
                                        for (OrderParserService.OrderResult o : orders) {
                                            if (o.isUnknown() || o.getMenuDto() == null) continue;
                                            if (!first) json.append(",");
                                            json.append(String.format("{\"id\":%d,\"name\":\"%s\",\"quantity\":%d}",
                                                    o.getMenuDto().getId(), o.getMenuDto().getName(), o.getQuantity()));
                                            first = false;
                                        }
                                        json.append("]");
                                        session.sendMessage(new TextMessage("SYSTEM:CONFIRM_ORDER:" + json));
                                        log.info("❓ 시노님 매칭 확인 요청: {}", json);
                                    } else if (confidence >= 0.6f) { // TODO 테스트용 — 실서비스 전 0.8f로 복원
                                        // [고신뢰도] 립리딩 서버 호출 없이 즉시 처리합니다.
                                        for (OrderParserService.OrderResult order : orders) {
                                            if (order.isUnknown()) continue;
                                            if (order.isAllCancel()) {
                                                cartService.clearCart(sessionId);
                                            } else if (order.getMenuDto() != null) {
                                                Long menuId = order.getMenuDto().getId();
                                                if (order.isMenuAllCancel()) {
                                                    cartService.removeFromCart(sessionId, menuId);
                                                } else if (order.isCancel()) {
                                                    cartService.updateQuantity(sessionId, menuId, -order.getQuantity());
                                                } else if (order.getQuantity() > 0) {
                                                    addToCart(sessionId, order.getMenuDto(), order.getQuantity());
                                                }
                                            }
                                        }
                                        String ordersJson = objectMapper.writeValueAsString(orders);
                                        session.sendMessage(new TextMessage("SYSTEM:PROCESS_ORDERS:" + ordersJson));
                                        log.info("📦 고신뢰도 주문 즉시 처리 완료 ({}건, confidence={})", orders.size(), confidence);
                                    } else {
                                        // [저신뢰도 + NLP 성공] 립리딩 교차검증
                                        lipReadingSessionContext.storePendingOrders(orders);
                                        List<byte[]> frames = frameBufferService.drainFrames();
                                        sendFramesToPython(transcript, confidence, frames);
                                        session.sendMessage(new TextMessage("SYSTEM:LIPREADING_ANALYZING"));
                                        log.info("🔍 저신뢰도({}), 립리딩 교차검증 요청 ({}건, 프레임={})", confidence, orders.size(), frames.size());
                                    }
                                } else {
                                    // AI 추천 결과가 있으면 프론트에 직접 전송
                                    List<com.kemini.kiosk_backend.dto.response.MenuResponseDto> aiSuggestions = orders.stream()
                                        .filter(o -> o.isUnknown() && o.getSuggestedMenus() != null && !o.getSuggestedMenus().isEmpty())
                                        .flatMap(o -> o.getSuggestedMenus().stream())
                                        .toList();

                                    if (!aiSuggestions.isEmpty()) {
                                        StringBuilder json = new StringBuilder("[");
                                        for (int i = 0; i < aiSuggestions.size(); i++) {
                                            if (i > 0) json.append(",");
                                            var m = aiSuggestions.get(i);
                                            json.append(String.format("{\"id\":%d,\"name\":\"%s\",\"quantity\":1}", m.getId(), m.getName()));
                                        }
                                        json.append("]");
                                        session.sendMessage(new TextMessage("SYSTEM:AI_CANDIDATES:" + json));
                                        log.info("🤖 AI 추천 결과 직접 전송 ({}건): {}", aiSuggestions.size(), json);
                                    } else {
                                        // AI도 실패 → 립리딩 추천
                                        List<byte[]> frames = frameBufferService.drainFrames();
                                        sendFramesToPython(transcript, confidence, frames);
                                        session.sendMessage(new TextMessage("SYSTEM:LIPREADING_ANALYZING"));
                                        log.info("🔍 NLP·AI 매칭 실패, 립리딩 추천 모드 (프레임={})", frames.size());
                                    }
                                }
                            }
                        } catch (Exception e) { 
                            log.error("STT 응답 처리 에러", e); 
                        }
                    }
                }
                @Override public void onError(Throwable t) {
                    // 이미 새 스트림으로 교체된 경우 stale 콜백 무시
                    if (sttStreams.get(sessionId) != thisStreamRef.get()) {
                        log.debug("[STT] onError 무시 — stale 스트림 콜백 (session={})", session.getId());
                        return;
                    }
                    log.warn("[STT] onError — 스트림 제거 (session={}, error={})",
                            session.getId(), t.getMessage());
                    sttStreams.remove(sessionId);
                    SpeechClient oldClient = speechClients.remove(sessionId);
                    CompletableFuture.runAsync(() -> {
                        if (oldClient != null) try { oldClient.close(); } catch (Exception ignored) {}
                    });
                }
                @Override public void onComplete() {
                    if (sttStreams.get(sessionId) != thisStreamRef.get()) {
                        log.debug("[STT] onComplete 무시 — stale 스트림 콜백 (session={})", session.getId());
                        return;
                    }
                    log.debug("[STT] onComplete (session={})", session.getId());
                }
            };

            ClientStream<StreamingRecognizeRequest> clientStream =
                    speechClient.streamingRecognizeCallable().splitCall(responseObserver);
            thisStreamRef.set(clientStream);
            
            StreamingRecognitionConfig config = StreamingRecognitionConfig.newBuilder()
                    .setConfig(RecognitionConfig.newBuilder()
                            .setLanguageCode("ko-KR")
                            .setSampleRateHertz(16000)
                            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                            .build())
                    .setInterimResults(true)
                    .setSingleUtterance(true)
                    .build();

            clientStream.send(StreamingRecognizeRequest.newBuilder().setStreamingConfig(config).build());
            sttStreams.put(sessionId, clientStream);
            log.info("[STT] 스트림 생성 완료 (session={})", sessionId);

        } catch (Exception e) { log.error("[STT] 스트림 생성 실패 (session={})", sessionId, e); }
    }

    private SpeechClient initSpeechClient(WebSocketSession session) throws Exception {
        String jsonPath = "/home/kambook/google-key.json"; 
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(jsonPath));
        SpeechSettings settings = SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
        SpeechClient client = SpeechClient.create(settings);
        speechClients.put(session.getId(), client);
        return client;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        if (!sttStreams.containsKey(sessionId)) {
            log.info("[STT] 오디오 수신 시 스트림 없음 — 재시작 트리거 (session={})", sessionId);
            startSttStream(session);
        }

        ClientStream<StreamingRecognizeRequest> clientStream = sttStreams.get(sessionId);
        if (clientStream != null) {
            try {
                clientStream.send(StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(message.getPayload().array()))
                        .build());
            } catch (Exception e) {
                log.warn("[STT] 오디오 전송 실패 — 스트림 제거 후 재시작 (session={}, error={})",
                        sessionId, e.getMessage());
                sttStreams.remove(sessionId);
                speechClients.remove(sessionId);
                startSttStream(session);
            }
        } else {
            log.warn("[STT] startSttStream 후에도 스트림 null (session={})", sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanup(session.getId());
    }

    private void sendFramesToPython(String text, float confidence, List<byte[]> frames) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. STT 정보를 REST로 먼저 전달
                Map<String, Object> body = Map.of("text", text, "confidence", confidence);
                restTemplate.postForObject(visionServerUrl + "/stt", body, String.class);
                log.info("Python STT 알림 전송 완료: {}", text);

                if (frames.isEmpty()) {
                    log.warn("전송할 프레임 없음 — 카메라 버퍼 비어있음");
                    return;
                }

                // 2. Python /ws/camera WebSocket에 연결해서 프레임 전송
                StandardWebSocketClient wsClient = new StandardWebSocketClient();
                String wsUrl = visionServerUrl.replaceFirst("^https", "wss")
                                              .replaceFirst("^http", "ws") + "/ws/camera";

                WebSocketSession pySession = wsClient.execute(
                    new AbstractWebSocketHandler() {},
                    wsUrl
                ).get();

                for (byte[] frame : frames) {
                    if (pySession.isOpen()) {
                        pySession.sendMessage(new BinaryMessage(frame));
                    }
                }
                pySession.close();
                log.info("Python 카메라 WebSocket 프레임 전송 완료: {}프레임", frames.size());
            } catch (Exception e) {
                log.warn("Python 프레임 전송 실패 (무시): {}", e.getMessage());
            }
        });
    }

    private void cleanup(String sessionId) {
        ClientStream<StreamingRecognizeRequest> stream = sttStreams.remove(sessionId);
        if (stream != null) try { stream.closeSend(); } catch (Exception e) {}
        SpeechClient client = speechClients.remove(sessionId);
        if (client != null) try { client.close(); } catch (Exception e) {}
    }
    
    private void addToCart(String sessionId, com.kemini.kiosk_backend.dto.response.MenuResponseDto menuDto, int qty) {
        // 장바구니에 담을 아이템 객체 생성
        com.kemini.kiosk_backend.dto.response.CartItem newItem = new com.kemini.kiosk_backend.dto.response.CartItem();
        
        // 🔥 엔티티 대신 DTO에서 값을 꺼내서 세팅합니다.
        newItem.setMenuId(menuDto.getId());
        newItem.setMenuName(menuDto.getName());
        newItem.setPrice(menuDto.getPrice());
        newItem.setQuantity(qty);

        // 실제 장바구니 서비스 호출 (Redis 등에 저장)
        cartService.addToCart(sessionId, newItem);
        
        log.info("🛒 장바구니 추가 완료: {} ({}개)", menuDto.getName(), qty);
    }
}