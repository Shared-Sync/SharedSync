package com.sharedsync.shared.presence.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Presence 기능이 비활성화된 경우에 사용하는 더미 UserProvider 구현체.
 * 실제 유저 정보 조회 대신 기본 맵을 리턴.
 */
public class DummyUserProvider implements UserProvider {

    @Override
    public Map<String, Object> findUserInfoByUserId(String userId) {
        Map<String, Object> info = new HashMap<>();
        info.put("nickname", "anonymous");
        return info;
    }
}
