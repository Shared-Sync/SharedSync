package com.sharedsync.shared.presence.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sharedsync.shared.sync.RedisSyncService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PresenceBroadcaster {

    private final RedisSyncService redisSyncService;

    public void broadcast(
            String entityName,
            String roomId,
            String action,
            String uid,
            Map<String, Object> userInfo,
            List<Map<String, Object>> users
    ) {
        Map<String, Object> payload = createPayload(uid, userInfo, users, action);

        redisSyncService.publish(
                String.format("/topic/%s/%s", entityName, roomId),
                payload
        );
    }

    public void sendToSession(
            String entityName,
            String roomId,
            String user,
            String sessionId,
            String action,
            String uid,
            Map<String, Object> userInfo,
            List<Map<String, Object>> users
    ) {
        Map<String, Object> payload = createPayload(uid, userInfo, users, action);

        redisSyncService.sendToSession(
                user,
                sessionId,
                String.format("/topic/%s/%s", entityName, roomId),
                payload
        );
    }

    public Map<String, Object> createPayload(
            String uid,
            Map<String, Object> userInfo,
            List<Map<String, Object>> users,
            String action
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", uid);
        payload.put("userInfo", userInfo);
        payload.put("users", users);
        payload.put("action", action);
        return payload;
    }

}
