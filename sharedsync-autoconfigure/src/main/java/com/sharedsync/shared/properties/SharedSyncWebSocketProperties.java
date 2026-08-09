package com.sharedsync.shared.properties;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 설정값 하나만 바꾸면 나머지는 프레임워크가 맞춘다.
 *
 * transport/codec/sockjs 는 서로 아무 조합이나 성립하지 않는다(§normalize). 예전에는 어긋난
 * 조합을 기동 시 예외로 막았는데, 그러면 "protobuf 로 바꾸려면 설정 세 개를 함께 고쳐라"가 되어
 * 앱이 프레임워크의 내부 제약을 알아야 한다. 지금은 어긋난 값을 프레임워크가 보정하고 로그만 남긴다.
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "sharedsync.websocket")
public class SharedSyncWebSocketProperties {

    /**
     * WebSocket endpoint path
     * 예: /ws-plan
     */
    private String endpoint = "/ws-sharedsync";

    /**
     * Allowed Origins
     */
    private List<String> allowedOrigins = List.of("*");

    /**
     * wire 페이로드 인코딩. json(기본, 현행) | protobuf
     */
    private String codec = "json";

    /**
     * 전송 계층. stomp(기본, 현행) | websocket(raw WebSocket + 바이너리 프레임)
     *
     * 이것만 바꾸면 된다. codec 과 sockjs 는 normalize() 가 맞춘다.
     */
    private String transport = "stomp";

    /**
     * raw WebSocket 모드에서 서버가 보내는 ping 주기(초). 0 이면 보내지 않는다.
     *
     * 브라우저 JS 는 ping 프레임을 보낼 수 없어서(WebSocket API 에 메서드가 없다) 유휴 연결이
     * 프록시에 끊기는 것을 막으려면 서버가 먼저 보내야 한다. STOMP 에서는 하트비트가 이 역할을 했다.
     */
    private int pingInterval = 25;

    /**
     * SockJS 폴백 사용 여부.
     *
     * 바이너리 codec 과는 함께 쓸 수 없다. StompSubProtocolHandler 는 SockJS 세션이면
     * BinaryMessage 를 보내지 않고 바이트를 TextMessage 로 UTF-8 디코딩해 **예외 없이 손상**시킨다.
     * codec=protobuf 면 normalize() 가 자동으로 끈다.
     */
    private boolean sockjs = true;

    /**
     * 인바운드 프레임 최대 크기(바이트).
     *
     * 컨테이너 기본값은 8KB 라 블록을 여러 개 담은 편집이 그걸 넘으면 프레임이 쪼개지는데,
     * 핸들러는 부분 메시지를 조립하지 않는다. STOMP 는 프레임 조립을 브로커가 해줬다.
     */
    private int maxFrameSize = 256 * 1024;

    /**
     * 프레임 처리 스레드 수. 0 이면 CPU 코어 수 × 2 (최소 4).
     *
     * 프레임은 이 풀에서 처리되고 세션별로만 직렬화된다. raw WebSocket 은 핸들러가 소켓 읽기
     * 스레드에서 도는데, 거기서 DB·Redis 를 만지면 그 소켓이 통째로 막힌다.
     */
    private int dispatchThreads = 0;

    /**
     * 세션 하나가 쌓아둘 수 있는 대기 프레임 수. 넘으면 BACKPRESSURE 에러를 돌려준다.
     *
     * 무한 큐를 두면 느린 처리 뒤에 프레임이 무한히 쌓여 힙이 먼저 죽는다. 거절해서 클라이언트가
     * 알게 하는 편이 낫다 — 조용히 버리면 클라이언트는 편집이 반영된 줄 안다.
     */
    private int dispatchQueueLimit = 200;

    /**
     * 생성된 wire 스키마(.proto)를 서빙할 경로. 비우면 서빙하지 않는다.
     *
     * 기본값이 endpoint 하위인 이유: 앱의 시큐리티 화이트리스트는 보통 WebSocket 핸드셰이크
     * 경로를 이미 열어두므로("/ws/**"), 그 아래에 두면 앱이 설정을 더 고칠 필요가 없다.
     */
    private String schemaPath = "";

    /**
     * Redis Sync Settings
     */
    private RedisSync redisSync = new RedisSync();

    /**
     * 서로 어긋나는 조합을 프레임워크가 보정한다. 앱은 transport(또는 codec) 하나만 정하면 된다.
     */
    @PostConstruct
    public void normalize() {
        if (isRawWebSocket() && !isProtobuf()) {
            // raw WS 프레임은 ClientFrame/ServerFrame 이라 JSON 코덱으로는 인바운드를 해석할 수 없다.
            log.info("[SharedSync] transport=websocket 이므로 codec 을 protobuf 로 맞춘다 (설정값: {})", codec);
            codec = "protobuf";
        }
        if (isProtobuf() && sockjs) {
            // SockJS 세션은 바이너리 프레임을 보내지 못해 바이트가 UTF-8 로 디코딩되며 조용히 손상된다.
            log.info("[SharedSync] codec=protobuf 이므로 SockJS 폴백을 끈다 (바이너리 프레임과 양립 불가)");
            sockjs = false;
        }
        if (schemaPath == null || schemaPath.isBlank()) {
            schemaPath = endpoint + "/schema.proto";
        }
    }

    public boolean isRawWebSocket() {
        return "websocket".equalsIgnoreCase(transport);
    }

    public boolean isProtobuf() {
        return "protobuf".equalsIgnoreCase(codec);
    }

    @Getter
    @Setter
    public static class RedisSync {
        /**
         * Enable Redis Pub/Sub for WebSocket synchronization across multiple servers
         */
        private boolean enabled = false;

        /**
         * Redis channel name for synchronization
         */
        private String channel = "sharedsync:websocket:sync";
    }
}
