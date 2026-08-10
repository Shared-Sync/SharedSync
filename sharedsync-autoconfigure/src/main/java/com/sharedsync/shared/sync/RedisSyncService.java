package com.sharedsync.shared.sync;

import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 편집·프레즌스 메시지를 채널마다 인코딩해 내보낸다.
 *
 * 채널이 둘 이상이면(STOMP+JSON 과 raw WS+protobuf 동시 운영) 같은 논리 메시지를 채널 수만큼
 * 인코딩해 발행한다. 인코딩은 여전히 채널당 한 번뿐이고, 그 뒤로는 바이트로만 다룬다.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisSyncService {

    private final RedisTemplate<String, RedisSyncMessage> redisSyncTemplate;
    private final List<SyncChannel> channels;
    private final SharedSyncWebSocketProperties props;

    /**
     * 메시지를 모든 채널로 발행한다.
     *
     * Redis 동기화가 켜져 있으면 각 인스턴스가 자기 채널로 내보내고, 꺼져 있으면 로컬에서 바로 보낸다.
     */
    public void publish(String destination, Object payload) {
        for (SyncChannel channel : channels) {
            byte[] encoded = channel.codec().encode(payload);

            if (!props.getRedisSync().isEnabled()) {
                channel.transport().send(destination, encoded, channel.codec().contentType());
                continue;
            }
            redisSyncTemplate.convertAndSend(props.getRedisSync().getChannel(), RedisSyncMessage.builder()
                    .destination(destination)
                    .payload(encoded)
                    .contentType(channel.codec().contentType().toString())
                    .channel(channelTag(channel))
                    .build());
        }
    }

    /**
     * 특정 세션에게만 보낸다 (프레즌스 초기 스냅샷 등).
     *
     * 대상 세션이 다른 인스턴스에 붙어 있을 수 있으므로 브로드캐스트와 마찬가지로 Redis 를 경유한다.
     * 그 세션이 어느 채널인지는 발행 시점에 알 수 없으므로 모든 채널로 보내고, 대상이 아닌 채널의
     * transport 는 자기 세션이 아니라 조용히 무시한다.
     */
    public void sendToSession(String user, String sessionId, String destination, Object payload) {
        for (SyncChannel channel : channels) {
            byte[] encoded = channel.codec().encode(payload);

            if (!props.getRedisSync().isEnabled()) {
                channel.transport().sendToSession(user, sessionId, destination, encoded,
                        channel.codec().contentType());
                continue;
            }
            redisSyncTemplate.convertAndSend(props.getRedisSync().getChannel(), RedisSyncMessage.builder()
                    .destination(destination)
                    .payload(encoded)
                    .contentType(channel.codec().contentType().toString())
                    .channel(channelTag(channel))
                    .targetSessionId(sessionId)
                    .targetUserId(user)
                    .build());
        }
    }

    /** Redis 로부터 받은 메시지를 이 인스턴스의 해당 채널로 전달한다. */
    public void handleMessage(RedisSyncMessage message) {
        try {
            byte[] payload = message.getPayload();

            log.debug("Redis로부터 웹소켓 동기화 메시지 수신: destination={}, bytes={}, channel={}, targetSessionId={}",
                    message.getDestination(),
                    payload != null ? payload.length : 0,
                    message.getChannel(),
                    message.getTargetSessionId());

            for (SyncChannel channel : channels) {
                if (!matches(channel, message)) {
                    continue;
                }
                MimeType contentType = resolveContentType(message.getContentType(), channel);

                if (message.getTargetSessionId() != null) {
                    // 대상 세션이 이 인스턴스에 없으면 각 transport 구현이 알아서 무시한다.
                    channel.transport().sendToSession(
                            message.getTargetUserId(),
                            message.getTargetSessionId(),
                            message.getDestination(),
                            payload,
                            contentType);
                    continue;
                }
                channel.transport().send(message.getDestination(), payload, contentType);
            }
        } catch (Exception e) {
            log.error("웹소켓 메시지 전달 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 채널이 하나뿐이면 태그를 붙이지 않는다.
     *
     * 롤링 배포 중에는 구버전 인스턴스가 같은 Redis 채널을 구독하고 있는데, 그쪽 ObjectMapper 는
     * 모르는 필드를 만나면 예외를 던진다(Jackson 기본값). 태그를 실으면 구버전 인스턴스에 붙어 있는
     * 세션들이 팬아웃을 통째로 못 받고, 그 인스턴스의 로그에는 아무것도 남지 않는다.
     * 채널이 하나인 배포(=지금까지의 모든 배포)에서는 예전과 바이트가 같다.
     */
    private String channelTag(SyncChannel channel) {
        return channels.size() > 1 ? channel.name() : null;
    }

    /**
     * 채널 이름이 없는 메시지는 모든 채널로 보낸다. 롤링 배포 중 구버전 인스턴스가 발행한
     * 메시지가 그렇다 — 그때는 채널이 하나뿐이었으므로 잘못 갈 곳도 없다.
     */
    private boolean matches(SyncChannel channel, RedisSyncMessage message) {
        String name = message.getChannel();
        return name == null || name.isBlank() || name.equals(channel.name());
    }

    private MimeType resolveContentType(String raw, SyncChannel channel) {
        if (raw == null || raw.isBlank()) {
            return channel.codec().contentType();
        }
        try {
            return MimeTypeUtils.parseMimeType(raw);
        } catch (Exception e) {
            return channel.codec().contentType();
        }
    }
}
