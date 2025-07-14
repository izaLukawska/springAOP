package org.lukawska.springaop.validation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;


@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessValidationAspect {

    private final BusinessValidationService validationService;

    /**
     * Method to run business validation and checks methods annotated with @BusinessValidation.
     * It takes the first argument passed into the method and uses BusinessValidationService to check it.
     * If any error occurs, then an error is thrown.
     * @param joinPoint This is like the exact moment our method is about to run.
     * @return Continues with the original method after validation.
     * @throws Throwable If anything goes wrong, like validation fails or the original method has an issue.
     */

    @Around("@annotation(org.lukawska.springaop.validation.BusinessValidation)")
    public Object applyBusinessValidation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        BusinessValidation businessValidation = methodSignature.getMethod()
            .getAnnotation(BusinessValidation.class);

        if (joinPoint.getArgs().length == 0) {
            log.warn("Annotated method {} has no arguments to validate.", methodSignature.getName());
            return joinPoint.proceed();
        }

        Object objectToValidate = joinPoint.getArgs()[0];

        log.debug("Applying business validation for method: {} with object of type {}",
            methodSignature.getName(), objectToValidate.getClass().getSimpleName());

        validationService.validate(
            objectToValidate,
            businessValidation.failFast(),
            businessValidation.rules(),
            businessValidation.errorMessage()
        );

        log.debug("Business validation successful for method: {}", methodSignature.getName());

        return joinPoint.proceed();
    }
}
