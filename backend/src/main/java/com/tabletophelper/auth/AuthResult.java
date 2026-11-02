package com.tabletophelper.auth;

import com.tabletophelper.auth.dto.AuthResponse;

/**
 * What a registration, login or refresh gives the controller: the response body, and the refresh
 * token it puts in the cookie rather than the body (D156).
 */
public record AuthResult(AuthResponse response, String refreshToken) {}
