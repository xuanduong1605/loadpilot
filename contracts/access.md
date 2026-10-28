# Browser access contract (F-014 / roadmap 2a)

The initial deployment retains the configured single `OPERATOR`. The user's login/connectivity request authorizes implementing the roadmap's proposed session flow. Public registration, password reset and multiple roles remain outside this slice; broader TBD-03 applicability remains open. Every authenticated operator can access current read APIs. Future mutation features must add their own policy gates.

Same-origin `/api/access` endpoints use backend-managed, in-memory servlet sessions. No Basic authentication or credential storage in the browser. Restart requires signing in again. All access responses use `Cache-Control: no-store`. Timestamps use UTC ISO-8601.

| Method/path | Input | Response |
| --- | --- | --- |
| GET `/csrf` | none; public | 200 `{ "headerName": "X-CSRF-TOKEN", "token": "opaque" }`; establishes anonymous session |
| POST `/login` | URL-encoded `username`, `password`; CSRF header | 200 `{ "username": "admin", "role": "OPERATOR", "idleTimeoutSeconds": 1800 }`; rotates session ID and clears CSRF token |
| GET `/session` | session cookie | 200 same identity with configured idle timeout; 401 if anonymous/expired |
| POST `/logout` | session cookie; fresh CSRF header | 204; invalidates session and deletes cookie |

After login/logout fetch a new CSRF token before another POST. Any missing/invalid CSRF token yields 403, including login/logout. Anonymous protected APIs yield 401 without a Basic challenge or HTML redirect. Wrong credentials yield 401 `INVALID_CREDENTIALS`; no user enumeration. Ten login attempts in any rolling minute per controller yield 429 `LOGIN_THROTTLED` with `Retry-After: 60`. The global limit bounds brute force for the single operator without trusting proxy IP headers. A controller restart resets the limiter.

Errors use `{ "code": "UNAUTHENTICATED", "message": "Sign in to continue.", "timestamp": "2026-10-27T00:00:00Z" }`. Codes include `FORBIDDEN`, `CSRF_INVALID`, `INVALID_CREDENTIALS`, `LOGIN_THROTTLED`. No submitted credentials, raw exceptions or database details are returned or audited.

Session defaults: 30-minute idle expiry, one active authenticated session per operator, HttpOnly cookie `LOADPILOT_SESSION`, path `/`, SameSite=Lax. Local loopback HTTP uses Secure=false; TLS deployments must set `SESSION_COOKIE_SECURE=true`. This does not authorize transmitting credentials over public HTTP. Successful login rotates the session ID; logout/expiry/restart removes access. Static HTML is public; backend APIs enforce access. UI hides workspace until session validation succeeds, preserves only a validated same-origin workspace return path, and redirects on expiry.

Audit migration stores UTC event time, configured actor (or null for failed login), action, object and outcome. Login success/failure, the first throttled attempt per window and explicit logout are durable; repeated throttled requests do not amplify database writes. No passwords, tokens, cookies or arbitrary submitted usernames. Audit persistence failure must fail authentication closed. Idle expiry is not extended by periodic UI polling.

Verification: focused Spring security tests for CSRF, wrong login, fixation, session read/logout, denied APIs, cookie settings and throttling; PostgreSQL integration for migrations/audit; browser flow via dev proxy and exported Nginx. Missing runtime prerequisites remain incomplete checks.
