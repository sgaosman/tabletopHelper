package com.tabletophelper.auth;

import com.tabletophelper.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh token (D156). The browser holds the random value in an httpOnly cookie; this row holds
 * only its SHA-256 hash. Every token issued from one login shares a family, so reusing a replaced
 * token can revoke all of them.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", length = 64, unique = true, nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** The token this one was exchanged for; set once the token has been used. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
