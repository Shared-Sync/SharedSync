package com.sharedsync.shared.presence.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PresenceKey
 *
 * DTO나 엔티티 내의 Presence 관련 필드를 표시하는 어노테이션.
 * 프레임워크는 이 어노테이션이 달린 필드를 리플렉션으로 읽어
 * Presence 데이터(Map<String,Object>)를 자동 생성한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PresenceKey {
    /**
     * Presence 필드 이름. (JSON 키로 사용)
     * 비워두면 필드명을 그대로 사용한다.
     */
    String name() default "";

    /**
     * Presence 계층 깊이 (0, 1, 2, ...)
     * Plan → TimeTable → Block 등 다층 구조를 표현할 때 사용.
     */
    int level() default 0;

    /**
     * Presence 식별자 여부.
     * true면 Presence의 주요 식별자로 취급되어 Redis 키 구성 등에 사용된다.
     */
    boolean identifier() default false;
}
