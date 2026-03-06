package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Entity 변환에 필요한 Repository들을 자동으로 주입하는 어노테이션
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoEntityConverter {
    /**
     * 필요한 Repository Bean 이름들 (순서대로 toEntity 메서드 파라미터로 전달)
     */
    String[] repositories() default {};
}