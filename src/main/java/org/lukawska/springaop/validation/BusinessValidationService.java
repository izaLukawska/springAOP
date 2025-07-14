package org.lukawska.springaop.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BusinessValidationService {

    private static final Logger log = LoggerFactory.getLogger(BusinessValidationService.class);

    private final Map<Class<?>, Map<String, BusinessValidator<?>>> validatorsMap;

    /**
     * Constructor for the class.
     * It groups the validators by their type and rule name for easy lookup.
     * @param validators A list of all BusinessValidator beans found in the application context.
     */
    public BusinessValidationService(List<BusinessValidator<?>> validators) {
        this.validatorsMap = validators.stream()
            .collect(Collectors.groupingBy(BusinessValidator::supports,
                Collectors.toMap(BusinessValidator::getRuleName, Function.identity(),
                    (existing, replacement) -> existing
                )
            ));

        log.info("Loaded {} validators grouped by type and rule name.", validators.size());
    }

    /**
     * Validates a given target object against a set of business rules.
     *
     * @param <T> The type of the object being validated.
     * @param target The object to be validated. Cannot be null.
     * @param failFast If true, the validation stops and throws an exception on the first encountered error.
     * If false, all applicable rules are executed, and all errors are collected before throwing an exception.
     * @param ruleNames An optional array of specific rule names to execute.
     * If null or empty, all validators supporting the target type will be executed.
     * @param customErrorMessage A custom message to be used in the BusinessValidationException if validation fails.
     * @throws BusinessValidationException If any validation errors are found.
     * Contains a list of all encountered errors.
     */
    public <T> void validate(T target, boolean failFast, String[] ruleNames, String customErrorMessage) {
        if (target == null) {
            log.warn("User cannot be null");
            return;
        }

        List<ValidationError> allErrors = new ArrayList<>();
        Class<?> targetClass = target.getClass();

        Map<String, BusinessValidator<?>> specificTypeValidators = validatorsMap.entrySet().stream()
            .filter(entry -> entry.getKey().isAssignableFrom(targetClass))
            .flatMap(entry -> entry.getValue().entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (specificTypeValidators.isEmpty()) {
            log.info("No validators found for type: {}.", targetClass.getName());
            return;
        }

        List<String> rulesToExecute = (ruleNames != null && ruleNames.length > 0)
            ? Arrays.asList(ruleNames)
            : new ArrayList<>(specificTypeValidators.keySet());


        log.info("Running rules: {}", rulesToExecute);

        for(String ruleName : rulesToExecute){
            BusinessValidator<?> validator = specificTypeValidators.get(ruleName);

            if (validator == null) {
                log.warn("No validator bean found for rule name: '{}'.", ruleName);
                continue;
            }

            @SuppressWarnings("unchecked")
            List<ValidationError> currentRuleErrors = ((BusinessValidator<T>) validator).validate(target);

            if (!currentRuleErrors.isEmpty()) {
                log.info("Validator fail for: {} with errors {} on object {}",
                    ruleName, currentRuleErrors,targetClass.getName());

                allErrors.addAll(currentRuleErrors);

                if (failFast) {
                    throw new BusinessValidationException(customErrorMessage, allErrors);
                }
            }
        }

        if (!allErrors.isEmpty()) {
            throw new BusinessValidationException(customErrorMessage, allErrors);
        }

        log.info("Validation completed for object of type {}", targetClass.getName());
    }
}
