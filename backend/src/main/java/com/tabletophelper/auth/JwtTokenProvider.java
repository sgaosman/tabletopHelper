package com.tabletophelper.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and reads access tokens, the only JWTs the app issues (D156). Each carries
 * {@code type: access}, and a token without it is refused, so nothing else signed with the same key
 * works as a bearer token or at STOMP CONNECT.
 */
@Component
public class JwtTokenProvider {

    /** HMAC-SHA256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;
    static final String TYPE_CLAIM = "type";
    static final String ACCESS_TYPE = "access";

    private final SecretKey key;
    private final long accessTokenExpirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be set to at least " + MIN_SECRET_BYTES
                    + " bytes, but app.jwt.secret has " + secretBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateAccessToken(UUID userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim(TYPE_CLAIM, ACCESS_TYPE)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Reads a bearer token. Empty unless the signature is valid, the token has not expired and it
     * carries {@code type: access}; tokens issued before D156 have no type and are refused.
     */
    public Optional<AccessTokenClaims> parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .require(TYPE_CLAIM, ACCESS_TYPE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getSubject() == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()), claims.get("username", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The user an access token was issued to. */
    public record AccessTokenClaims(UUID userId, String username) {}
}
