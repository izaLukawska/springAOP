package org.lukawska.springaop.locking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DistributedLock {

    String lockName() default "";

    String key() default "";

    long waitTimeSeconds() default 10;

    long leaseTimeSeconds() default -1;

    LockStrategy strategy() default LockStrategy.FAIL_FAST;

    String fallbackMethod() default "";

}
