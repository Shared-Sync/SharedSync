package com.sharedsync.shared.auth;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.sharedsync.shared.properties.SharedSyncAuthProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

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

    private String extractToken(ServerHttpRequest request) {
        var headers = request.getHeaders();
        String auth = headers.getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String query = request.getURI().getQuery();
        if (query != null) {
            // split query into key=val pairs and find token parameter (handles additional params like &roomId=...)
            String[] parts = query.split("&");
            for (String part : parts) {
                if (part.startsWith("token=")) {
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


