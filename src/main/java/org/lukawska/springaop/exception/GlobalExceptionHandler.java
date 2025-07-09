package org.lukawska.springaop.exception;

import org.lukawska.springaop.validation.BusinessValidationException;
import org.lukawska.springaop.validation.ErrorResponse;
import org.lukawska.springaop.validation.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * This method catches the UserAlreadyExistsException.
     * @param ex The exception being thrown.
     * @return Response body with 'Conflict' status and exception message.
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<String> handleAlreadyExists(UserAlreadyExistsException ex) {
        log.warn("UserAlreadyExistsException caught: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * This method catches the UserNotFoundException.
     * @param ex The exception being thrown.
     * @return Response body with 'NOT FOUND' status and exception message.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<String> handleNotFound(UserNotFoundException ex) {
        log.warn("UserNotFoundException caught: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * This method catches the MethodArgumentNotValidException while trying to read the data from the request
     * and maps it to a structured error response.
     * @param ex The error thrown by Spring.
     * @param request Information about the web request that caused the error.
     * @return A response with a "Bad Request" status and details about the validation errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex,
                                                                    WebRequest request) {

        log.warn("MethodArgumentNotValidException caught: {}", ex.getMessage());

        List<ValidationError> validationErrors = ex.getBindingResult().getAllErrors().stream()
            .filter(error -> error instanceof FieldError)
            .map(error -> (FieldError) error)
            .map(fieldError ->
                new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
            .collect(Collectors.toList());

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
            .message("Validation failed for input data.")
            .path(request.getDescription(false).replace("uri=", ""))
            .validationErrors(validationErrors).build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * This method handles custom validation errors (e.g., User taken) and maps the response to an error message.
     * @param ex The exception thrown.
     * @param request Information about the request that caused the error.
     * @return Response with 'Bad Request' status and detailed error body.
     */
    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<Object> handleBusinessValidationException(BusinessValidationException ex,
                                                                    WebRequest request) {
        log.warn("BusinessValidationException caught: {}", ex.getMessage());

        Map<String, Object> body = Map.of(
            "timestamp", LocalDateTime.now(),
            "status", HttpStatus.BAD_REQUEST.value(),
            "error", HttpStatus.BAD_REQUEST.getReasonPhrase(),
            "message", ex.getMessage(),
            "validationErrors", ex.getErrors(),
            "path", request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * This method is used to catch unexpected errors.
     * @param ex Exception being thrown.
     * @param request The request that caused the error.
     * @return Response with 'INTERNAL SERVER ERROR' and a detailed error message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllExceptions(Exception ex, WebRequest request) {
        log.error("Unknown error: {}", ex.getMessage(), ex);

        Map<String, Object> body = Map.of(
            "timestamp", LocalDateTime.now(),
            "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "error", HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            "message", "Internal server error.",
            "path", request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
