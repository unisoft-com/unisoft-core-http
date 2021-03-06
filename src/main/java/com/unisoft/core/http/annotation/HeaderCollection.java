package com.unisoft.core.http.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation on a deserialized header type that indicates that the property should
 * be treated as a header collection with the provided prefix.
 *
 * @author omar.H.Ajmi
 * @since 19/10/2020
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface HeaderCollection {
    /**
     * The header collection prefix.
     *
     * @return The header collection prefix
     */
    String value();
}
