# Proposition, Plan and Market Configuration

## Review flow

Both entities have exactly three statuses:

`DRAFT → PENDING_REVIEW → APPROVED`

Proposition and Plan have independent review actions and approval metadata. Approving a Plan never approves its Proposition; approving a Proposition never approves its Plans.

An approved Plan is operational only when:

- Its Proposition is also approved.
- Both the Proposition and Plan are within their effective periods.
- It has not been superseded by an approved successor whose effective period has started.

The API exposes the derived `active` boolean separately from review status. An approved Plan waiting for its Proposition remains `APPROVED` with `active=false`. Future or expired effective dates do not introduce new statuses. There are no Go Live, Schedule, Deactivate or expiration transitions or scheduler.

## Actions and permissions

All mutation bodies use `{"expectedRevision":N}`. The owning Enterprise Admin/Product Manager manages review. Related Sub-Tenants and ECO have scoped read access.

| Action | Endpoint suffix under `/api/v1/propositions/{id}` |
| --- | --- |
| Submit Proposition | POST `/submit` |
| Approve Proposition | POST `/approve` |
| Submit Plan | POST `/plans/{planId}/submit` |
| Approve Plan | POST `/plans/{planId}/approve` |
| Create successor Draft | POST `/plans/{planId}/versions` |

- Only Draft entities may be edited; only Draft Plans may be removed.
- Only Draft entities may be submitted, and only Pending Review entities may be approved.
- Proposition submission requires business targets, valid dates/source, distribution countries and profit allocation totalling 100%.
- Plan submission requires its own valid tenant/country configuration and market pricing. It can be submitted and approved while its Proposition is still Draft or Pending Review; incomplete Proposition targets do not block independent Plan review.
- Proposition approval rechecks already approved Plans before enabling them. Incomplete Draft Plans do not block Proposition approval.
- Root optimistic revision and atomic audit protect every review transition. Audit records include the entity's own review status, actor/time and Plan version.

## Tenant Directory and markets

The form follows every page of the existing `GET /api/v1/tenants/directory`. Distribution partners must be `SUB_TENANT` with type `BRAND` or `ECOSYSTEM_PARTNER`. Backend validates the same Enterprise Directory scope, using existing master/sub-tenant relationships.

Directory exposes `country` from the preferred onboarding contact address's `isoCountryCode`, normalized to ISO alpha-2. Missing country must be filled in through the existing Tenant contact editor. No repeat onboarding is needed.

Proposition `regionIds` stores the countries derived by the backend from selected partners. Each Plan selects one country and only selected distribution Sub-Tenants belonging to that country. Optional Brand association is retained.

Allowed segments/scopes and Plan segment/scope have been removed from UI, DTOs, stored model and validation. They no longer partition the conflict check. Plans conflict when country, tenant and effective period overlap; periods are half-open `[start,end)`. Direct predecessor/successor versions can overlap while the replacement is reviewed.

## Pricing and Product Process

Market Configuration stores sale price, optional currency, tax percentage and additional fees per Plan. UI preview must be confirmed; backend recalculates exact decimal amounts. Pricing can be copied from another Plan as a template. Draft pricing can be omitted, but submission for review requires it.

Only enabled name/description/image/recipe overrides may be customized. Recipe steps are Plan-specific and never alter the approved source ProductProcess. Business targets include target year, expected revenue, nullable current revenue and profit shares totalling 100% when supplied.

## Existing records and local build

No business records are rewritten by this source change. A Proposition without its own saved status is treated as Draft, even if it already has approved Plans. It needs its own Submit/Approve actions.

Mongo reading compatibility maps legacy Plan `LIVE` to `APPROVED`, and `DEACTIVATED`/`EXPIRED` to `DRAFT` for explicit review. Removed fields on old documents are ignored. Current API responses expose only the three review statuses. Existing overlapping Plans previously distinguished only by segments/scopes must be resolved before approving their Proposition.

Rebuild/restart backend and frontend together. The local reactor is `.product-build/pom.xml`; Core must be installed or published consistently with the backend DTO usage. Existing MongoDB settings remain in use.

## Checks

- Java tests cover both approval orders, invalid transitions, scope/revision checks, audit atomicity, legacy Mongo mapping, effective dates and successor versions.
- Frontend: `node --test tests/proposition-flow.test.mjs`, production Vite build and lint of changed files.
- `PropositionMongoIT` is opt-in through `PROPOSITION_TEST_MONGO_URI` and uses a newly generated isolated database.
