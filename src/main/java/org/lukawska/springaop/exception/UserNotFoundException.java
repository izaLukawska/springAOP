package org.lukawska.springaop.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("User not found for ID: " + id);
    }
}
