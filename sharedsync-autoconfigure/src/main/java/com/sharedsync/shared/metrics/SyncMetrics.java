package com.sharedsync.shared.metrics;

/**
 * 동기화 계층의 관측 지점.
 *
 * 인터페이스로 두는 이유는 Micrometer 를 **선택 의존**으로 유지하기 위해서다. 프레임워크가
 * MeterRegistry 를 강제하면 그걸 쓰지 않는 앱까지 액추에이터를 끌어와야 한다. 없으면 {@link #NOOP}
 * 가 들어가고 호출부는 분기하지 않는다.
 *
 * STOMP 를 쓰던 때는 Spring 이 브로커 통계를 대신 냈다. raw WebSocket 으로 옮기면서 그마저
 * 사라졌으므로, 세션 수·프레임 수·에러 코드는 프레임워크가 직접 내야 한다.
 */
public interface SyncMetrics {

    SyncMetrics NOOP = new SyncMetrics() {
    };

    /** 인바운드 프레임 한 건. type 은 join/edit/ping/unknown/malformed. */
    default void frame(String type) {
    }

    /** 큐 한도 초과로 거절한 프레임. 지속적으로 늘면 dispatch-threads 가 부족한 것이다. */
    default void rejected() {
    }

    /** 클라이언트에 돌려준 에러 프레임. code 별로 센다. */
    default void error(String code) {
    }
}
