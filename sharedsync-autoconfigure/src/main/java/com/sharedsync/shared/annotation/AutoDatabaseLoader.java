package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 데이터베이스 로딩을 자동화하는 어노테이션
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoDatabaseLoader {
    /**
     * Repository Bean 이름 (생략시 자동 생성: entityNameRepository)
     */
    String repository() default "";
    
    /**
     * 조회 메서드 이름 (생략시 자동 생성: findByParentEntityNameId)
     */
    String method() default "";
}