package com.sharedsync.shared.auth;

import java.util.List;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 허용되지 않은 Origin 을 서버 로그에 남긴다.
 *
 * Origin 검사는 Spring 이 핸드셰이크 단계에서 하고 403 만 돌려준다 — 서버에는 아무 흔적이 없다.
 * 그래서 "브라우저에서만 연결이 안 된다"가 되고, 원인이 프록시인지 토큰인지 Origin 목록인지
 * 서버 쪽에서 판단할 방법이 없었다.
 *
 * 실제로 막지는 않는다(그건 Spring 의 몫이다). 어긋난 조합을 눈에 보이게 하는 것이 목적이다.
 */
@Slf4j
@RequiredArgsConstructor
public class OriginLoggingInterceptor implements HandshakeInterceptor {

    private final List<String> allowedOrigins;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst("Origin");
        if (origin == null || allowedOrigins.contains("*") || allowedOrigins.contains(origin)) {
            return true;
        }

        log.warn("[SharedSync] 허용 목록에 없는 Origin 으로 핸드셰이크 시도: {} (허용: {}). "
                        + "Spring 이 403 으로 막을 것이다 — 브라우저 클라이언트라면 "
                        + "sharedsync.websocket.allowed-origins 를 확인할 것.",
                origin, allowedOrigins);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
