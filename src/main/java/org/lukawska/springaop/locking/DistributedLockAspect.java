package org.lukawska.springaop.locking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.lukawska.springaop.locking.exception.LockAcquisitionException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockAspect {

    private final ExpressionParser parser = new SpelExpressionParser();

    private final RedissonClient redissonClient;

    private final DistributedLockMetricsService metricsService;

    @Around("@annotation(lockAnnotation)")
    public Object acquireLock(ProceedingJoinPoint pjp, DistributedLock lockAnnotation) throws Throwable {
        String lockKey = generateLockKey(lockAnnotation, pjp);
        RLock lock = redissonClient.getLock(lockKey);
        String methodName = pjp.getSignature().toShortString();

        boolean acquired = false;
        long startTime = System.nanoTime();
        try {
            acquired = lockAcquired(lock, lockAnnotation);
            metricsService.recordLockWaitTime(lockKey, methodName, System.nanoTime() - startTime);
            return handleLockAcquisition(acquired, pjp, lockAnnotation, lockKey, methodName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while acquiring lock {} for method {}: {}",
                lockKey, methodName, e.getMessage());
            metricsService.incrementLockAcquisitionFailure(lockKey, methodName, "interrupted");
            return handleLockAcquisitionFailure(pjp, lockAnnotation, e);
        } catch (RedisConnectionException e) {
            log.error("Redis connection error while acquiring lock {} for method {}: {}",
                lockKey, methodName, e.getMessage());
            metricsService.incrementLockAcquisitionFailure(lockKey, methodName, "redis_connection_error");
            return handleLockAcquisitionFailure(pjp, lockAnnotation, e);
        } catch (RedisException e) {
            log.error("Redisson error while acquiring lock {} for method {}: {}",
                lockKey, methodName, e.getMessage());
            metricsService.incrementLockAcquisitionFailure(lockKey, methodName, "redisson_error");
            return handleLockAcquisitionFailure(pjp, lockAnnotation, e);
        } finally {
            if (acquired) {
                releaseLock(lock);
            }
        }
    }

    private boolean lockAcquired(RLock lock, DistributedLock lockAnnotation) throws InterruptedException {
        if (lockAnnotation.strategy() == LockStrategy.WAIT_AND_RETRY) {
            return lock.tryLock(lockAnnotation.waitTimeSeconds(),
                lockAnnotation.leaseTimeSeconds(), TimeUnit.SECONDS);
        } else {
            long leaseTime = lockAnnotation.leaseTimeSeconds();
            if (leaseTime == -1) {
                return lock.tryLock(0, TimeUnit.SECONDS);
            } else {
                return lock.tryLock(0, leaseTime, TimeUnit.SECONDS);
            }
        }
    }

    private Object handleLockAcquisition(boolean acquired,
                                         ProceedingJoinPoint pjp,
                                         DistributedLock lockAnnotation,
                                         String lockKey,
                                         String methodName) throws Throwable {
        if(acquired){
            log.info("Lock {} acquired, proceeding with method: {}", lockKey, methodName);
            metricsService.incrementLockAcquisitionSuccess(lockKey, methodName);
            return pjp.proceed();
        } else {
            log.info("Lock {} NOT acquired for method: {}", lockKey, methodName);

            if (lockAnnotation.strategy() == LockStrategy.SKIP_EXECUTION) {
                log.info("Strategy is SKIP_EXECUTION. Proceeding with method without lock.");
                metricsService.incrementLockSkipped(lockKey, methodName);
                return pjp.proceed();
            } else {
                log.info("Strategy is FAIL_FAST or WAIT_AND_RETRY failed. Handling failure.");
                metricsService.incrementLockAcquisitionFailure(lockKey, methodName, "lock_not_acquired");
                return handleLockAcquisitionFailure(pjp, lockAnnotation, null);
            }
        }
    }

    /**
     * Handles the failure to acquire a lock, attempting to invoke a fallback method or
     * applying a default failure strategy (throwing an exception).
     *
     * @param pjp      The {@link ProceedingJoinPoint} of the intercepted method.
     * @param lockAnnotation The {@link DistributedLock} annotation.
     * @param cause          The underlying cause of the failure (e.g., RedisConnectionException) can be null.
     * @return The result of the fallback method or the original method if skipped.
     * @throws Throwable If no fallback is defined or the fallback method throws an exception.
     */
    private Object handleLockAcquisitionFailure(ProceedingJoinPoint pjp,
                                                DistributedLock lockAnnotation,
                                                Throwable cause) throws Throwable {
        String methodName = pjp.getSignature().toShortString();
        String fallbackMethodName = lockAnnotation.fallbackMethod();

        if (!fallbackMethodName.isEmpty()) {
            try {
                log.info("Lock not acquired for method: {}. Attempt to invoke fallback: {}",
                    methodName, fallbackMethodName);
                return invokeFallbackMethod(pjp, fallbackMethodName, cause);
            } catch (NoSuchMethodException e) {
                log.error("Fallback method {} not found for method {}. Applying default failure strategy.",
                    fallbackMethodName, methodName, e);
                return applyLockFailureStrategy(methodName, lockAnnotation.strategy(), e);
            } catch (Throwable e) {
                log.error("Error invoking fallback method {} for method {}. Re-throwing fallback exception.",
                    fallbackMethodName, methodName, e);
                throw e;
            }
        } else {
            log.debug("No fallback method specified for method {}. Applying default failure strategy.",
                methodName);
            return applyLockFailureStrategy(methodName, lockAnnotation.strategy(), cause);
        }
    }

    /**
     * Invokes the specified fallback method using reflection.
     * This version expects the fallback method to always have the original method's arguments
     * followed by a single {@link Throwable} parameter.
     *
     * @param pjp          The {@link ProceedingJoinPoint} of the intercepted method.
     * @param fallbackMethodName The name of the fallback method to invoke.
     * @param cause              The {@link Throwable} cause of the failure, can be null.
     * @return The result of the fallback method invocation.
     * @throws Throwable If the fallback method is not found or throws an exception.
     */
    private Object invokeFallbackMethod(ProceedingJoinPoint pjp,
                                        String fallbackMethodName,
                                        Throwable cause) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
        int parameterCount = methodSignature.getParameterTypes().length;

        Class<?> targetClass = pjp.getTarget().getClass();
        Object[] originalArgs = pjp.getArgs();

        Class<?>[] fallbackParameterTypes = Arrays.copyOf(methodSignature.getParameterTypes(),
            parameterCount + 1);
        fallbackParameterTypes[parameterCount] = Throwable.class;

        Object[] argsToInvoke = Arrays.copyOf(originalArgs, originalArgs.length + 1);
        argsToInvoke[originalArgs.length] = (cause != null) ? cause : new LockAcquisitionException(
            "Failed to acquire lock for method: " + methodSignature.getName()
        );

        Method fallbackMethod = getFallbackMethod(fallbackMethodName, targetClass, fallbackParameterTypes);
        return fallbackMethod.invoke(pjp.getTarget(), argsToInvoke);
    }

    /**
     * Applies the default failure strategy (throwing an exception) when a lock cannot be acquired
     * and no fallback method is specified or applicable.
     *
     * @param methodName The short string representation of the intercepted method.
     * @param strategy   The {@link LockStrategy} that was applied.
     * @param cause      The underlying cause of the failure can be null.
     * @throws Throwable A {@link LockAcquisitionException} or the original cause if available.
     */
    private Object applyLockFailureStrategy(String methodName,
                                            LockStrategy strategy,
                                            Throwable cause) throws Throwable {
        log.error("Throwing exception (lock not acquired for method {} with strategy {})",
            methodName, strategy);

        if (cause != null) {
            throw cause;
        }
        throw new LockAcquisitionException("Failed to acquire distributed lock for method: " + methodName);
    }

    private static Method getFallbackMethod(String fallbackMethodName,
                                            Class<?> targetClass,
                                            Class<?>[] fallbackParameterTypes) {
        Method fallbackMethod;
        try {
            fallbackMethod = targetClass.getMethod(fallbackMethodName, fallbackParameterTypes);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(
                String.format("Fallback method %s not found in class %s",
                    fallbackMethodName, targetClass.getName()), ex);
        }

        fallbackMethod.setAccessible(true);
        return fallbackMethod;
    }

    private void releaseLock(RLock lock) {
        if (lock.isLocked() && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                log.info("Released lock: {}", lock.getName());
            } catch (IllegalMonitorStateException e) {
                log.warn("Lock expired/ not held by thread: {}", lock.getName());
            } catch (Exception e) {
                log.error("Error releasing lock {} : {}", lock.getName(), e.getMessage());
            }
        } else {
            log.warn("Not locked/held by thread: {}", lock.getName());
        }
    }

    private String generateLockKey(DistributedLock distributedLock, ProceedingJoinPoint pjp) {
        String lockName = distributedLock.lockName();
        String keyExpression = distributedLock.key();

        String generatedKey = lockName;

        if (!keyExpression.isEmpty()) {
            MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
            Object[] args = pjp.getArgs();
            String[] parameterNames = methodSignature.getParameterNames();

            StandardEvaluationContext context = new StandardEvaluationContext();
            for(int i = 0; i < parameterNames.length; i++){
                context.setVariable(parameterNames[i], args[i]);
            }

            String dynamicKeyPart = parser.parseExpression(keyExpression).getValue(context, String.class);
            generatedKey = lockName.isEmpty() ? dynamicKeyPart : lockName + ":" + dynamicKeyPart;
        }

        if (Objects.requireNonNull(generatedKey).isEmpty()) {
            String methodName = pjp.getSignature().toShortString();
            log.error("Empty lock key for method: {}", methodName);
            throw new IllegalArgumentException("Lock key cannot be empty for method: "
                + methodName);
        }

        return generatedKey;
    }
}
