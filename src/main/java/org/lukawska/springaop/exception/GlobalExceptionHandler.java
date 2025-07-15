package org.lukawska.springaop.exception;

import org.lukawska.springaop.validation.exception.BusinessValidationException;
import org.lukawska.springaop.validation.exception.ValidationError;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * This method catches the UserAlreadyExistsException.
     *
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
     *
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
     *
     * @param ex      The error thrown by Spring.
     * @param request Information about the web request that caused the error.
     * @return A response with a "Bad Request" status and details about the validation errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(MethodArgumentNotValidException ex,
                                                             WebRequest request) {
        log.warn("MethodArgumentNotValidException caught: {}", ex.getMessage());
        Map<String, Object> body = mapToErrorResponse(HttpStatus.BAD_REQUEST, ex, request);

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * This method handles custom validation errors (e.g., User taken) and maps the response to an error message.
     *
     * @param ex      The exception thrown.
     * @param request Information about the request that caused the error.
     * @return Response with 'Bad Request' status and detailed error body.
     */
    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<Object> handleBusinessValidationException(BusinessValidationException ex,
                                                                    WebRequest request) {
        log.warn("BusinessValidationException caught: {}", ex.getMessage());
        Map<String, Object> body = mapToErrorResponse(HttpStatus.BAD_REQUEST, ex, request);

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * This method is used to catch unexpected errors.
     *
     * @param ex      Exception being thrown.
     * @param request The request that caused the error.
     * @return Response with 'INTERNAL SERVER ERROR' and a detailed error message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllExceptions(Exception ex, WebRequest request) {
        log.error("Unknown error: {}", ex.getMessage(), ex);
        Map<String, Object> body = mapToErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex, request);

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private Map<String, Object> mapToErrorResponse(HttpStatus status,
                                                   Exception exception,
                                                   WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", exception.getMessage());
        body.put("path", request.getDescription(false).replace("uri=", ""));

        if (exception instanceof BusinessValidationException ex) {
            body.put("validationErrors", ex.getErrors());
            return body;
        }

        if (exception instanceof MethodArgumentNotValidException ex) {
            List<ValidationError> validationErrors = ex.getBindingResult().getAllErrors().stream()
                .filter(error -> error instanceof FieldError)
                .map(error -> (FieldError) error)
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
            body.put("validationErrors", validationErrors);
            return body;
        }

        return body;
    }
}
