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

        // 프론트엔드에게 너의 진짜 세션 ID는 이거라고 알려줌
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
                                String baseUrl = "https://kemini-kiosk-api.duckdns.org"; 
                                
                                // 파서에서 이제 MenuResponseDto가 포함된 OrderResult를 반환합니다.
                                List<OrderParserService.OrderResult> orders = orderParserService.parseMultiOrder(sessionId, transcript, baseUrl);

                                if (!orders.isEmpty()) {
                                    for (OrderParserService.OrderResult order : orders) {
                                        
                                        // 1. [추천 리스트] 매칭 실패 시 (기존 유지)
                                        if (order.isUnknown()) {
                                            List<com.kemini.kiosk_backend.dto.response.MenuResponseDto> suggestions = order.getSuggestedMenus(); 
                                            if (suggestions != null && !suggestions.isEmpty()) {
                                                String json = objectMapper.writeValueAsString(suggestions);
                                                session.sendMessage(new TextMessage("SYSTEM:RECOMMEND_LIST:" + json));
                                            } else {
                                                session.sendMessage(new TextMessage("SYSTEM:UNKNOWN_COMMAND"));
                                            }
                                            break; 
                                        }

                                        // 2. 🔥 [수정] 학습된 단어 매칭 시 (CONFIRM_MATCH)
                                        if (order.isLearnedMatch() && !order.isCancel() && !order.isAllCancel()) {
                                            // order.getMenuDto()를 사용하여 이미 완성된 DTO를 꺼냅니다.
                                            com.kemini.kiosk_backend.dto.response.MenuResponseDto menuDto = order.getMenuDto();
                                            log.info("🤔 학습 데이터 매칭됨. 사용자 확인 절차 시작: {}", menuDto.getName());
                                            
                                            // 더 이상 new MenuResponseDto(...)를 호출할 필요가 없습니다. (에러 원인 제거)
                                            String json = objectMapper.writeValueAsString(menuDto);
                                            session.sendMessage(new TextMessage("SYSTEM:CONFIRM_MATCH:" + json));
                                            break; 
                                        }

                                        // 3. [전체 비우기] (기존 유지)
                                        if (order.isAllCancel()) {
                                            cartService.clearCart(sessionId);
                                            session.sendMessage(new TextMessage("SYSTEM:CLEAR_CART_SUCCESS"));
                                            break;
                                        }

                                        // 🔥 엔티티 대신 DTO를 사용합니다.
                                        com.kemini.kiosk_backend.dto.response.MenuResponseDto menuDto = order.getMenuDto();
                                        if (menuDto == null) continue;

                                        // 4. [취소 및 일반 주문]
                                        if (order.isMenuAllCancel()) {
                                            // cartService는 보통 ID를 받으므로 menuDto.getId() 사용
                                            cartService.removeFromCart(sessionId, menuDto.getId());
                                            session.sendMessage(new TextMessage("SYSTEM:CANCEL_SUCCESS:" + menuDto.getName() + ":ALL"));
                                        } else if (order.isCancel()) {
                                            cartService.updateQuantity(sessionId, menuDto.getId(), -order.getQuantity());
                                            session.sendMessage(new TextMessage("SYSTEM:CANCEL_SUCCESS:" + menuDto.getName() + ":" + order.getQuantity()));
                                        } else {
                                            if (order.getQuantity() == 0) {
                                                session.sendMessage(new TextMessage("SYSTEM:REASK_QUANTITY:" + menuDto.getName()));
                                            } else {
                                                // addToCart 헬퍼 함수가 Menu 엔티티를 받는다면, ID를 받거나 
                                                // DTO 정보를 활용하도록 내부를 살짝 수정해야 할 수 있습니다.
                                                addToCart(sessionId, menuDto, order.getQuantity());
                                                session.sendMessage(new TextMessage("SYSTEM:ORDER_SUCCESS:" + menuDto.getName() + ":" + order.getQuantity()));
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