# Authentication Flow

## Overview

TabletopHelper authenticates requests with short-lived JWT access tokens and keeps sessions going with rotating refresh tokens (D156, R3). Passwords are hashed with BCrypt.

- **Access tokens** are JWTs that expire after 15 minutes (`app.jwt.access-token-expiration-ms: 900000`) and carry `type: access`. They are checked without a database lookup, so REST and STOMP authentication stay stateless. They are signed with HMAC-SHA using `JWT_SECRET`: jjwt picks HS512 for a secret of 64 bytes or more (the dev secret, and the `openssl rand -hex 64` recipe in [[deployment-guide]]), and HS384 or HS256 for shorter secrets down to the 32-byte minimum.
- **Refresh tokens** are random values, not JWTs. The browser holds one in the HttpOnly `refresh_token` cookie, and the server stores only its SHA-256 hash in `refresh_tokens`. Each refresh replaces it with a new token valid for 7 days.

**History (superseded in R3):** access tokens lasted 1 hour, and refresh tokens were 7-day JWTs that differed from them only in expiry. Both came back in the response body and were kept in localStorage, the frontend refreshed on 401 or 403, and nothing could be revoked (D003, D017; replaced by D155 and D156).

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
      │                                 │  Start a token family:
      │                                 │    random refresh token (7d),
      │                                 │    store its SHA-256 hash
      │                                 │  Generate access token (15m)
      │  {userId, ..., accessToken}     │
      │  Set-Cookie: refresh_token=...  │
      │ ◄────────────────────────────── │
      │                                 │
      │  Keep access token in memory    │
      │  (the browser keeps the cookie) │
      │                                 │
      │  GET /api/campaigns             │
      │  Authorization: Bearer <access> │
      │ ──────────────────────────────► │
      │                                 │  JwtAuthenticationFilter:
      │                                 │    Extract token from header
      │                                 │    Check signature, expiry
      │                                 │    and type: access
      │                                 │    Set SecurityContext
      │  [{campaign}, ...]              │
      │ ◄────────────────────────────── │
      │                                 │
      │  ... 15 minutes later ...       │
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
      │  Cookie: refresh_token=...      │
      │ ──────────────────────────────► │
      │                                 │  Find token by hash (row locked)
      │                                 │  Replace it with a new one (7d)
      │                                 │  Generate access token (15m)
      │  {userId, ..., accessToken}     │
      │  Set-Cookie: refresh_token=...  │
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
  "type": "access",
  "iat": 1784326082,
  "exp": 1784326982
}
```

- `sub` — User UUID (used as the authentication principal throughout the backend)
- `username` — Stored as authentication details for convenience
- `type` — Always `access`. The HTTP filter and the STOMP CONNECT check accept only a token that carries it, so a token issued before R3, which has no type, is refused (D156)
- `iat` / `exp` — Standard JWT issued-at and expiry timestamps, 15 minutes apart

## Refresh Tokens (D156)

A refresh token is 32 random bytes, base64url-encoded. Only its SHA-256 hash (lowercase hex) is stored, in `refresh_tokens` (see [[database-schema#refresh_tokens]]).

- **Families.** Each login or registration starts a new token family, and every token issued by refreshing it joins the same family.
- **Rotation.** `POST /api/auth/refresh` reads the cookie, locks the token's row, issues a new token in the same family valid for 7 days, records it in the old token's `replaced_by_id`, and returns a new access token. A session therefore lasts as long as it is refreshed at least once every 7 days.
- **Reuse detection.** Presenting a token that was already replaced means someone else holds a copy, so the whole family is revoked and the request gets 401. The revocation is committed even though the request fails. The row lock makes two requests with the same token run one after the other, so the second counts as reuse; this is why browser tabs take turns refreshing (see below).
- **Expired, revoked or unknown tokens**, and a missing cookie, get 401 with `{"error": "Your session has ended. Please sign in again."}`.
- **Logout.** `POST /api/auth/logout` revokes the family of the token in the cookie (a missing or unknown token is ignored) and answers 204 with a cookie that deletes `refresh_token`.

Expired and revoked rows are not cleaned up yet.

## The `refresh_token` Cookie

`AuthController` sets it on registration, login and every refresh:

| Attribute | Value | Why |
|-----------|-------|-----|
| HttpOnly | always | Page scripts can't read the token |
| SameSite | Strict | The browser never sends it with cross-site requests |
| Path | `/api/auth` | It goes only to the auth endpoints, never to other API calls |
| Max-Age | 7 days, renewed by each refresh | Matches the token's lifetime |
| Secure | on, except in the dev profile | Browsers only store a Secure cookie over HTTPS |

`app.auth.cookie-secure` is `true` in `application.yml` and `false` only in `application-dev.yml`, because local development runs on plain HTTP. Outside dev, a deployment on plain HTTP would lose the cookie and sign everyone out on every reload, so deploying R3 needs the Hosting step's HTTPS first (D154).

## Frontend Token Management

Nothing is stored in localStorage (superseded in R3). On load, `AuthContext` deletes the old `accessToken`, `refreshToken` and `user` keys from any browser that still has them.

- **`api/tokenStore.ts`** keeps the access token in module memory. `refreshAccessToken()` posts to `/api/auth/refresh` with plain axios (`withCredentials`), so a failed refresh never re-enters the interceptor. Callers in one tab share a single request, and tabs take turns through the Web Locks API (`navigator.locks`, lock `tabletophelper-token-refresh`), so each refresh sends the cookie the previous one set. Browsers without Web Locks refresh without the lock.
- **`api/axiosConfig.ts`** attaches `Authorization: Bearer <token>` to every request. On a **401 only** (a 403 is a refusal that a new token wouldn't change) it refreshes and retries the request once. It never refreshes for a request that carried no token, or for any `/auth/*` request, since login, register and refresh report their own failures. If the refresh fails, it clears the token and redirects to `/login` (D155).
- **`context/AuthContext.tsx`** starts with status `loading` and calls `refreshAccessToken()` when the app starts. If the browser has a valid cookie, the status becomes `authenticated`; otherwise `anonymous`. The route guards in `App.tsx` show "Loading..." until then, so a reload briefly shows that state. `login` and `register` keep the returned access token in memory. `logout` calls `POST /api/auth/logout`, then clears the token and signs the tab out even if that call fails.
- Signing out in one tab ends the other tabs' sessions at their next refresh, within 15 minutes.

## Security Considerations

- **JWT secret** — `application.yml` has no default (`secret: ${JWT_SECRET:}`), and `JwtTokenProvider` stops startup when the secret is missing or shorter than 32 bytes, with `JWT_SECRET must be set to at least 32 bytes, but app.jwt.secret has N`. Only the dev profile has a built-in secret, in `application-dev.yml` (D156); the integration tests' `it` profile, which isn't packaged with the app, sets its own. Before R3, `application.yml` held a fallback secret committed to the repo, which any deployment without `JWT_SECRET` would have used.
- **BCrypt** — Passwords are hashed with BCrypt (cost factor 10, Spring Security default). Raw passwords are never stored or logged.
- **CORS** — `CORS_ALLOWED_ORIGINS` (comma-separated, default `http://localhost:5173`) lists the origins allowed to make cross-origin requests and, since R3, the origins `/ws` accepts (D157). Production sets the site's own origin (see [[deployment-guide]]).
- **Token storage** — The access token lives only in memory and the refresh token in an HttpOnly cookie, so script on the page can't read the refresh token. SameSite=Strict and Path=/api/auth keep the cookie off cross-site requests and off every other API call (D156). Superseded in R3: both tokens used to sit in localStorage, where a cross-site scripting attack could read them.
- **Revocation** — Refresh tokens can be revoked: logout revokes the login's whole family, and so does reusing a replaced token. Access tokens can't be revoked, so a stolen one works until it expires, at most 15 minutes. Superseded in R3: there was no revocation, and the 7-day refresh JWT also worked as a bearer token.

## WebSocket Authentication

The STOMP CONNECT frame carries the access token as a native header, `Authorization: Bearer <access token>`, not as a query parameter. `WebSocketAuthInterceptor` (in `identity.internal`, registered ahead of every other interceptor by `WebSocketAuthConfig`) accepts only an unexpired access token, and the client fetches a fresh one in `beforeConnect` before every connect and reconnect (D156). The token is checked only at CONNECT, so an open connection keeps working after it expires; the encounter subscription guard then checks each SUBSCRIBE and SEND against the connected user. See [[websocket-protocol]] for details.
