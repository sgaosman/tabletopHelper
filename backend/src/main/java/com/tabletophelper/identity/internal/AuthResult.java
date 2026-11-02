package com.tabletophelper.identity.internal;

import com.tabletophelper.identity.internal.dto.AuthResponse;

/**
 * What a registration, login or refresh gives the controller: the response body, and the refresh
 * token it puts in the cookie rather than the body (D156).
 */
public record AuthResult(AuthResponse response, String refreshToken) {}
