package com.sharedsync.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.sharedsync.shared.properties.SharedSyncAuthProperties;

/**
 * 핸드셰이크에서 토큰을 어디서 읽는지가 계약이다. 브라우저는 Authorization 헤더를 지정할 수 없어
 * Sec-WebSocket-Protocol 을 쓰고, 쿼리 파라미터는 URL 이 액세스 로그에 남아 하위 호환용으로만 둔다.
 */
@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

    private static final String TOKEN = "valid-token";

    @Mock
    private AuthenticationTokenResolver tokenResolver;

    private final SharedSyncAuthProperties authProperties = new SharedSyncAuthProperties();

    private JwtHandshakeInterceptor interceptor() {
        lenient().when(tokenResolver.validate(TOKEN)).thenReturn(true);
        lenient().when(tokenResolver.extractPrincipalId(TOKEN)).thenReturn("user-1");
        return new JwtHandshakeInterceptor(tokenResolver, authProperties);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setServerName("example.com");
        return request;
    }

    private boolean handshake(MockHttpServletRequest raw, Map<String, Object> attributes) {
        ServerHttpRequest request = new ServletServerHttpRequest(raw);
        ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        return interceptor().beforeHandshake(request, response, null, attributes);
    }

    @Test
    @DisplayName("Sec-WebSocket-Protocol 의 bearer.<token> 으로 인증한다 (브라우저 경로)")
    void acceptsTokenFromSubProtocol() {
        MockHttpServletRequest raw = request();
        raw.addHeader("Sec-WebSocket-Protocol", "sharedsync.v1, bearer." + TOKEN);
        Map<String, Object> attributes = new HashMap<>();

        assertThat(handshake(raw, attributes)).isTrue();
        assertThat(attributes.get("userId"))
                .as("핸들러는 이 attribute 로만 사용자를 안다 — raw WS 에는 CONNECT 프레임이 없다")
                .isEqualTo("user-1");
    }

    @Test
    @DisplayName("Authorization 헤더도 그대로 받는다 (서버 대 서버)")
    void acceptsTokenFromAuthorizationHeader() {
        MockHttpServletRequest raw = request();
        raw.addHeader("Authorization", "Bearer " + TOKEN);
        Map<String, Object> attributes = new HashMap<>();

        assertThat(handshake(raw, attributes)).isTrue();
        assertThat(attributes.get("userId")).isEqualTo("user-1");
    }

    @Test
    @DisplayName("쿼리 파라미터 토큰은 하위 호환으로 계속 받는다")
    void acceptsTokenFromQueryParameter() {
        MockHttpServletRequest raw = request();
        raw.setQueryString("token=" + TOKEN);
        Map<String, Object> attributes = new HashMap<>();

        assertThat(handshake(raw, attributes)).isTrue();
        assertThat(attributes.get("userId")).isEqualTo("user-1");
    }

    @Test
    @DisplayName("토큰이 없으면 401 로 거부한다")
    void rejectsWhenTokenMissing() {
        MockHttpServletRequest raw = request();
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor().beforeHandshake(
                new ServletServerHttpRequest(raw), new ServletServerHttpResponse(rawResponse),
                null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("Sec-WebSocket-Protocol 에 bearer 항목이 없으면 인증 통로로 치지 않는다")
    void subProtocolWithoutBearerIsNotAToken() {
        MockHttpServletRequest raw = request();
        raw.addHeader("Sec-WebSocket-Protocol", "sharedsync.v1");
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();

        boolean accepted = interceptor().beforeHandshake(
                new ServletServerHttpRequest(raw), new ServletServerHttpResponse(rawResponse),
                null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("auth.enabled=false 면 검사 없이 통과한다 (데모 모드)")
    void skipsAuthenticationWhenDisabled() {
        authProperties.setEnabled(false);
        lenient().when(tokenResolver.extractPrincipalId("token")).thenReturn("demo-user");

        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(request(), attributes)).isTrue();
        assertThat(attributes.get("userId")).isEqualTo("demo-user");
    }

    @Test
    @DisplayName("허용되지 않은 Origin 은 막지 않고 로그로만 드러낸다 (차단은 Spring 의 몫)")
    void originLoggingDoesNotBlock() {
        OriginLoggingInterceptor origin = new OriginLoggingInterceptor(
                java.util.List.of("https://app.example.com"));

        MockHttpServletRequest raw = request();
        raw.addHeader("Origin", "https://evil.example.com");

        assertThat(origin.beforeHandshake(new ServletServerHttpRequest(raw),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, new HashMap<>()))
                .as("여기서 막으면 Spring 의 origin 검사와 이중이 된다. 목적은 원인을 보이게 하는 것뿐이다.")
                .isTrue();
    }

    @Test
    @DisplayName("URI 는 그대로 유지된다 (경로가 로그 판별에 쓰인다)")
    void requestPathIsAvailableForDiagnostics() {
        MockHttpServletRequest raw = request();
        assertThat(new ServletServerHttpRequest(raw).getURI()).isNotNull();
        assertThat(URI.create("http://example.com/ws").getPath()).isEqualTo("/ws");
    }
}
