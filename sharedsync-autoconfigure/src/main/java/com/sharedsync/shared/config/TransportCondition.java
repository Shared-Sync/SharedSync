package com.sharedsync.shared.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * transport 설정이 이 전송 계층을 요구하는지 판단한다.
 *
 * {@code @ConditionalOnProperty(havingValue=...)} 로는 표현할 수 없다 — {@code both} 는 두 값에
 * 동시에 해당하기 때문이다. 조건은 빈이 만들어지기 전에 평가되므로 프로퍼티 객체를 쓸 수 없고,
 * Environment 에서 직접 읽는다.
 */
abstract class TransportCondition implements Condition {

    static final String PROPERTY = "sharedsync.websocket.transport";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String transport = context.getEnvironment().getProperty(PROPERTY, "stomp");
        return matches(transport.trim().toLowerCase());
    }

    abstract boolean matches(String transport);

    /** STOMP 브로커가 필요한 경우: stomp(기본) 또는 both. */
    static class Stomp extends TransportCondition {
        @Override
        boolean matches(String transport) {
            return !"websocket".equals(transport);
        }
    }

    /** raw WebSocket 핸들러가 필요한 경우: websocket 또는 both. */
    static class RawWebSocket extends TransportCondition {
        @Override
        boolean matches(String transport) {
            return "websocket".equals(transport) || "both".equals(transport);
        }
    }
}
