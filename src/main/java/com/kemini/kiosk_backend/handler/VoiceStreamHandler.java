package com.kemini.kiosk_backend.handler;

import java.io.FileInputStream;
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
import com.kemini.kiosk_backend.service.MenuResolverService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceStreamHandler extends BinaryWebSocketHandler {

    private final Map<String, ClientStream<StreamingRecognizeRequest>> sttStreams = new ConcurrentHashMap<>();
    private final Map<String, SpeechClient> speechClients = new ConcurrentHashMap<>();
    
    private final MenuResolverService menuResolverService; 
    private final CartService cartService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("웹소켓 연결 성공: session id = {}", session.getId());
        // 처음 연결 시에는 SpeechClient만 준비해둡니다.
        initSpeechClient(session);
    }

    // 🔥 STT 스트림을 새로 생성하는 핵심 메서드
    private synchronized void startSttStream(WebSocketSession session) {
        String sessionId = session.getId();
        
        // 이미 스트림이 있다면 종료 후 생성 (혹시 모를 중복 방지)
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
                        boolean isFinal = result.getIsFinal(); // 문장이 끝났는지 확인

                        try {
                            // 1. 실시간 텍스트 전달 (기존 유지)
                            session.sendMessage(new TextMessage(transcript));

                            // 2. 🔥 [핵심 추가] 문장이 완성되었다면 메뉴 매칭 및 장바구니 담기
                            if (isFinal) {
                                log.info("최종 인식 문장: {}", transcript);
                                Menu menu = menuResolverService.resolve(transcript.trim());                

                                if (menu != null) {
                                    log.info("매칭 성공! 메뉴명: {}", menu.getName());
                                    
                                    // 1. CartItem 보따리를 새로 만듭니다.
                                    com.kemini.kiosk_backend.dto.response.CartItem newItem = 
                                        new com.kemini.kiosk_backend.dto.response.CartItem();

                                    // 2. 보따리에 메뉴 정보를 채워 넣습니다. (ID, 이름, 가격, 수량 등)
                                    newItem.setMenuId(menu.getId());
                                    newItem.setMenuName(menu.getName());
                                    newItem.setPrice(menu.getPrice());
                                    newItem.setQuantity(1); // 음성 주문 시 기본 1개
                                    // newItem.setImageName(menu.getImageName()); // 필요시 추가

                                    // 장바구니에 담기 (수량 1개 고정)
                                    cartService.addToCart(sessionId, newItem);
                                    session.sendMessage(new TextMessage("SYSTEM:ORDER_SUCCESS:" + menu.getName()));
                                }
                            }
                        } catch (Exception e) { log.error("전송 에러", e); }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("STT 스트림 에러 발생 (타임아웃 등): {}", t.getMessage());
                    // 🔥 에러 발생 시 맵에서 제거하여 다음 전송 때 새로 생성하게 함
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
            
            // 설정 전송
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
            log.info("새로운 STT 스트림이 생성되었습니다.");

        } catch (Exception e) {
            log.error("STT 스트림 생성 실패", e);
        }
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
        
        // 🔥 스트림이 없거나 닫혔다면 새로 시작!
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
        log.info("세션 자원 정리 완료: {}", sessionId);
    }
}