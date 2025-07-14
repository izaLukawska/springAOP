package org.lukawska.springaop.versioning;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiVersion {

    String[] versions() default {};

    String since() default "";

    String deprecated() default "";

    boolean requiresVersion() default true;
}
