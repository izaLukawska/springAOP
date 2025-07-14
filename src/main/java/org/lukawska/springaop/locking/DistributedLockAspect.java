package org.lukawska.springaop.locking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLockAspect {

    private final ExpressionParser parser = new SpelExpressionParser();

    private final RedissonClient redissonClient;

    @Around("@annotation(lockAnnotation)")
    public Object acquireLock(ProceedingJoinPoint joinPoint, DistributedLock lockAnnotation) throws Throwable {
        String lockKey = generateLockKey(lockAnnotation, joinPoint);
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired = false;
        try {
            if (lockAnnotation.strategy() == LockStrategy.WAIT_AND_RETRY) {
                acquired = lock.tryLock(lockAnnotation.waitTimeSeconds(),
                    lockAnnotation.leaseTimeSeconds(), TimeUnit.SECONDS);
            } else {
                long leaseTime = lockAnnotation.leaseTimeSeconds();
                if (leaseTime == -1) {
                    acquired = lock.tryLock(0, TimeUnit.SECONDS);
                } else {
                    acquired = lock.tryLock(0, leaseTime, TimeUnit.SECONDS);
                }
            }

            if (acquired) {
                log.info("Lock '{}' acquired, proceeding with method: {}",
                    lockKey, joinPoint.getSignature().toShortString());
                return joinPoint.proceed();
            } else {
                if (lockAnnotation.strategy() == LockStrategy.SKIP_EXECUTION) {
                    log.info("Locked skipped (strategy = SKIP_EXECUTION)");
                    return joinPoint.proceed();
                } else {
                    log.info("Strategy is FAIL_FAST or WAIT_AND_RETRY failed. Handling failure.");
                    return handleLockAcquisitionFailure(joinPoint, lockAnnotation);
                }
            }
        } finally {
            if (acquired) {
                releaseLock(lock);
            }
        }
    }

    private Object invokeFallbackMethod(ProceedingJoinPoint joinPoint,
                                        String fallbackMethodName) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass = joinPoint.getTarget().getClass();

        try {
            Method fallbackMethod = targetClass.getMethod(
                fallbackMethodName,
                methodSignature.getParameterTypes()
            );
            log.info("Invoking fallback method {} for method'{}.",
                fallbackMethodName, methodSignature.toShortString());
            return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
        } catch (NoSuchMethodException e) {
            log.error("Fallback method {} not found for method {}.",
                fallbackMethodName, methodSignature.toShortString(), e);
            throw new IllegalStateException("Fallback method not found or signature mismatch: "
                + fallbackMethodName, e);
        } catch (Exception e) {
            log.error("Error invoking fallback method '{}' for method '{}'.",
                fallbackMethodName, methodSignature.toShortString(), e);
            throw e;
        }
    }

    private void releaseLock(RLock lock) {
        if (lock.isLocked() && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
                log.info("Released lock: {}", lock.getName());
            } catch (IllegalMonitorStateException e) {
                log.info("Lock expired/ not held by thread: {}", lock.getName());
            } catch (Exception e) {
                log.error("Error releasing lock '{}': {}", lock.getName(), e.getMessage());
            }
        } else {
            log.warn("Not locked/held by thread: {}", lock.getName());
        }
    }

    private Object handleLockAcquisitionFailure(ProceedingJoinPoint joinPoint,
                                                DistributedLock lockAnnotation) throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        String fallbackMethodName = lockAnnotation.fallbackMethod();

        if (!fallbackMethodName.isEmpty()) {
            try {
                log.info("Lock not acquired for method: {}. Attempt to invoke fallback: {}",
                    methodName, fallbackMethodName);
                return invokeFallbackMethod(joinPoint, fallbackMethodName);
            } catch (NoSuchMethodException e) {
                log.error("Fallback method not found {} for {} Falling back to strategy: {}",
                    fallbackMethodName, methodName, lockAnnotation.strategy(), e);
                return applyLockFailureStrategy(methodName, lockAnnotation.strategy());
            } catch (Throwable e) {
                log.error("Error invoking fallback method {} for {}. Re-throwing fallback exception.",
                    fallbackMethodName, methodName, e);
                throw e;
            }
        } else {
            log.debug("No fallback method specified for {}. Applying strategy: {}",
                methodName, lockAnnotation.strategy());
            return applyLockFailureStrategy(methodName, lockAnnotation.strategy());
        }
    }

    private Object applyLockFailureStrategy(String methodName, LockStrategy strategy) {
        return switch (strategy) {
            case FAIL_FAST -> {
                log.error("Failed lock acquire (FAIL FAST): {}", methodName);
                throw new IllegalStateException("Failed to acquire lock for method: "
                    + methodName);
            }
            case WAIT_AND_RETRY -> {
                log.error("Failed lock acquire (WAIT AND RETRY) after all retries: {}",
                    methodName);
                throw new IllegalStateException("Failed to acquire lock after retries for method: "
                    + methodName);
            }
            case SKIP_EXECUTION -> {
                log.warn("Failed lock acquire (SKIP EXECUTION): {}. Returning null.",
                    methodName);
                yield null;
            }
        };
    }

    private String generateLockKey(DistributedLock distributedLock, ProceedingJoinPoint joinPoint) {
        String lockName = distributedLock.lockName();
        String keyExpression = distributedLock.key();

        String generatedKey = lockName;

        if (!keyExpression.isEmpty()) {
            MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
            Object[] args = joinPoint.getArgs();
            String[] parameterNames = methodSignature.getParameterNames();

            StandardEvaluationContext context = new StandardEvaluationContext();
            for(int i = 0; i < parameterNames.length; i++){
                context.setVariable(parameterNames[i], args[i]);
            }

            String dynamicKeyPart = parser.parseExpression(keyExpression).getValue(context, String.class);
            generatedKey = lockName.isEmpty() ? dynamicKeyPart : lockName + ":" + dynamicKeyPart;
        }

        if (Objects.requireNonNull(generatedKey).isEmpty()) {
            String methodName = joinPoint.getSignature().toShortString();
            log.error("Empty lock key for method: {}", methodName);
            throw new IllegalArgumentException("Lock key cannot be empty for method: "
                + methodName);
        }

        return generatedKey;
    }
}
