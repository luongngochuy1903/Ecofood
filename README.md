# EcoFood backend (`app`)

## Known gaps before deploying with real per-tenant subdomains

The tenant-switcher / multi-origin login flow was built and tested against
`*.localhost` subdomains in local development. Two things are currently
hardcoded for that local setup and **must** be changed before onboarding any
tenant on a real production domain, or Keycloak/CORS will reject requests
with the exact same errors already hit and fixed during local development.

### 1. `app.tenant.redirect-scheme` defaults to `http`

Each tenant's Keycloak client gets its `redirectUri`/`webOrigins` built as
`{app.tenant.redirect-scheme}://{tenant.domainUrl}` at onboarding time (see
`TenantServiceImpl.buildPublicClient`). The default is `http`, which is
correct for local dev (`http://acme.localhost:5173/*`) but wrong for a real
deployment served over HTTPS.

**Fix**: set the env var before onboarding any production tenant:
```
APP_TENANT_REDIRECT_SCHEME=https
```
Getting this wrong manifests as a `400 Bad Request` from Keycloak's
`/protocol/openid-connect/auth` endpoint (redirect_uri mismatch) — the same
symptom hit when the local dev port changed from 3000 to 5173.

### 2. `SecurityConfig`'s CORS allow-list is a fixed local-dev list

```java
configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
```

In production, every tenant lives on its own subdomain. This fixed list
won't include any of them, so every cross-origin call from a tenant's real
domain to this backend will fail with `403 Invalid CORS request` — the same
error hit when `EcoFood-FE`'s dev port didn't match the allow-list.

**Fix needed**: replace the fixed list with a pattern match covering the
real production domain structure, e.g.:
```java
configuration.setAllowedOriginPatterns(List.of("https://*.ecofood.com"));
```
(`setAllowedOriginPatterns`, not `setAllowedOrigins`, is required for
wildcard patterns when `allowCredentials` is also set.)

Not yet implemented — tracked here so it isn't forgotten before a real
deploy.
