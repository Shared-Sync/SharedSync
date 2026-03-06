package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * DTO 필드에 대응하는 엔티티의 테이블 이름을 명시합니다.
 * extractRelatedId 등에서 필드를 찾을 때 보조 수단으로 사용됩니다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableName {
    String value();
}
