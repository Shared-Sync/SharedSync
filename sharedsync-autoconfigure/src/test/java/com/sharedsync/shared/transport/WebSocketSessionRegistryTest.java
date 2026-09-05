package com.sharedsync.shared.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link WebSocketSessionRegistry#sessionIdsOf} — REST 경로가 "이 유저가 이 방에 연 세션"을
 * 신원 기준으로 스스로 찾을 때 쓰는 조회.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketSessionRegistryTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    @Mock
    private WebSocketSession rawA;
    @Mock
    private WebSocketSession rawB;
    @Mock
    private WebSocketSession rawC;

    private void register(WebSocketSession raw, String id, String userId) {
        when(raw.getId()).thenReturn(id);
        // 방 필터에서 걸러지는 세션은 getAttributes() 까지 안 간다(예: 다른 방 조회) — lenient.
        lenient().when(raw.getAttributes()).thenReturn(Map.of("userId", userId));
        registry.register(raw);
    }

    @Test
    @DisplayName("같은 방, 같은 userId 세션만 돌려준다 — 다른 유저 세션은 제외")
    void returnsOnlySessionsOwnedByUser() {
        register(rawA, "s-a", "user-1");
        register(rawB, "s-b", "user-2");
        registry.joinRoom("s-a", "room-1");
        registry.joinRoom("s-b", "room-1");

        Set<String> found = registry.sessionIdsOf("room-1", "user-1");

        assertThat(found).containsExactly("s-a");
    }

    @Test
    @DisplayName("같은 유저가 같은 방을 여러 세션(탭)으로 열면 전부 돌아온다 — 선택은 호출자 책임")
    void returnsAllSessionsWhenSameUserMultipleTabs() {
        register(rawA, "s-a", "user-1");
        register(rawC, "s-c", "user-1");
        registry.joinRoom("s-a", "room-1");
        registry.joinRoom("s-c", "room-1");

        Set<String> found = registry.sessionIdsOf("room-1", "user-1");

        assertThat(found).containsExactlyInAnyOrder("s-a", "s-c");
    }

    @Test
    @DisplayName("다른 방의 세션은 제외된다")
    void excludesSessionsInOtherRooms() {
        register(rawA, "s-a", "user-1");
        registry.joinRoom("s-a", "room-1");

        assertThat(registry.sessionIdsOf("room-2", "user-1")).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 방이면 빈 Set (null 아님)")
    void unknownRoom_returnsEmptySet() {
        assertThat(registry.sessionIdsOf("no-such-room", "user-1")).isEmpty();
    }

    @Test
    @DisplayName("그 방에 이 유저 세션이 없으면 빈 Set")
    void noMatchingUser_returnsEmptySet() {
        register(rawA, "s-a", "user-1");
        registry.joinRoom("s-a", "room-1");

        assertThat(registry.sessionIdsOf("room-1", "user-2")).isEmpty();
    }
}
