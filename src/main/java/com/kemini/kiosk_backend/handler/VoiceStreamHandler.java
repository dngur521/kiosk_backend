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
import com.kemini.kiosk_backend.service.CancelResolverService;
import com.kemini.kiosk_backend.service.CartService;
import com.kemini.kiosk_backend.service.OrderParserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceStreamHandler extends BinaryWebSocketHandler {

    private final Map<String, ClientStream<StreamingRecognizeRequest>> sttStreams = new ConcurrentHashMap<>();
    private final Map<String, SpeechClient> speechClients = new ConcurrentHashMap<>();
    
    private final OrderParserService orderParserService; 
    private final CartService cartService;
    private final CancelResolverService cancelResolverService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("웹소켓 연결 성공: session id = {}", session.getId());
        session.sendMessage(new TextMessage("SYSTEM:SESSION_ID:" + session.getId()));
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
                @Override public void onStart(StreamController controller) {}

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
                                String baseUrl = "https://kemini-kiosk-api.duckdns.org"; 
                                
                                // 1. 파서에서 분석 결과 리스트를 가져옵니다.
                                List<OrderParserService.OrderResult> orders = orderParserService.parseMultiOrder(sessionId, transcript, baseUrl);

                                if (!orders.isEmpty()) {
                                    // 🔥 [장바구니 로직 실행]
                                    // 확신이 있는 주문(Direct Match)은 여기서 즉시 장바구니 CRUD를 수행합니다.
                                    for (OrderParserService.OrderResult order : orders) {
                                        // 확인 모달이 필요한 경우나 알 수 없는 경우는 장바구니 처리를 건너뜁니다.
                                        if (order.isUnknown() || order.isLearnedMatch()) continue;

                                        // 전체 비우기 처리
                                        if (order.isAllCancel()) {
                                            cartService.clearCart(sessionId);
                                        } 
                                        // 일반 주문/취소 처리
                                        else if (order.getMenuDto() != null) {
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

                                    // 🔥 [핵심 수정] 모든 분석 결과를 '보따리(JSON)'로 묶어서 프론트에 딱 한 번만 보냅니다.
                                    // 이렇게 해야 프론트가 큐를 쌓아서 하나씩 모달을 띄울 수 있습니다.
                                    String ordersJson = objectMapper.writeValueAsString(orders);
                                    session.sendMessage(new TextMessage("SYSTEM:PROCESS_ORDERS:" + ordersJson));
                                    
                                    log.info("📦 복합 주문 처리 및 전송 완료 ({}건)", orders.size());
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