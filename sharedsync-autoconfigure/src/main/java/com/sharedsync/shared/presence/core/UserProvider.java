package com.sharedsync.shared.presence.core;

import java.util.Map;

/**
 * 앱이 구현하는 유저 정보 조회 인터페이스
 */
public interface UserProvider {
    Map<String, Object> findUserInfoByUserId(String userId);
}
