package org.districompute.app.api;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * An error with an HTTP status and a stable machine-readable code, rendered as RFC 9457 Problem Details by
 * {@link ApiExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldViolation> violations;

    /**
     * Creates an error.
     *
     * @param status HTTP status
     * @param code stable code, for example {@code NOT_FOUND}
     * @param message human-readable message
     * @param violations field violations (may be empty)
     */
    public ApiException(HttpStatus status, String code, String message, List<FieldViolation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    /**
     * A 404 error.
     *
     * @param what kind of resource
     * @param id its id
     * @return the error
     */
    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " does not exist", List.of());
    }

    /**
     * A 400 error.
     *
     * @param code code
     * @param message message
     * @return the error
     */
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, List.of());
    }

    /**
     * A 400 validation error.
     *
     * @param message summary
     * @param violations violations
     * @return the error
     */
    public static ApiException invalid(String message, List<FieldViolation> violations) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, violations);
    }

    /**
     * A 409 error.
     *
     * @param code code
     * @param message message
     * @return the error
     */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of());
    }

    /**
     * A 403 error.
     *
     * @param message message
     * @return the error
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, List.of());
    }

    /**
     * A 429 error.
     *
     * @param message message
     * @return the error
     */
    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message, List.of());
    }

    /**
     * A 503 error.
     *
     * @param code code
     * @param message message
     * @return the error
     */
    public static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, List.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    /**
     * One invalid field.
     *
     * @param field field path (Core parameter name or request property)
     * @param code violation code
     * @param message explanation
     */
    public record FieldViolation(String field, String code, String message) {
    }
}
