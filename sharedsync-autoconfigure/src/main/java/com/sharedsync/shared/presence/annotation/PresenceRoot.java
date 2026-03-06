package com.sharedsync.shared.presence.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PresenceRoot {

    /** WebSocket 채널명 */
    String channel() default "presence";

    /** root 엔터티의 식별자 필드명 */
    String idField();
}
