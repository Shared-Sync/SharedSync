package com.sharedsync.shared.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.sharedsync.shared.codec.ProtoSyncCodec;
import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.codec.SyncDescriptors;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 생성된 wire 스키마(.proto 텍스트)를 서빙한다.
 *
 * 클라이언트는 이 응답 바이트로 코드를 생성하고, **같은 바이트**의 해시를 Join 에 실어 보낸다.
 * 해시를 디스크립터가 아니라 텍스트에서 뽑는 이유가 이것이다 — 서버가 내려준 바로 그 바이트를
 * 클라가 해시하므로 protoc 산출 디스크립터의 인코딩 차이에 영향받지 않는다.
 *
 * 경로가 설정값이라 애노테이션 매핑 대신 RouterFunction 으로 등록한다. 기본 경로는 WebSocket
 * endpoint 하위(예: /ws/schema.proto)라, 앱의 시큐리티 화이트리스트가 핸드셰이크 경로를 이미
 * 열어두었다면 추가 설정 없이 그대로 열린다.
 */
@Slf4j
public class SyncSchemaController {

    private static final String HASH_HEADER = "X-SharedSync-Schema-Hash";

    private final SyncCodec codec;
    private final SharedSyncWebSocketProperties props;

    /** 코덱이 JSON 이어도 APT 가 스키마를 만들어 두었다면 서빙한다. 없으면 null 로 남고 404 가 된다. */
    private volatile SyncDescriptors descriptors;

    public SyncSchemaController(SyncCodec codec, SharedSyncWebSocketProperties props) {
        this.codec = codec;
        this.props = props;
    }

    public RouterFunction<ServerResponse> routes() {
        String path = props.getSchemaPath();
        log.info("[SharedSync] wire 스키마 서빙 경로: {}", path);
        return RouterFunctions.route()
                .GET(path, request -> {
                    SyncDescriptors resolved = resolve();
                    if (resolved == null) {
                        return ServerResponse.notFound().build();
                    }
                    String hash = resolved.getSchemaHash();
                    // ETag 로 해시를 노출해 두면 클라이언트 빌드가 재생성 여부를 값싸게 판단할 수 있다.
                    return ServerResponse.ok()
                            .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                            .header(HASH_HEADER, hash)
                            // 교차 출처에서는 이 선언이 없으면 브라우저가 커스텀 헤더를 감춘다.
                            // 클라이언트는 해시를 직접 계산하는 편이 맞지만, 헤더를 읽으려다
                            // null 을 받아 빈 해시로 Join 하는 실수를 막아준다.
                            .header("Access-Control-Expose-Headers", HASH_HEADER + ", ETag")
                            .eTag(hash)
                            .body(resolved.getProtoText());
                })
                .build();
    }

    private SyncDescriptors resolve() {
        if (descriptors != null) {
            return descriptors;
        }
        if (codec instanceof ProtoSyncCodec proto) {
            descriptors = proto.getDescriptors();
            return descriptors;
        }
        try {
            descriptors = new SyncDescriptors();
        } catch (RuntimeException e) {
            // APT 가 스키마를 만들지 않은 앱이다. 이 엔드포인트만 없을 뿐 나머지는 정상 동작한다.
            log.debug("[SharedSync] wire 스키마가 없어 서빙하지 않는다: {}", e.getMessage());
        }
        return descriptors;
    }
}
