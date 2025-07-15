package org.lukawska.springaop.locking.exception;

public class LockAcquisitionException extends RuntimeException {
    /**
     * Constructs a new LockAcquisitionException with the specified detail message.
     *
     * @param message The detail message (which is saved for later retrieval by the getMessage() method).
     */
    public LockAcquisitionException(String message) {
        super(message);
    }

    /**
     * Constructs a new LockAcquisitionException with the specified detail message and cause.
     *
     * @param message The detail message.
     * @param cause   The cause (which is saved for later retrieval by the getCause() method).
     *                (A null value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new LockAcquisitionException with the specified cause and a detail message of
     * (cause==null ? null : cause.toString()) (which typically contains the class and detail message of cause).
     *
     * @param cause The cause (which is saved for later retrieval by the getCause() method).
     *              (A null value is permitted and indicates that the cause is nonexistent or unknown.)
     */
    public LockAcquisitionException(Throwable cause) {
        super(cause);
    }
}
