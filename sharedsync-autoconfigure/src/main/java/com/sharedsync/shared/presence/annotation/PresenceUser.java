package com.sharedsync.shared.presence.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PresenceUser
 *
 * User 엔티티를 Presence 시스템과 연결하기 위한 어노테이션.
 * 프레임워크는 해당 어노테이션이 달린 엔티티를 스캔하여
 * userId 및 nickname 필드를 자동 인식한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PresenceUser {
    /**
     * 유저 식별자 필드 이름 (예: "userId")
     * 명시하지 않을 경우 @Id 어노테이션이 붙은 필드를 자동으로 찾습니다.
     */
    String idField() default "";

    /**
     * Presence 정보에 포함할 필드 리스트 (예: {"nickname", "email"})
     */
    String[] fields() default {"nickname"};
}
