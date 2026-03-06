package com.sharedsync.shared.presence.core;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.reflections.Reflections;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.context.FrameworkContext;
import com.sharedsync.shared.presence.annotation.PresenceUser;

import jakarta.annotation.PostConstruct;

@Component
public class PresenceUserRegistry {

    private Class<?> userClass;
    private Field idField;
    private List<Field> infoFields = new java.util.ArrayList<>();

    @PostConstruct
    public void init() {
        String basePackage = FrameworkContext.getBasePackage();
        Reflections reflections = new Reflections(basePackage);

        Set<Class<?>> annotated = reflections.getTypesAnnotatedWith(PresenceUser.class);
        if (annotated.isEmpty()) return;

        userClass = annotated.iterator().next();
        PresenceUser ann = userClass.getAnnotation(PresenceUser.class);
        try {
            // ID 필드 찾기
            String idFieldName = ann.idField();
            if (idFieldName.isEmpty()) {
                // @Id 어노테이션이 붙은 필드 찾기
                for (Field field : userClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(jakarta.persistence.Id.class) || 
                        field.isAnnotationPresent(org.springframework.data.annotation.Id.class)) {
                        idField = field;
                        break;
                    }
                }
                if (idField == null) {
                    throw new IllegalStateException("Could not find @Id field in " + userClass.getName());
                }
            } else {
                idField = userClass.getDeclaredField(idFieldName);
            }
            idField.setAccessible(true);

            // 정보 필드들(fields) 찾기
            for (String fieldName : ann.fields()) {
                Field f = userClass.getDeclaredField(fieldName);
                f.setAccessible(true);
                infoFields.add(f);
            }

        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("@PresenceUser fields not found in " + userClass.getName(), e);
        }
    }

    /** 등록된 User 엔티티 클래스 반환 (없으면 null) */
    public Class<?> getUserClass() {
        return userClass;
    }

    /** 스캔 성공 여부 */
    public boolean isInitialized() {
        return userClass != null && idField != null && !infoFields.isEmpty();
    }

    public boolean isUserClass(Object obj) {
        return isInitialized() && userClass.isAssignableFrom(obj.getClass());
    }

    public String getUserId(Object user) {
        try {
            Object val = idField.get(user);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> getUserInfo(Object user) {
        Map<String, Object> info = new java.util.HashMap<>();
        try {
            for (Field f : infoFields) {
                info.put(f.getName(), f.get(user));
            }
            return info;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
