package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEntity {

    /**
     * Pool당 한 번에 할당할 ID 개수 (기본: 50)
     */
    int allocationSize() default 50;
}