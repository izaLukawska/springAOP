package org.lukawska.springaop.validation;

import lombok.Getter;

import java.util.List;

@Getter
public class BusinessValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public BusinessValidationException(List<ValidationError> errors) {
        super("Business validation failed. Errors: " + errors.size());
        this.errors = errors;
    }

    public BusinessValidationException(String message, List<ValidationError> errors) {
        super(message + ". Errors: " + errors.size());
        this.errors = errors;
    }
}
