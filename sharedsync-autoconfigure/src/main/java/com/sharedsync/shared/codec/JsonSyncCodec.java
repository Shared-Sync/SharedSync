package com.sharedsync.shared.codec;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 기본 코덱. 현행 JSON wire 포맷을 그대로 유지한다.
 *
 * content-type 을 application/json 으로 두면 StompSubProtocolHandler 가
 * (octet-stream 이 아니므로) TextMessage 로 내보내고, 바이트가 그대로 UTF-8 JSON 이라
 * 클라이언트가 보는 프레임은 MappingJackson2MessageConverter 를 쓰던 때와 동일하다.
 *
 * 빈 등록은 {@link com.sharedsync.shared.autoConfig.SharedSyncAutoConfig} 의 @Bean 메서드가 한다.
 * @Component + @ConditionalOnMissingBean 조합은 동작하지 않는다 — 그 조건은 오토컨피그의
 * @Bean 메서드에서만 평가되므로, 컴포넌트 스캔 대상에 붙이면 빈이 아예 등록되지 않는다.
 */
@RequiredArgsConstructor
public class JsonSyncCodec implements SyncCodec {

    private final ObjectMapper objectMapper;

    @Override
    public byte[] encode(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        if (payload instanceof byte[] raw) {
            return raw;
        }
        try {
            return objectMapper.writeValueAsBytes(toJsonShape(payload));
        } catch (Exception e) {
            throw new IllegalStateException("JSON 인코딩 실패: " + payload.getClass().getName(), e);
        }
    }

    /**
     * 타입화된 엔벨로프를 기존 wire 포맷 그대로의 맵으로 편다.
     * W&lt;Entity&gt;Response 등 그 외의 값은 예전처럼 Jackson 이 그대로 직렬화한다.
     */
    private Object toJsonShape(Object payload) {
        if (payload instanceof SyncOutbound.Entities entities) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("eventId", entities.eventId() == null ? "" : entities.eventId());
            map.put("action", entities.action());
            map.put("entity", entities.entity());
            if (entities.isUndoRedo()) {
                map.put("isUndoRedo", true);
            }
            map.put(entities.dtoFieldName(), entities.dtos());
            return map;
        }
        if (payload instanceof SyncOutbound.Presence presence) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uid", presence.uid());
            map.put("userInfo", presence.userInfo());
            map.put("users", presence.users());
            map.put("action", presence.action());
            return map;
        }
        return payload;
    }


    @Override
    public MimeType contentType() {
        return MimeTypeUtils.APPLICATION_JSON;
    }
}
