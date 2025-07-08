package org.lukawska.springaop.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BusinessValidationService {

    private static final Logger log = LoggerFactory.getLogger(BusinessValidationService.class); // 2. Logger

    private final Map<Class<?>, Map<String, BusinessValidator<?>>> validatorsMap;

    public BusinessValidationService(ApplicationContext applicationContext) {
        @SuppressWarnings("rawtypes")
        Map<String, BusinessValidator> beans = applicationContext.getBeansOfType(BusinessValidator.class);

        this.validatorsMap = beans.values().stream()
            .map(validator -> (BusinessValidator<?>) validator)
            .collect(Collectors.groupingBy(BusinessValidator::supports,
                Collectors.toMap(BusinessValidator::getRuleName, Function.identity(),
                (existing, replacement) -> existing
            )
        ));

        log.info("Loaded {} business validators grouped by target type and rule name.", beans.size());
    }



    public <T> void validate(T target, boolean failFast, String[] ruleNames, String customErrorMessage) {
        if (target == null) {
            log.warn("Attempted to validate a null object. Skipping validation.");
            return;
        }

        List<ValidationError> allErrors = new ArrayList<>();
        Class<?> targetClass = target.getClass();

        Map<String, BusinessValidator<?>> specificTypeValidators = validatorsMap.entrySet().stream()
            .filter(entry -> entry.getKey().isAssignableFrom(targetClass))
            .flatMap(entry -> entry.getValue().entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));


        if (specificTypeValidators.isEmpty()) {
            log.debug("No business validators found for type: {} ", targetClass.getName());
            return;
        }

        Set<String> rulesToExecute = (ruleNames != null && ruleNames.length > 0)
            ? Arrays.stream(ruleNames).collect(Collectors.toSet())
            : specificTypeValidators.keySet();

        log.debug("Running business validation for object of type {} with {} specified rules. Fail-fast: {}",
            targetClass.getName(), rulesToExecute.size(), failFast);

        for(String ruleName : rulesToExecute){
            BusinessValidator<?> validator = specificTypeValidators.get(ruleName);

            if (validator == null) {
                log.warn("No validator found for rule name: '{}' for type {}.", ruleName, targetClass.getName());
                continue;
            }

            @SuppressWarnings("unchecked")
            List<ValidationError> errors = ((BusinessValidator<T>) validator).validate(target);

            if (!errors.isEmpty()) {
                log.warn("Validation failed for validator {} (rule: '{}') on object {}. Errors: {}",
                    validator.getClass().getSimpleName(), ruleName, targetClass.getName(), errors);
                allErrors.addAll(errors);
                if (failFast) {
                    throw new BusinessValidationException(customErrorMessage, allErrors);
                }
            } else {
                log.debug("Validation successful for validator {} (rule: '{}') on object {}.",
                    validator.getClass().getSimpleName(), ruleName, targetClass.getName());
            }
        }

        if (!allErrors.isEmpty()) {
            throw new BusinessValidationException(customErrorMessage, allErrors);
        }

        log.debug("Business validation completed successfully for object of type {}.", targetClass.getName());
    }
}
