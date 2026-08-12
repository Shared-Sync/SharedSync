package com.sharedsync.shared.controller;

import java.util.Map;

import com.sharedsync.shared.codec.ClientFrame;

/**
 * 편집 요청을 서비스 계층으로 넘기는 경계. 구현은 애노테이션 프로세서가 생성하는
 * {@code sharedsync.controller.SharedSyncController} 다.
 *
 * transport 마다 인바운드 표현이 다르다:
 * <ul>
 *   <li>STOMP + JSON: 컨버터가 만든 {@code Map<String, Object>}</li>
 *   <li>raw WebSocket + protobuf: 디코더가 만든 {@link ClientFrame.Edit}</li>
 * </ul>
 * 두 경로 모두 같은 서비스 호출과 같은 publish 로 수렴한다 — 나가는 페이로드는 어느 쪽이든
 * {@link com.sharedsync.shared.codec.SyncOutbound.Entities} 다.
 */
public interface SyncDispatcher {

    void dispatch(String roomId, Map<String, Object> payload);

    void dispatch(String roomId, ClientFrame.Edit edit);
}
