package com.sharedsync.shared.presence.dto;

import java.util.Map;

public record PresenceSnapshot(
        String userId,
        Map<String, Object> userInfo,
        Map<String, Object> attributes
) {}
