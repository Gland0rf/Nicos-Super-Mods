package com.nico.client.configuration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stable serialized feature ID. Keep this value when moving a feature to another category. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FeatureId {
    String value();
    /** Old JSON field names, if a feature has been renamed. */
    String[] legacyNames() default {};
}
