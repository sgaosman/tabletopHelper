package com.tabletophelper.config;

import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new ServerProperties());
        request = new MockHttpServletRequest("POST", "/api/characters");
    }

    private static void assertError(ResponseEntity<Map<String, String>> response, HttpStatus status, String error) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Map.of("error", error), response.getBody());
    }

    @Test
    @DisplayName("NotFoundException returns 404 with its message")
    void handleNotFound_returns404() {
        assertError(handler.handleNotFound(new NotFoundException("Character not found")),
                HttpStatus.NOT_FOUND, "Character not found");
    }

    @Test
    @DisplayName("ForbiddenException returns 403 with its message")
    void handleForbidden_returns403() {
        assertError(handler.handleForbidden(new ForbiddenException("Only the DM can perform this action")),
                HttpStatus.FORBIDDEN, "Only the DM can perform this action");
    }

    @Test
    @DisplayName("ConflictException returns 409 with its message")
    void handleConflict_returns409() {
        assertError(handler.handleConflict(new ConflictException("Character is already at maximum level")),
                HttpStatus.CONFLICT, "Character is already at maximum level");
    }

    @Test
    @DisplayName("ValidationException returns 400 with its message")
    void handleValidation_returns400() {
        assertError(handler.handleValidation(new ValidationException("Invalid username or password")),
                HttpStatus.BAD_REQUEST, "Invalid username or password");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException returns 400 listing each field error")
    void handleInvalidFields_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "level", "must be greater than 0"));
        bindingResult.addError(new FieldError("request", "strength", "must be less than or equal to 30"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        assertError(handler.handleInvalidFields(ex), HttpStatus.BAD_REQUEST,
                "level: must be greater than 0; strength: must be less than or equal to 30");
    }

    @Test
    @DisplayName("A path variable of the wrong type returns 400 naming the parameter and value")
    void handleTypeMismatch_returns400() {
        var ex = new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "characterId", null,
                new IllegalArgumentException("Invalid UUID string: not-a-uuid"));

        assertError(handler.handleTypeMismatch(ex), HttpStatus.BAD_REQUEST, "Invalid value for characterId: not-a-uuid");
    }

    @Test
    @DisplayName("An unreadable request body returns 400 without the parser's details")
    void handleUnreadableBody_returns400() {
        var ex = new HttpMessageNotReadableException("JSON parse error: Unexpected character",
                new MockHttpInputMessage(new byte[0]));

        assertError(handler.handleUnreadableBody(ex), HttpStatus.BAD_REQUEST, "Request body is missing or malformed");
    }

    @Test
    @DisplayName("A missing required parameter returns 400 naming it")
    void handleMissingParameter_returns400() {
        var ex = new MissingServletRequestParameterException("name", "String");

        assertError(handler.handleMissingParameter(ex), HttpStatus.BAD_REQUEST, "Missing required parameter: name");
    }

    @Test
    @DisplayName("An optimistic locking failure returns 409 instead of 500 (D034)")
    void handleOptimisticLock_returns409() {
        var ex = new OptimisticLockingFailureException("Row was updated or deleted by another transaction");

        assertError(handler.handleOptimisticLock(ex), HttpStatus.CONFLICT,
                "Someone else changed this at the same time. Please try again.");
    }

    @Test
    @DisplayName("An unexpected exception returns 500 with a generic message outside dev")
    void handleUnexpected_hidesMessageByDefault() throws Exception {
        var ex = new IllegalStateException("Failed to recalculate spell slots: bad JSON");

        assertError(handler.handleUnexpected(ex, request), HttpStatus.INTERNAL_SERVER_ERROR,
                GlobalExceptionHandler.UNEXPECTED_ERROR);
    }

    @Test
    @DisplayName("With include-message set to always, as in dev, an unexpected exception shows its message")
    void handleUnexpected_showsMessageInDev() throws Exception {
        ServerProperties devProperties = new ServerProperties();
        devProperties.getError().setIncludeMessage(ErrorProperties.IncludeAttribute.ALWAYS);
        handler = new GlobalExceptionHandler(devProperties);

        var ex = new IllegalStateException("Failed to recalculate spell slots: bad JSON");

        assertError(handler.handleUnexpected(ex, request), HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to recalculate spell slots: bad JSON");
    }

    @Test
    @DisplayName("An unexpected exception without a message still gets the generic message in dev")
    void handleUnexpected_withoutMessageUsesGenericMessage() throws Exception {
        ServerProperties devProperties = new ServerProperties();
        devProperties.getError().setIncludeMessage(ErrorProperties.IncludeAttribute.ALWAYS);
        handler = new GlobalExceptionHandler(devProperties);

        assertError(handler.handleUnexpected(new RuntimeException(), request), HttpStatus.INTERNAL_SERVER_ERROR,
                GlobalExceptionHandler.UNEXPECTED_ERROR);
    }

    @Test
    @DisplayName("Spring's own 404 and 405 exceptions are rethrown so Spring answers them, not turned into 500s")
    void handleUnexpected_rethrowsSpringErrors() {
        var notFound = new NoHandlerFoundException("GET", "/api/nope", new HttpHeaders());
        var wrongMethod = new HttpRequestMethodNotSupportedException("PATCH");

        assertSame(notFound, assertThrows(NoHandlerFoundException.class,
                () -> handler.handleUnexpected(notFound, request)));
        assertSame(wrongMethod, assertThrows(HttpRequestMethodNotSupportedException.class,
                () -> handler.handleUnexpected(wrongMethod, request)));
    }
}
