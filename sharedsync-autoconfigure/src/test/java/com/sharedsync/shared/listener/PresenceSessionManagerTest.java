package com.sharedsync.shared.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.listener.PresenceSessionManager;
import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;

@ExtendWith(MockitoExtension.class)
class PresenceSessionManagerTest {

    @Mock
    private PresenceStorage presenceStorage;
    @Mock
    private PresenceBroadcaster presenceBroadcaster;
    @Mock
    private UserProvider userProvider;
    @Mock
    private CacheInitializer cacheInitializer;
    @Mock
    private CacheSyncService cacheSyncService;
    @Mock
    private HistoryService historyService;
    @Mock
    private PresenceRootResolver presenceRootResolver;
    @Mock
    private SharedSyncAuthProperties authProperties;
    @Mock
    private SharedSyncPresenceProperties presenceProperties;

    @InjectMocks
    private PresenceSessionManager presenceSessionManager;

    @Test
    @DisplayName("handleSubscribe: 새로운 사용자가 방에 입장하면 처음 유저임을 검증하고 Storage에 정보를 등록한다")
    void handleSubscribe_firstUser() {
        // given
        String rootId = "room-abc";
        String userId = "user-123";
        String sessionId = "session-1";

        given(authProperties.isEnabled()).willReturn(true);
        given(presenceStorage.hasTracker(rootId)).willReturn(false); // First user
        given(presenceStorage.acquireSyncLock(rootId)).willReturn(true); // acquire lock true
        given(presenceRootResolver.getChannel()).willReturn("shared-group");
        given(presenceProperties.getSessionTimeout()).willReturn(30000L);
        given(presenceProperties.getBroadcastDelay()).willReturn(10L);

        Map<String, Object> mockUserInfo = new HashMap<>();
        mockUserInfo.put("nickname", "tester");
        given(userProvider.findUserInfoByUserId(userId)).willReturn(mockUserInfo);
        // buildUserListWithoutDuplicates 에 사용되므로 빈 리스트 반환 처리
        given(presenceStorage.getUserIdsInRoom(rootId)).willReturn(Collections.singletonList(userId));
        given(presenceStorage.getUserInfoByUserId(userId)).willReturn(mockUserInfo);

        // when
        presenceSessionManager.handleSubscribe(rootId, userId, sessionId);

        // then
        verify(presenceStorage).insertTracker(rootId, sessionId, userId, "0");
        verify(presenceStorage).addActiveSession(userId, sessionId);
        verify(presenceStorage).saveUserInfo(userId, mockUserInfo);
        verify(cacheInitializer).initializeHierarchy(rootId);
        verify(presenceBroadcaster).broadcast(
                eq("shared-group"), eq(rootId), eq("create"), eq(userId), eq(mockUserInfo), anyList());
    }

    @Test
    @DisplayName("handleDisconnect: 방을 이탈하게 되면 스토리지 매핑이 제거되고 퇴장 브로드캐스팅이 호출된다")
    void handleDisconnect_success() {
        // given
        String rootId = "room-abc";
        String userId = "user-123";
        String sessionId = "session-1";

        given(presenceStorage.removeSessionRootMapping(sessionId)).willReturn(rootId);
        given(authProperties.isEnabled()).willReturn(true);
        given(presenceStorage.hasTracker(rootId)).willReturn(true); // 아직 방에 누가 남아있다면 (본인이 마지막이 아니면)
        given(presenceRootResolver.getChannel()).willReturn("shared-group");
        given(presenceStorage.getUserIdsInRoom(rootId)).willReturn(Collections.emptyList());

        // when
        presenceSessionManager.handleDisconnect(userId, sessionId);

        // then
        verify(presenceStorage).removeTracker(rootId, sessionId, userId);
        verify(presenceStorage).removeActiveSession(userId, sessionId);
        verify(historyService).clearHistory(rootId, sessionId);
        verify(presenceBroadcaster).broadcast(
                eq("shared-group"), eq(rootId), eq("delete"), eq(userId), any(), anyList());
    }
}
