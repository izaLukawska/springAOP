package org.lukawska.springaop.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SmartCache {

    String cacheName();

    String key() default "";

    int ttlSeconds() default 10;

    String[] dependsOn() default {};

    boolean useWeakReference() default false;
}

