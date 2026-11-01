# ACL Role Model — Design

Status: **confirmed** — role shape and UI access rules agreed; ready to
implement (role codes, permission claims, nav filtering, route guards).
`CUSTOMER`'s own UI is explicitly deferred (§1.4).

## 1. Role shape

A complete role = **Role Actor** + **Section Role**, e.g. `ECO_ADMIN`,
`MASTER_PRODUCT_MANAGER`. One extra standalone role (`CUSTOMER`) sits
outside this grid entirely.

### 1.1 Role Actors (4) — who the tenant *is*

| Actor | Meaning | Maps to onboarding data |
|---|---|---|
| `ECO` | Platform admin — not scoped to any one tenant | no tenant; platform-level staff account |
| `ENTERPRISE` | A tenant onboarded as `partnerTypeCode` = `ENTERPRISE` or `REGIONAL_ENTERPRISE` | always `tenantType = MASTER_TENANT` (forced at onboarding — see `TenantOnboardingPage`'s `FORCED_MASTER_TENANT_TYPES`) |
| `MASTER` | A tenant onboarded as `BRAND`/`ECOSYSTEM_PARTNER` **and** `tenantType = MASTER_TENANT` | top of its own hierarchy, but not an Enterprise |
| `TENANT` | A tenant onboarded as `BRAND`/`ECOSYSTEM_PARTNER` **and** `tenantType = SUB_TENANT` | belongs to a Master Tenant via `partyRelationships[].relationshipTypeCode = MASTER_TENANT` |

### 1.2 Section Roles (4) — what the user does within that tenant

| Section role | Scope |
|---|---|
| `_ADMIN` | Full access to everything their Actor is allowed to see |
| `_PRODUCT_MANAGER` | Product Library only |
| `_PARTNERSHIP_MANAGER` | Partner tab + the "Users" sub-tab only |
| `_DASHBOARD_MANAGER` | Manager (dashboard) tab only |

### 1.3 Complete role grid (4 × 4, minus one)

| Actor \ Section | `_ADMIN` | `_PRODUCT_MANAGER` | `_PARTNERSHIP_MANAGER` | `_DASHBOARD_MANAGER` |
|---|---|---|---|---|
| `ECO` | `ECO_ADMIN` | `ECO_PRODUCT_MANAGER` | `ECO_PARTNERSHIP_MANAGER` | `ECO_DASHBOARD_MANAGER` |
| `ENTERPRISE` | `ENTERPRISE_ADMIN` | `ENTERPRISE_PRODUCT_MANAGER` | `ENTERPRISE_PARTNERSHIP_MANAGER` | `ENTERPRISE_DASHBOARD_MANAGER` |
| `MASTER` | `MASTER_ADMIN` | `MASTER_PRODUCT_MANAGER` | `MASTER_PARTNERSHIP_MANAGER` | `MASTER_DASHBOARD_MANAGER` |
| `TENANT` | `TENANT_ADMIN` | `TENANT_PRODUCT_MANAGER` | `TENANT_PARTNERSHIP_MANAGER` | `TENANT_DASHBOARD_MANAGER` |

**Revised**: `TENANT_PARTNERSHIP_MANAGER` DOES exist (reversing this doc's
original call that it shouldn't) — scoped to **read-only visibility of its
own tenant hierarchy**, not onboarding/managing partners: it sees only the
Partner tab's Tenant sub-tab (never Tenant Onboarding), backed by
`GET /api/v1/tenants/directory` with `RoleLevel.TENANT`, which naturally
returns just that caller's own tenant row (a sub-tenant can never have
further sub-tenants of its own).

**Total: 16 actor×section roles + `CUSTOMER` = 17 roles.**

### 1.4 `CUSTOMER`

Not an actor×section combination. Available under **any** tenant type
(Enterprise, Master, or Tenant) — it's the end-customer identity for that
tenant's own customers, not a staff role. Gets an **entirely separate UI**
(not this admin console's navbar at all) — **deferred**: not designed yet,
ignore it for the first implementation pass. The only rule that applies to
it from this doc is: it still gets the Account tab (§3.3), and it never
gets the tenant switcher.

## 2. Navbar tabs (current EcoFood-FE nav tree)

```
Home
Partner
 ├─ Tenant
 └─ Tenant Onboarding
Manager
Product Library
Users
 ├─ Users
 ├─ Roles
 ├─ Permission
 └─ Data Access Rules
Account
 ├─ Account
 └─ Log out
```
Plus the **tenant switcher**, which lives in the sidebar/topbar rather than
the nav tree, but is access-controlled the same way as a tab.

## 3. Visibility rules

Resolved in two passes: **Actor-level base visibility**, then
**Section-role narrowing**.

### 3.1 Actor-level base visibility (which tabs the Actor can ever see)

| Actor | Partner tab | Everything else |
|---|---|---|
| `ECO` | ✅ visible | ✅ all tabs |
| `ENTERPRISE` | ✅ visible | ✅ all tabs |
| `MASTER` | ✅ visible | ✅ all tabs |
| `TENANT` | ⚠️ **only** for `_ADMIN`/`_PARTNERSHIP_MANAGER` (Tenant sub-tab only, never Onboarding — see 3.2) | ✅ all tabs |

`ENTERPRISE` gets full actor-level tab access, same as `MASTER`/`ECO` —
confirmed.

### 3.2 Section-role narrowing (on top of 3.1)

| Section role | Visible tabs |
|---|---|
| `_ADMIN` | Everything the Actor is allowed to see (3.1), unnarrowed — for `TENANT_ADMIN` specifically, that still means only the Tenant sub-tab of Partner, never Onboarding (3.1 itself caps it there) |
| `_PRODUCT_MANAGER` | **Only** Product Library |
| `_PARTNERSHIP_MANAGER` | **Only** Partner tab (both sub-tabs for ECO/ENTERPRISE/MASTER; **Tenant sub-tab only, never Onboarding, for TENANT**) + Users tab, but **only** the "Users" sub-tab (not Roles/Permission/Data Access Rules) |
| `_DASHBOARD_MANAGER` | **Only** Manager tab |

`_PARTNERSHIP_MANAGER` and `_PRODUCT_MANAGER`/`_DASHBOARD_MANAGER` are
AND-ed with 3.1: `TENANT_PARTNERSHIP_MANAGER` is the one exception to "every
actor's `_PARTNERSHIP_MANAGER` sees the full Partner tab" — it's narrowed a
second time, down to just the Tenant sub-tab, since a sub-tenant still can't
onboard anything even though it can now view its own hierarchy.

### 3.3 Universal tabs (every role, including narrowed section roles)

| Tab | Who sees it |
|---|---|
| **Account** | Everyone, including `CUSTOMER` |
| **Tenant switcher** | Everyone **except** `CUSTOMER` |
| **Home** | Everyone — Home is **not role-gated at all**, same as Account. Confirmed: it is never swallowed by `_PRODUCT_MANAGER`/`_PARTNERSHIP_MANAGER`/`_DASHBOARD_MANAGER`'s "only see X" narrowing. |

### 3.4 `CUSTOMER`

Out of scope for the admin navbar entirely — "different UI explicitly" per
the brief. The only rule that applies to it from this matrix is: it still
gets the Account tab, and it never gets the tenant switcher.

## 4. Full role × tab matrix

`●` = full tab (all sub-tabs) · `◐` = tab present but only one sub-tab ·
`—` = not visible

| Role | Home | Partner | Manager | Product Library | Users | Account | Tenant switcher |
|---|---|---|---|---|---|---|---|
| `ECO_ADMIN` | ● | ● | ● | ● | ● | ● | ● |
| `ECO_PRODUCT_MANAGER` | ● | — | — | ● | — | ● | ● |
| `ECO_PARTNERSHIP_MANAGER` | ● | ● | — | — | ● | ● | ● |
| `ECO_DASHBOARD_MANAGER` | ● | — | ● | — | — | ● | ● |
| `ENTERPRISE_ADMIN` | ● | ● | ● | ● | ● | ● | ● |
| `ENTERPRISE_PRODUCT_MANAGER` | ● | — | — | ● | — | ● | ● |
| `ENTERPRISE_PARTNERSHIP_MANAGER` | ● | ● | — | — | ◐ (Users only) | ● | ● |
| `ENTERPRISE_DASHBOARD_MANAGER` | ● | — | ● | — | — | ● | ● |
| `MASTER_ADMIN` | ● | ● | ● | ● | ● | ● | ● |
| `MASTER_PRODUCT_MANAGER` | ● | — | — | ● | — | ● | ● |
| `MASTER_PARTNERSHIP_MANAGER` | ● | ● | — | — | ◐ (Users only) | ● | ● |
| `MASTER_DASHBOARD_MANAGER` | ● | — | ● | — | — | ● | ● |
| `TENANT_ADMIN` | ● | ◐ (Tenant only) | ● | ● | ● | ● | ● |
| `TENANT_PRODUCT_MANAGER` | ● | — | — | ● | — | ● | ● |
| `TENANT_PARTNERSHIP_MANAGER` | ● | ◐ (Tenant only) | — | — | ◐ (Users only) | ● | ● |
| `TENANT_DASHBOARD_MANAGER` | ● | — | ● | — | — | ● | ● |
| `CUSTOMER` *(deferred)* | separate UI, not this navbar | — | — | — | — | ● | — |

## 5. Decisions log

| # | Question | Decision |
|---|---|---|
| 1 | Does `ENTERPRISE` get full actor-level tab access like `MASTER`/`ECO`? | **Yes.** |
| 2 | Does Home stay visible for narrowed section roles? | **Yes — Home is not role-based at all**, visible to everyone unconditionally, same tier as Account. |
| 3 | Is `CUSTOMER` a Section Role too, or actor-agnostic? | **Deferred.** `CUSTOMER` gets its own UI, not designed yet — out of scope for the first implementation pass. Only its Account-tab/no-tenant-switcher rule (§3.3/§3.4) applies for now. |
| 4 | Does `_PARTNERSHIP_MANAGER` get both Partner sub-tabs? | **Yes, both** — "Tenant" and "Tenant Onboarding". |

## 6. Where the role/permission data already lives

This app's internal access token (`InternalTokenService.mintTokenPair`,
`com.ecofood.app.auth.service`) **already carries `roles`, `permissions`,
and `roleGroups` as JWT claims**, resolved per-tenant via
`UserAccessService`/`CoreUserAccessClient` (this project's own
UserRole/RolePermission/GroupRole ACL module) — the exact same shape as the
DCMP pattern researched earlier (role/permission codes baked into the JWT
at login, not fetched live).

**So yes — the frontend can and should extract the role client-side by
decoding that token**, via the `decodeJwt()` helper that already exists in
`EcoFood-FE/src/features/auth/lib/jwt.js`. One correction to keep in mind:
the internal **access** token is never written to any browser storage
(session or local) — it only ever lives in the in-memory Zustand
`authStore` state (`internalAccessToken`). Only the **refresh** tokens
(`internalRefreshToken`/`kcRefreshToken`) are persisted, and to
`localStorage`, not `sessionStorage` (see `authStore.js`'s `partialize`).
Decoding happens straight off the in-memory access token the store already
holds — no storage read needed either way.

**Gap to close before this is usable**: `UserRoleAssignmentServiceImpl`
(`com.ecofood.app.userACL.service`) currently only ever assigns one of two
hardcoded role codes at onboarding time — `ENTERPRISE_ADMIN` or
`TENANT_ADMIN` — predating this 16-role design. Implementing this model
means: (a) seeding/creating `Role` documents for all 15 actor×section
codes, (b) teaching `resolveRoleCode`-equivalent logic the new Actor
derivation (`ECO` / `ENTERPRISE` / `MASTER` / `TENANT`, per §1.1) instead of
just `partnerTypeCode`, and (c) deciding how a tenant's *other* users (not
just the onboarding admin) get assigned one of the 4 section roles.
