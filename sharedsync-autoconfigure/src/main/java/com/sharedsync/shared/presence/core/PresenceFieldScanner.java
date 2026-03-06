package com.sharedsync.shared.presence.core;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sharedsync.shared.presence.annotation.PresenceKey;

/**
 * PresenceFieldScanner
 *
 * DTO나 엔티티 내의 @PresenceKey 필드를 리플렉션으로 읽어
 * Presence 브로드캐스트용 Map으로 변환한다.
 */
public class PresenceFieldScanner {

    public static Map<String, Object> extractPresenceData(Object dto) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (Field field : dto.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(PresenceKey.class)) continue;
            PresenceKey key = field.getAnnotation(PresenceKey.class);
            field.setAccessible(true);
            String name = key.name().isEmpty() ? field.getName() : key.name();
            try {
                map.put(name, field.get(dto));
            } catch (IllegalAccessException ignored) {}
        }
        return map;
    }
}
