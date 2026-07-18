# Authentication Flow

## Overview

QuestKeeper uses stateless JWT authentication. Passwords are hashed with BCrypt. Access tokens expire after 1 hour; refresh tokens after 7 days. Tokens are signed with HS512.

## Token Lifecycle

```
┌────────────┐                    ┌────────────┐
│   Client   │                    │   Server   │
└─────┬──────┘                    └─────┬──────┘
      │                                 │
      │  POST /api/auth/login           │
      │  {username, password}           │
      │ ──────────────────────────────► │
      │                                 │  Lookup user by username
      │                                 │  BCrypt.verify(password, hash)
      │                                 │  Generate access token (1h)
      │                                 │  Generate refresh token (7d)
      │  {accessToken, refreshToken}    │
      │ ◄────────────────────────────── │
      │                                 │
      │  Store tokens in localStorage   │
      │                                 │
      │  GET /api/campaigns             │
      │  Authorization: Bearer <access> │
      │ ──────────────────────────────► │
      │                                 │  JwtAuthenticationFilter:
      │                                 │    Extract token from header
      │                                 │    Validate signature + expiry
      │                                 │    Set SecurityContext
      │  [{campaign}, ...]              │
      │ ◄────────────────────────────── │
      │                                 │
      │  ... 1 hour later ...           │
      │                                 │
      │  GET /api/campaigns             │
      │  Authorization: Bearer <access> │
      │ ──────────────────────────────► │
      │                                 │  Token expired
      │  401 Unauthorized               │
      │ ◄────────────────────────────── │
      │                                 │
      │  Axios interceptor triggers:    │
      │  POST /api/auth/refresh         │
      │  {refreshToken}                 │
      │ ──────────────────────────────► │
      │                                 │  Validate refresh token
      │                                 │  Generate new token pair
      │  {accessToken, refreshToken}    │
      │ ◄────────────────────────────── │
      │                                 │
      │  Retry original request         │
      │  with new access token          │
      │ ──────────────────────────────► │
      │  [{campaign}, ...]              │
      │ ◄────────────────────────────── │
```

## JWT Payload

```json
{
  "sub": "816e42b5-1627-4b20-8821-de609320338d",
  "username": "aragorn",
  "iat": 1784326082,
  "exp": 1784329682
}
```

- `sub` — User UUID (used as the authentication principal throughout the backend)
- `username` — Stored as authentication details for convenience
- `iat` / `exp` — Standard JWT issued-at and expiry timestamps

## Frontend Token Management

Tokens are stored in `localStorage` for persistence across page refreshes. The Axios interceptor (`api/axiosConfig.ts`) handles:

1. **Attaching tokens** — Every request gets `Authorization: Bearer <token>` from localStorage
2. **Auto-refresh on 401** — If a request fails with 401, the interceptor tries to refresh the token and retry the original request
3. **Logout on refresh failure** — If the refresh token is also expired, localStorage is cleared and the user is redirected to `/login`

## Security Considerations

- **JWT secret** — Stored in environment variable `JWT_SECRET`, not in source code. The dev fallback in `application.yml` is intentionally long (64+ bytes for HS512) but must be replaced in production.
- **BCrypt** — Passwords are hashed with BCrypt (cost factor 10, Spring Security default). Raw passwords are never stored or logged.
- **CORS** — Only `http://localhost:5173` is allowed in dev. Production should restrict to the production domain only.
- **Token storage** — localStorage is vulnerable to XSS but acceptable for this use case (small trusted group, no sensitive financial data). HttpOnly cookies would be more secure but complicate WebSocket auth.
- **No token revocation** — There is no server-side token blacklist. If a token is compromised, it remains valid until expiry. The 1-hour access token window limits exposure. For a 9-user personal project, this trade-off is acceptable.

## WebSocket Authentication

WebSocket connections include the JWT as a query parameter during the STOMP CONNECT handshake. A `ChannelInterceptor` validates it before allowing the connection. See [[websocket-protocol]] for details.
