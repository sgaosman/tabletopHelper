package com.tabletophelper.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("IllegalArgumentException returns 400 Bad Request with error message")
    void handleIllegalArgument_returns400() {
        IllegalArgumentException ex = new IllegalArgumentException("Character not found");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Character not found", response.getBody().get("error"));
    }

    @Test
    @DisplayName("IllegalStateException returns 409 Conflict with error message")
    void handleIllegalState_returns409() {
        IllegalStateException ex = new IllegalStateException("Cannot level up: already at max level");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Cannot level up: already at max level", response.getBody().get("error"));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException returns 400 with field errors")
    void handleValidation_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "level", "must be greater than 0"));
        bindingResult.addError(new FieldError("request", "strength", "must be less than or equal to 30"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        String error = response.getBody().get("error");
        assertTrue(error.contains("level"));
        assertTrue(error.contains("strength"));
    }

    @Test
    @DisplayName("Error response body always contains 'error' key")
    void allHandlers_returnErrorKey() {
        ResponseEntity<Map<String, String>> argResponse =
                handler.handleIllegalArgument(new IllegalArgumentException("test"));
        ResponseEntity<Map<String, String>> stateResponse =
                handler.handleIllegalState(new IllegalStateException("test"));

        assertTrue(argResponse.getBody().containsKey("error"));
        assertTrue(stateResponse.getBody().containsKey("error"));
    }
}
