package com.sharedsync.shared.presence.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sharedsync.shared.presence.dto.PresenceSnapshot;
import com.sharedsync.shared.storage.PresenceStorage;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SharedPresenceFacade {

    private final PresenceStorage storage;

    public List<PresenceSnapshot> getPresence(String roomId) {
        var entries = storage.getTrackerEntries(roomId);

        if (entries == null || entries.isEmpty()) return Collections.emptyList();

        List<PresenceSnapshot> snapshots = new ArrayList<>();

        for (var e : entries.entrySet()) {

            // "userId//sessionId" → userId 파싱
            String rawKey = e.getKey();
            String userId = rawKey.contains("//") ? rawKey.split("//")[0] : rawKey;

            String dayIndex = e.getValue();
            Map<String, Object> userInfo = storage.getUserInfoByUserId(userId);

            Map<String, Object> attr = new HashMap<>();
            attr.put("dayIndex", dayIndex);

            snapshots.add(new PresenceSnapshot(
                    userId,
                    userInfo,
                    attr
            ));
        }

        return snapshots;
    }

}
