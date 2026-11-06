package com.tabletophelper.config;

import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import jakarta.persistence.LockTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Maps exceptions to status codes with an {@code {"error": ...}} body (D155). Domain exceptions carry
 * a message meant for users, which is always returned. Anything unexpected is a 500 whose message is
 * shown only where {@code spring.web.error.include-message} is {@code always}, which is the dev profile.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String UNEXPECTED_ERROR = "Something went wrong. Please try again.";
    static final String LOCK_TIMEOUT = "Another change is still in progress. Please try again in a moment.";

    private final boolean includeMessage;

    public GlobalExceptionHandler(WebProperties webProperties) {
        this.includeMessage = webProperties.getError().getIncludeMessage()
                == ErrorProperties.IncludeAttribute.ALWAYS;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(ValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidFields(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        return error(HttpStatus.BAD_REQUEST, message);
    }

    /** A path variable or query parameter of the wrong type, such as a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName() + ": " + ex.getValue());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or malformed");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Missing required parameter: " + ex.getParameterName());
    }

    /**
     * Two people changed the same encounter at once (D034), whose next broadcast carries the winner, or a write from
     * a copy of a character that a command has changed since, which the character's version catches (D158).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "Someone else changed this at the same time. Please try again.");
    }

    /**
     * A lock wait ran out: an encounter or character command waited more than five seconds for the one
     * before it (D158, D160), or two transactions deadlocked. The transaction rolled back, so trying again
     * is safe.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, LockTimeoutException.class})
    public ResponseEntity<Map<String, String>> handleLockTimeout(RuntimeException ex) {
        return error(HttpStatus.CONFLICT, LOCK_TIMEOUT);
    }

    /**
     * Everything else is a 500. Spring's own exceptions, such as an unknown path (404) or an
     * unsupported method (405), are rethrown so Spring answers them as it normally would.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        String message = includeMessage && ex.getMessage() != null ? ex.getMessage() : UNEXPECTED_ERROR;
        return error(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
