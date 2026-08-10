package com.sharedsync.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sharedsync.auth")
public class SharedSyncAuthProperties {

    private boolean enabled = true;

    /**
     * 어떤 validator 도 매칭되지 않은 룸을 거부할지. 기본값 false 는 기존 동작(통과)이다.
     *
     * validator 의 목적지 정규식이 어긋나 매칭에 실패한 경우와 "인가가 필요 없는 룸"이
     * 구분되지 않기 때문에, 인가가 통째로 빠진 상태가 조용히 열린 채로 남을 수 있다.
     */
    private boolean denyUnmatched = false;

    public boolean isDenyUnmatched() {
        return denyUnmatched;
    }

    public void setDenyUnmatched(boolean denyUnmatched) {
        this.denyUnmatched = denyUnmatched;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
