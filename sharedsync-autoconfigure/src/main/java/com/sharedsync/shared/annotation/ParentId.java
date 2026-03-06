package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 부모 ID를 나타내는 어노테이션 (findByParentId 등에서 사용)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ParentId {

	/**
	 * Declares the entity type that owns this parent identifier.
	 * Defaults to {@link Object} meaning "unspecified".
	 */
	Class<?> value() default Object.class;
}