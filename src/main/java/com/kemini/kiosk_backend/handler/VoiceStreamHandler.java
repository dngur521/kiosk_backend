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
import com.kemini.kiosk_backend.service.CancelResolverService;
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
    
    private final OrderParserService orderParserService; 
    private final CartService cartService;
    private final CancelResolverService cancelResolverService;

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
                            session.sendMessage(new TextMessage(transcript)); 

                            if (isFinal) {
                                log.info("🏁 최종 문장 인식: {}", transcript);
                                String sessionId = session.getId();
                                
                                // 파서를 통해 분석된 결과 리스트
                                List<OrderParserService.OrderResult> orders = orderParserService.parseMultiOrder(sessionId, transcript);

                                if (!orders.isEmpty()) {
                                    for (OrderParserService.OrderResult order : orders) {
                                        
                                        // 1. [장바구니 전체 비우기] 메뉴 상관없이 "싹 다 취소해"
                                        if (order.isAllCancel()) {
                                            log.info("🗑️ 장바구니 전체 비우기 실행");
                                            cartService.clearCart(sessionId);
                                            session.sendMessage(new TextMessage("SYSTEM:CLEAR_CART_SUCCESS"));
                                            break; // 전체 삭제 시 루프 종료
                                        }

                                        Menu menu = order.getMenu();
                                        if (menu == null) continue; // 안전장치

                                        // 2. [특정 메뉴 전체 삭제] "아메리카노 전부 빼줘"
                                        if (order.isMenuAllCancel()) {
                                            log.info("🗑️ 특정 메뉴 항목 삭제: {}", menu.getName());
                                            cartService.removeFromCart(sessionId, menu.getId());
                                            session.sendMessage(new TextMessage("SYSTEM:CANCEL_SUCCESS:" + menu.getName() + ":ALL"));
                                        } 
                                        
                                        // 3. [특정 메뉴 수량 차감] "아메리카노 하나 빼줘"
                                        else if (order.isCancel()) {
                                            log.info("➖ 수량 차감: {} ({}개)", menu.getName(), order.getQuantity());
                                            cartService.updateQuantity(sessionId, menu.getId(), -order.getQuantity());
                                            session.sendMessage(new TextMessage("SYSTEM:CANCEL_SUCCESS:" + menu.getName() + ":" + order.getQuantity()));
                                        } 
                                        
                                        // 4. [일반 주문 추가]
                                        else {
                                            if (order.getQuantity() == 0) {
                                                // "많이" 같은 모호한 수량 대응
                                                session.sendMessage(new TextMessage("SYSTEM:REASK_QUANTITY:" + menu.getName()));
                                            } else {
                                                addToCart(sessionId, menu, order.getQuantity());
                                                session.sendMessage(new TextMessage("SYSTEM:ORDER_SUCCESS:" + menu.getName() + ":" + order.getQuantity()));
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) { 
                            log.error("STT 응답 처리 에러", e); 
                        }
                    }
                }
                @Override public void onError(Throwable t) { sttStreams.remove(sessionId); }
                @Override public void onComplete() { sttStreams.remove(sessionId); }
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

        } catch (Exception e) { log.error("STT 스트림 생성 실패", e); }
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
        if (!sttStreams.containsKey(sessionId)) startSttStream(session);

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