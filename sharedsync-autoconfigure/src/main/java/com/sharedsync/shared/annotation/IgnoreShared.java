package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 어노테이션이 붙은 필드는 sharedsync 프레임워크의 공유 대상(DTO 생성, 캐싱 등)에서 제외됩니다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IgnoreShared {
}
