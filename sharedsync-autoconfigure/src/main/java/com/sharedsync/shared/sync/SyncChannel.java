package com.sharedsync.shared.sync;

import com.sharedsync.shared.codec.SyncCodec;
import com.sharedsync.shared.transport.SyncTransport;

/**
 * 하나의 전송 경로. codec 과 transport 는 반드시 짝으로 움직인다.
 *
 * 예전에는 둘을 따로 주입받아 하나씩만 들 수 있었고, 그래서 STOMP+JSON 과 raw WS+protobuf 를
 * 동시에 서비스할 수 없었다 — 전환하려면 모든 클라이언트를 한 번에 바꿔야 했다는 뜻이다.
 * 채널을 여러 개 두면 구버전 클라이언트가 붙어 있는 동안 새 클라이언트를 배포할 수 있다.
 *
 * @param name Redis 팬아웃에서 이 채널을 식별하는 이름. 수신 인스턴스가 같은 이름의 채널로만
 *             내보내야 한다 — 바이트는 채널의 codec 으로 인코딩돼 있어서, 다른 채널로 보내면
 *             JSON 을 기대하는 클라이언트에 protobuf 가 가거나 그 반대가 된다.
 */
public record SyncChannel(String name, SyncTransport transport, SyncCodec codec) {

    public static final String STOMP = "stomp";
    public static final String WEBSOCKET = "websocket";
}
