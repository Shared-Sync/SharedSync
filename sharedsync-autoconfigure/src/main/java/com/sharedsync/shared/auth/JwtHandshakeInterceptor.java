package com.sharedsync.shared.auth;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.sharedsync.shared.properties.SharedSyncAuthProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    /** 브라우저가 토큰을 실어 보낼 수 있는 유일한 헤더성 통로. */
    public static final String BEARER_PROTOCOL_PREFIX = "bearer.";

    private final AuthenticationTokenResolver tokenResolver;
    private final SharedSyncAuthProperties authProperties; // ← 추가됨

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {

        // 🚀 데모 모드: 인증 완전 비활성화
        if (!authProperties.isEnabled()) {
            attributes.put("userId", tokenResolver.extractPrincipalId("token"));
            return true;
        }

        try {
            String token = extractToken(request);
            if (token == null || token.isBlank()) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            if (!tokenResolver.validate(token)) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            attributes.put("userId", tokenResolver.extractPrincipalId(token));
            return true;

        } catch (Exception e) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                               WebSocketHandler h, Exception ex) {}

    /**
     * {@code sharedsync.v1, bearer.<token>} 에서 토큰을 꺼낸다.
     *
     * 서버는 클라이언트가 제시한 것 중 하나를 골라 응답해야 하는데(고르지 않으면 브라우저가 연결을
     * 끊는다) 토큰 쪽을 고르면 그 값이 응답 헤더로 되돌아간다. 그래서 핸들러는 sharedsync.v1 만
     * 지원 목록에 두고, 토큰 항목은 여기서 읽기만 한다.
     */
    private String extractFromSubProtocol(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String candidate : header.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.startsWith(BEARER_PROTOCOL_PREFIX)) {
                String token = trimmed.substring(BEARER_PROTOCOL_PREFIX.length()).trim();
                return token.isEmpty() ? null : token;
            }
        }
        return null;
    }

    /**
     * 토큰 위치는 세 곳이다. 우선순위가 곧 권장 순서다.
     *
     * <ol>
     *   <li>Authorization 헤더 — 서버 대 서버에서만 쓸 수 있다. 브라우저 WebSocket API 로는
     *       핸드셰이크 헤더를 지정할 수 없다.</li>
     *   <li>Sec-WebSocket-Protocol — 브라우저에서 쓸 수 있는 유일한 헤더성 통로.
     *       {@code new WebSocket(url, ["sharedsync.v1", "bearer." + token])} 형태로 보낸다.</li>
     *   <li>쿼리 파라미터 — 하위 호환용. URL 은 nginx·톰캣 액세스 로그에 그대로 남으므로
     *       토큰이 로그에 적힌다. 쓰이면 경고를 남긴다.</li>
     * </ol>
     */
    private String extractToken(ServerHttpRequest request) {
        var headers = request.getHeaders();
        String auth = headers.getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }

        String fromProtocol = extractFromSubProtocol(headers.getFirst("Sec-WebSocket-Protocol"));
        if (fromProtocol != null) {
            return fromProtocol;
        }

        String query = request.getURI().getQuery();
        if (query != null) {
            // split query into key=val pairs and find token parameter (handles additional params like &roomId=...)
            String[] parts = query.split("&");
            for (String part : parts) {
                if (part.startsWith("token=")) {
                    log.warn("[SharedSync] 쿼리 파라미터로 토큰을 받았다. URL 은 액세스 로그에 그대로 "
                            + "기록되므로 Sec-WebSocket-Protocol(\"bearer.<token>\") 로 옮길 것.");
                    String val = part.substring(6).trim();
                    try {
                        // decode in case token was URL-encoded
                        return java.net.URLDecoder.decode(val, java.nio.charset.StandardCharsets.UTF_8.name());
                    } catch (Exception e) {
                        return val;
                    }
                }
            }
        }
        return null;
    }
}


