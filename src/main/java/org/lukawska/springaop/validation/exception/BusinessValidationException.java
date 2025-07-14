package org.lukawska.springaop.validation.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class BusinessValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public BusinessValidationException(String message, List<ValidationError> errors) {
        super(message + ". Errors: " + errors.size());
        this.errors = errors;
    }
}
