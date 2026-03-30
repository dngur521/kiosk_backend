package com.kemini.kiosk_backend.handler;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
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
import com.kemini.kiosk_backend.domain.entity.Menu;
import com.kemini.kiosk_backend.service.CartService;
import com.kemini.kiosk_backend.service.OrderParserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceStreamHandler extends BinaryWebSocketHandler {

    private final Map<String, ClientStream<StreamingRecognizeRequest>> sttStreams = new ConcurrentHashMap<>();
    private final Map<String, SpeechClient> speechClients = new ConcurrentHashMap<>();
    
    // 🔥 새롭게 만든 복합 주문 분석 서비스 주입
    private final OrderParserService orderParserService; 
    private final CartService cartService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("웹소켓 연결 성공: session id = {}", session.getId());
        initSpeechClient(session);
    }

    private synchronized void startSttStream(WebSocketSession session) {
        String sessionId = session.getId();
        if (sttStreams.containsKey(sessionId)) return;

        try {
            SpeechClient speechClient = speechClients.get(sessionId);
            if (speechClient == null || speechClient.isShutdown()) {
                speechClient = initSpeechClient(session);
            }

            ResponseObserver<StreamingRecognizeResponse> responseObserver = new ResponseObserver<>() {
                @Override
                public void onStart(StreamController controller) {}

                @Override
                public void onResponse(StreamingRecognizeResponse response) {
                    if (response.getResultsCount() > 0) {
                        StreamingRecognitionResult result = response.getResultsList().get(0);
                        String transcript = result.getAlternativesList().get(0).getTranscript();
                        boolean isFinal = result.getIsFinal();

                        try {
                            session.sendMessage(new TextMessage(transcript)); // 실시간 자막 전송

                            if (isFinal) {
                                log.info("🏁 최종 문장 인식: {}", transcript);
                                
                                // 🔥 [핵심 수정] 복합 주문 분석기를 통해 주문 리스트를 가져옵니다.
                                List<OrderParserService.OrderResult> orders = orderParserService.parseMultiOrder(transcript);

                                if (!orders.isEmpty()) {
                                    for (OrderParserService.OrderResult order : orders) {
                                        Menu menu = order.getMenu();
                                        int quantity = order.getQuantity();

                                        if (quantity == 0) {
                                            // '많이' 등 모호한 표현 처리
                                            session.sendMessage(new TextMessage("SYSTEM:REASK_QUANTITY:" + menu.getName()));
                                        } else {
                                            // 정상 주문 처리
                                            addToCart(sessionId, menu, quantity);
                                            session.sendMessage(new TextMessage("SYSTEM:ORDER_SUCCESS:" + menu.getName() + ":" + quantity));
                                        }
                                    }
                                } else {
                                    log.warn("❓ 인식된 메뉴가 없습니다: {}", transcript);
                                }
                            }
                        } catch (Exception e) { log.error("STT 응답 처리 중 에러", e); }
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    log.warn("STT 스트림 에러: {}", t.getMessage());
                    sttStreams.remove(sessionId);
                }

                @Override
                public void onComplete() {
                    log.info("STT 스트림 완료");
                    sttStreams.remove(sessionId);
                }
            };

            ClientStream<StreamingRecognizeRequest> clientStream = 
                    speechClient.streamingRecognizeCallable().splitCall(responseObserver);
            
            StreamingRecognitionConfig config = StreamingRecognitionConfig.newBuilder()
                    .setConfig(RecognitionConfig.newBuilder()
                            .setLanguageCode("ko-KR")
                            .setSampleRateHertz(16000)
                            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                            .build())
                    .setInterimResults(true)
                    .build();

            clientStream.send(StreamingRecognizeRequest.newBuilder().setStreamingConfig(config).build());
            sttStreams.put(sessionId, clientStream);
            log.info("🚀 새로운 STT 스트림 생성됨");

        } catch (Exception e) {
            log.error("STT 스트림 생성 실패", e);
        }
    }

    private SpeechClient initSpeechClient(WebSocketSession session) throws Exception {
        // 경로 확인: /home/hyeok/... 인지 /home/kambook/... 인지 우혁님 서버 환경에 맞추세요!
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
            startSttStream(session);
        }

        ClientStream<StreamingRecognizeRequest> clientStream = sttStreams.get(sessionId);
        if (clientStream != null) {
            clientStream.send(StreamingRecognizeRequest.newBuilder()
                    .setAudioContent(ByteString.copyFrom(message.getPayload().array()))
                    .build());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        ClientStream<StreamingRecognizeRequest> stream = sttStreams.remove(sessionId);
        if (stream != null) try { stream.closeSend(); } catch (Exception e) {}
        
        SpeechClient client = speechClients.remove(sessionId);
        if (client != null) try { client.close(); } catch (Exception e) {}
        log.info("🧹 세션 자원 정리 완료: {}", sessionId);
    }
    
    private void addToCart(String sessionId, Menu menu, int qty) {
        com.kemini.kiosk_backend.dto.response.CartItem newItem = new com.kemini.kiosk_backend.dto.response.CartItem();
        newItem.setMenuId(menu.getId());
        newItem.setMenuName(menu.getName());
        newItem.setPrice(menu.getPrice());
        newItem.setQuantity(qty);

        cartService.addToCart(sessionId, newItem);
        log.info("🛒 장바구니 추가: {} ({}개)", menu.getName(), qty);
    }
}