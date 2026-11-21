# SCR-33 — Proposition, Plan and Market Configuration

> The current UC-013/UC-011/UC-012 flow is documented in
> [PROPOSITION_PLAN_FLOW.md](PROPOSITION_PLAN_FLOW.md). It supersedes the
> Distributor-only filtering, manual regions, tenant-managed Plans and
> Draft/Approved-only lifecycle described in the original implementation below.

## Scope and source analysis

Repositories: Ecofood `SCR-33`, Eco_Core `scr-33`, EcoFood-FE `SCR-33`.
The complete EcoFood_Proposition_Plan_Agent_Spec.md and ACL_ROLE_MODEL.md were read.
The diagram was checked; the user's override is authoritative: each Proposition
references exactly one APPROVED ProductProcess rather than a Product/Bundle.
Existing ProductProcess query/history/approval, Core Client delegation, internal
JWT filter, ACL role groups, BaseEntity auditing and Mongo configuration were reused.

Missing before this change: Proposition contracts/model/API/UI, Plan handling,
market calculation and Enterprise currency settings. No Region/Segment catalog
was found. Enterprise declares region/segment/scope codes on the Proposition;
a Plan is restricted to them. The diagram's monthly economic indicator fields
are represented as month/year, sales volume, profit, ingredient/platform costs.

Implementation followed contracts -> model -> services/market -> controller -> FE
-> regression/integration. Intermediate checks are under `.product-build/scr33-*.log`.

## Confirmed behavior

- Enterprise ADMIN/PRODUCT_MANAGER with ENTERPRISE group creates/edits Propositions.
- Source identity is checked through CoreProductQueryClient.getApprovedVersion and
  processes: product ID, version and process ID must match the same approved
  Enterprise-owned process. Historical Approved sources remain available when
  the latest Product version is Draft. Product code was not modified.
- Proposition source fields and saved process snapshot are immutable after create.
  A Proposition may initially contain no Plans.
- Existing tenant relationships are only read, never written or redefined.
  MT remains on the sub-tenant and points to its master, exactly as existing
  TenantRepository.findScopedTenantIds/onboarding use it. Existing explicit
  Tenant BL/OW links are read in their existing direction; no names, realms,
  domain URLs or Enterprise-collection IDs are treated as tenant associations.
  Brand uses BRAND; distributor/eligible Plan tenant uses ECOSYSTEM_PARTNER.
- Plan management/approval is allowed for the owning Enterprise or an authorized
  MASTER/TENANT ADMIN/PRODUCT_MANAGER whose existing tenant scope contains every
  tenant assigned to the Plan. Related actors see only Plans in their scope;
  unrelated IDs are hidden. ECO has read access, no Enterprise creation rights.
- Plans are embedded in the Proposition aggregate; no PlanRepository or service
  outside the modular monolith was introduced. Root @Version protects all Plan
  mutations. Request expectedRevision and database CAS both reject stale writes.
- Draft -> Approved only. Approval requires market pricing and valid scope.
  Approved Plans cannot be edited/deleted. No Live, activation scheduler or
  automatic expiration was added. Plan version starts at 1; independent Plan
  version-fork APIs are deferred.
- Overlap: same tenant + region, intersecting segment/scope (blank means all),
  and intersecting effective period is rejected, including Drafts. Periods are
  half-open [start,end); touching boundaries are valid. Dates are stored as UTC
  instants; UI uses local datetime input. Plan dates stay within the Proposition.
- Only explicitly enabled Product override fields (name/description/image URL)
  can be customized. No mutation of the approved source is performed.
- Market BigDecimal calculation: rounded tax = salePrice * percent / 100,
  total = salePrice + rounded tax + surcharge; HALF_UP at ISO currency minor units.
  8 means 8%, matching existing Product percentage units. Monetary response fields
  are decimal strings; the FE preview uses BigInt. No FX conversion is performed.
- Currency policy is isolated to this commercial module and keyed by Enterprise.
  Initial policy is USD only/default USD; Enterprise can enable valid ISO codes.
  Existing approved prices are preserved if a currency is later disabled.
  Product's existing VND pricing and Enterprise onboarding are unchanged.
- Expected/partner monthly indicators and profit shares are optional in Draft,
  non-negative, unique by period; shares are individually 0..100. No unstated
  rule forces shares to total 100. Actual indicators are read-only.
- Atomic audit: changes (actor/time/action and changed definition/Plan request)
  are embedded and saved together with the Proposition by one CAS write. A
  failed CAS publishes neither data nor its audit entry. No cross-document
  transaction is claimed. Currency settings use their own revision and audit
  metadata inherited from BaseEntity.

## Minimal edits outside the new module

| File | Reason |
| --- | --- |
| Eco_Core/pom.xml | New explicit Core contract version 1.0.13 |
| Ecofood/pom.xml | Match Core 1.0.13 |
| Eco_Core/.../service/TenantQueryService.java | Add narrow read-only commercial reference queries |
| Eco_Core/.../client/CoreTenantQueryClient.java | Delegate those queries through the Core Client boundary |
| Ecofood/.../tenant/service/TenantQueryServiceImpl.java | Map existing tenant fields/relations/scope into the new read DTO; no writes |
| EcoFood-FE/src/app/AppRouter/AppRouter.jsx | Replace only the Proposition placeholder route |
| EcoFood-FE/.gitignore | Exclude temporary integration-test config |

Auth, Tenant relationship creation/update, Product production code, other FE
pages, FEcore, application.properties and .env were not changed. No deployment,
branch switch, commit or push was performed.

## API and persistence

Base: /api/v1/propositions.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | / | Scoped paginated list/search |
| GET | /options | Existing eligible parties and creation capability |
| GET | /approved-processes | Approved source versions |
| GET/PUT | /settings | Enterprise commercial currency policy |
| GET/POST/PUT | /{id} (POST at base) | Detail/create/update |
| POST | /{id}/plans | Add Draft Plan |
| GET/PUT/DELETE | /{id}/plans/{planId} | Read/update/remove Draft |
| POST | /{id}/plans/{planId}/approve | Approve valid Plan |

Create takes productId, productVersion, productProcessId and definition.
Update takes expectedRevision and definition. Definition groups Proposition
commercial fields in the DTO/document; it is not a separate collection.
The server derives Enterprise/owner/source from the authenticated context.
Plans and changes are embedded in collection `proposition`;
`proposition_currency_settings` holds Enterprise currency policy.
Indexes: Enterprise, ProductProcess, Brand, distributors, Plan tenants; unique
Enterprise currency-settings index. Indexes are created idempotently at startup.
No existing collections/documents are migrated. Documents must fit MongoDB's
document size limit; large audit histories or catalogs will eventually require
archiving/database-side pagination. Current scoped-list filtering follows the
existing Product service convention with pagination in memory.

Import `EcoFood_Proposition_Plan.postman_collection.json` for executable examples.
Default page size is Spring's default; maximum accepted size is 100.
Invalid input returns 400, missing auth 401, forbidden role/scope 403, hidden ID
404, stale revision/lifecycle conflict 409, using existing error handling.

## Build and verification

- EcoCore 1.0.13 clean build/install: 36 unit/contract tests passed.
- Ecofood clean build/install: 273 unit/controller tests passed, including
  Product/Auth/Tenant/UserACL regression. 0 failures/errors/skips.
- FE helper tests: 18 passed (13 Product + 5 Proposition).
- FE production build and lint passed; existing warnings in other pages remain.
- Real Spring HTTP + Mongo integration passed, including unique-index startup,
  Product create/submit/approve, Proposition creation, Plan price/approval,
  persisted audit and a real optimistic-lock conflict.
- Real FE -> API -> Mongo desktop flow passed: create/list/detail/edit Proposition,
  create/edit/approve Plan, and reload persisted results. Uses signed test-only
  internal JWTs verified by the real filter; Keycloak interactive login is not
  part of this integration fixture.
- Integration creates an isolated `ecofood_proposition_it_*` database using the
  configured Mongo endpoint and drops that database after the test. It never
  writes to the normal configured database. Docker is not used.
- Standard regression uses -Dtest=*Test; infrastructure AppApplicationTests is
  excluded. PropositionMongoIT explicitly starts the complete application against
  real Mongo. Mobile result and final log paths are appended below.

```powershell
# Reactor builds the matching Core and application together:
mvn.cmd -o -f D:\TLCN\.product-build\pom.xml '-Dmaven.repo.local=C:/Users/PC/.m2/repository' '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' clean install
cd D:\TLCN\EcoFood-FE
node --test src/features/product/lib/product.test.js src/features/proposition/lib/proposition.test.js
npm.cmd run build
npm.cmd run lint

# Optional integration: supply PROPOSITION_TEST_MONGO_URI in the environment;
# run Vite on localhost:5173. Never commit tokens or URI credentials.
# Run from D:\TLCN:
mvn.cmd -o -f .product-build/pom.xml '-Dmaven.repo.local=C:/Users/PC/.m2/repository' '-Dtest=PropositionMongoIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dproposition.browser=true' test
# Add -Dproposition.browserWidth=390 for the mobile browser.
```

Build Core 1.0.13/install or publish through the existing repository process before
building Ecofood independently. Core was installed locally only. Restart the
backend after updating it; restart Vite when changing environment values.

Mobile 390px real-backend/Mongo test: PASS. Database dropped and temporary JWT config removed after test.
Logs: .product-build/scr33-final-build.log, scr33-mongo-it.log, scr33-mongo-mobile-it.log.

## Created/modified file inventory

### Ecofood Proposition source

- Ecofood/src/main/java/com/ecofood/app/proposition/controller/PropositionController.java
- Ecofood/src/main/java/com/ecofood/app/proposition/entity/Plan.java
- Ecofood/src/main/java/com/ecofood/app/proposition/entity/PropositionChange.java
- Ecofood/src/main/java/com/ecofood/app/proposition/entity/PropositionCurrencySettings.java
- Ecofood/src/main/java/com/ecofood/app/proposition/entity/PropositionEntity.java
- Ecofood/src/main/java/com/ecofood/app/proposition/repository/PropositionCurrencySettingsRepository.java
- Ecofood/src/main/java/com/ecofood/app/proposition/repository/PropositionRepository.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/MarketConfigurationCalculator.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionAccess.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionCurrencies.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionIndexes.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionMapper.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionParties.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionQueryServiceImpl.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionServiceImpl.java
- Ecofood/src/main/java/com/ecofood/app/proposition/service/PropositionValidation.java

### Ecofood tests

- Ecofood/src/test/java/com/ecofood/app/proposition/MarketConfigurationCalculatorTest.java
- Ecofood/src/test/java/com/ecofood/app/proposition/PropositionApiTest.java
- Ecofood/src/test/java/com/ecofood/app/proposition/PropositionMongoIT.java
- Ecofood/src/test/java/com/ecofood/app/proposition/PropositionValidationTest.java

### EcoCore contracts and delegates

- Eco_Core/src/main/java/com/ecofood/core/client/CorePropositionClient.java
- Eco_Core/src/main/java/com/ecofood/core/client/CorePropositionQueryClient.java
- Eco_Core/src/main/java/com/ecofood/core/service/PropositionQueryService.java
- Eco_Core/src/main/java/com/ecofood/core/service/PropositionService.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionCreateRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionCurrencySettingsRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionCurrencySettingsResponse.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionDefinition.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionOptionsResponse.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionResponse.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionTenantReference.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PropositionUpdateRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PlanProductOverrides.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PlanRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PlanResponse.java
- Eco_Core/src/main/java/com/ecofood/core/dto/MarketConfigurationRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/MarketConfigurationResponse.java
- Eco_Core/src/main/java/com/ecofood/core/dto/ApprovedProductProcessReference.java
- Eco_Core/src/main/java/com/ecofood/core/dto/EconomicMetricsRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/PartnerMetricsRequest.java
- Eco_Core/src/main/java/com/ecofood/core/dto/RevisionRequest.java
- Eco_Core/src/main/java/com/ecofood/core/enums/PlanOverrideField.java
- Eco_Core/src/main/java/com/ecofood/core/enums/PlanStatusEnum.java
- Eco_Core/src/test/java/com/ecofood/core/client/PropositionContractsTest.java

### Frontend

- EcoFood-FE/src/features/proposition/api/proposition.api.js
- EcoFood-FE/src/features/proposition/lib/proposition.js
- EcoFood-FE/src/features/proposition/lib/proposition.test.js
- EcoFood-FE/src/features/proposition/pages/CurrencySettingsForm.jsx
- EcoFood-FE/src/features/proposition/pages/PlanForm.jsx
- EcoFood-FE/src/features/proposition/pages/PropositionDetail.jsx
- EcoFood-FE/src/features/proposition/pages/PropositionFields.jsx
- EcoFood-FE/src/features/proposition/pages/PropositionForm.jsx
- EcoFood-FE/src/features/proposition/pages/PropositionPage.css
- EcoFood-FE/src/features/proposition/pages/PropositionPage.jsx
- EcoFood-FE/tests/proposition-live.html
- EcoFood-FE/tests/proposition-live.jsx

### Modified integration files and docs

- Ecofood/pom.xml
- Eco_Core/pom.xml
- Eco_Core/src/main/java/com/ecofood/core/client/CoreTenantQueryClient.java
- Eco_Core/src/main/java/com/ecofood/core/service/TenantQueryService.java
- Ecofood/src/main/java/com/ecofood/app/tenant/service/TenantQueryServiceImpl.java
- EcoFood-FE/src/app/AppRouter/AppRouter.jsx
- EcoFood-FE/.gitignore
- EcoFood-FE/.idea/.gitignore
- Ecofood/docs/EcoFood_Proposition_Plan.postman_collection.json
- Ecofood/docs/SCR33_PROPOSITION_IMPLEMENTATION.md

- EcoFood-FE/tests/run-proposition-browser.mjs (Chrome CDP runner with real viewport emulation)

Mobile verification uses Emulation.setDeviceMetricsOverride; it asserts viewport=390, no horizontal document overflow, and the complete real backend flow. This avoids Chrome headless's minimum native window width. Result: PASS at 390x844.
