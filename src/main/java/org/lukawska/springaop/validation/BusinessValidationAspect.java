package org.lukawska.springaop.validation;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Aspect
@Component
@RequiredArgsConstructor
public class BusinessValidationAspect {

    private static final Logger log = LoggerFactory.getLogger(BusinessValidationAspect.class);

    private final BusinessValidationService validationService;

    @Around("@annotation(org.lukawska.springaop.validation.BusinessValidation)")
    public Object applyBusinessValidation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        BusinessValidation businessValidation = methodSignature.getMethod().getAnnotation(BusinessValidation.class);

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
