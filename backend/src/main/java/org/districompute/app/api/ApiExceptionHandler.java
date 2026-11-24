package org.districompute.app.api;

import java.net.URI;
import java.util.List;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every API error as RFC 9457 Problem Details with two extra members: {@code code} (stable,
 * machine-readable) and {@code violations} (invalid fields). Internal details are logged, never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.violations());
    }

    @ExceptionHandler(DistriComputeException.class)
    ResponseEntity<ProblemDetail> core(DistriComputeException e) {
        List<ApiException.FieldViolation> violations = e.violations().stream()
                .map(v -> new ApiException.FieldViolation(v.field(), v.code(), v.message())).toList();
        HttpStatus status = switch (e.code()) {
            case UNKNOWN_ALGORITHM -> HttpStatus.NOT_FOUND;
            case RESOURCE_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR, SERIALIZATION_FAILED, EXECUTION_FAILED, AGGREGATION_FAILED -> {
                LOG.error("Component error", e);
                yield HttpStatus.INTERNAL_SERVER_ERROR;
            }
            default -> HttpStatus.BAD_REQUEST;
        };
        String message = e.code() == ErrorCode.INTERNAL_ERROR ? "Internal error in a component" : e.getMessage();
        return problem(status, e.code().name(), message, violations);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException e) {
        List<ApiException.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldViolation(f.getField(), "INVALID", f.getDefaultMessage())).toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid", violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> malformed(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read: "
                + firstLine(e.getMessage()), List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> illegalArgument(IllegalArgumentException e) {
        LOG.debug("Rejected argument", e);
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", firstLine(e.getMessage()), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_TOO_LARGE", "The uploaded file is larger than allowed",
                List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such resource", List.of());
    }

    /** A client closed a live stream (or another long request): nothing can or needs to be answered. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void disconnected(AsyncRequestNotUsableException e) {
        LOG.debug("Client disconnected: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        LOG.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred; it has "
                + "been logged", List.of());
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message,
            List<ApiException.FieldViolation> violations) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("https://districompute.org/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        if (!violations.isEmpty()) {
            detail.setProperty("violations", violations);
        }
        return ResponseEntity.status(status).body(detail);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable input";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
