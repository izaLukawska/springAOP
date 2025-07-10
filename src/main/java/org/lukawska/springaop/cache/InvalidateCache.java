package org.lukawska.springaop.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InvalidateCache {

    String[] cacheNames() default {};

    String keyPattern() default "*";

    String[] dependsOn() default {};

    boolean async() default true;

}

