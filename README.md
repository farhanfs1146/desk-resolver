# desk-resolver

Internal IT support ticketing API. Employees raise tickets against an application/module from a
catalogue; IT support triages, assigns and moves them through status; every change is appended to an
audit trail.

Spring Boot 4.1 · Java 25 · PostgreSQL · Flyway · JWT bearer authentication.

## Running it

Requires a PostgreSQL instance and a JDK 25 toolchain. Everything below is configurable through the
environment; the defaults in `src/main/resources/application.yaml` target local development.

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/ITSoftwareSupport` | |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | |
| `APP_JWT_SECRET` | *(none)* | HS256 signing key, **minimum 32 bytes**. If unset, a random key is generated per JVM: tokens then die on restart and do not validate across instances. Set it in any shared environment. |
| `APP_CORS_ORIGINS` | `http://localhost:4200` | Exact origins, never a wildcard. |
| `APP_SWAGGER_PUBLIC` | `true` | Set `false` in production. |
| `APP_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | *(none)* | Both required to create the first ADMIN. See below. |
| `APP_RATE_LIMIT_ENABLED` | `true` | Login throttling. |
| `APP_TRUST_FORWARDED_HEADERS` | `false` | Enable **only** behind a proxy that appends `X-Forwarded-For`. |
| `APP_SESSION_RETENTION` | `P7D` | How long an expired session row is kept before the purge deletes it. |
| `APP_SESSION_PURGE_INTERVAL` | `PT1H` | How often that purge runs. |

```bash
./mvnw spring-boot:run
```

Serves on port **9092**. The schema is managed entirely by Flyway
(`src/main/resources/db/migration`); Hibernate runs with `ddl-auto: validate` and never alters it.

It spans **two schemas**. Ticketing lives in `desk_resolver_db`; identity and authorization live in
`auth` — `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `sessions`. The
application connects with `search_path = desk_resolver_db`, so every reference to an `auth` table is
schema-qualified explicitly, in the migrations and in `@Table(schema = "auth")`. Nothing resolves by
search-path order. `V17` created the schema and moved `users` into it; the four foreign keys pointing
at `users` from the ticketing tables were not recreated, because PostgreSQL moves a table's
constraints, indexes and owned sequence along with it.

### First run

Creating a user requires the `USER_MANAGE` permission, which requires an account — so a fresh
database needs one administrator bootstrapped from configuration:

```bash
APP_BOOTSTRAP_ADMIN_EMAIL=admin@example.com \
APP_BOOTSTRAP_ADMIN_PASSWORD='<at least 12 characters>' \
./mvnw spring-boot:run
```

This runs **only** when no user holds the `ADMIN` role, so it cannot be used to reset an existing
administrator's password. Change the password through `PATCH /api/users/me/password` afterwards and
unset the variables.

The `ADMIN` role itself is looked up in `auth.roles` rather than assumed: if `V17`'s seed is missing,
the bootstrap fails loudly instead of creating an administrator who holds nothing.

## Authentication

`POST /api/auth/login` with `{"email":..., "password":...}` returns a signed bearer token. Send it as
`Authorization: Bearer <token>` on every other endpoint — it is the only endpoint reachable without
one. `POST /api/auth/logout` ends the session the token belongs to.

### Roles and permissions

A user holds **any number of roles**, and their permissions are the **union** of what those roles
grant — no intersection, no deny. Adding a role can only widen access, so an `EMPLOYEE` who is also
granted `IT_SUPPORT` holds `TICKET_READ_ALL` and sees every ticket.

Endpoints assert *permissions* (`TICKET_ASSIGN`), never roles. Authority is resolved from `auth`
on every request rather than read from the token, so granting or revoking a role takes effect on the
caller's next request — no re-login, and no waiting for outstanding tokens to expire.

The division between code and data is deliberate and worth knowing:

| | Owner | Why |
| --- | --- | --- |
| Which permissions **exist** | `Permission` enum, seeded into `auth.permissions` by `V17` | A permission is enforced by a string literal inside `@PreAuthorize`. As editable data, renaming or deleting a row would disable that check with nothing failing anywhere. |
| Which role grants **which permission** | `auth.role_permissions` | What an administrator changes. No redeploy. |
| Which user holds **which role** | `auth.user_roles` | Likewise, through `PUT /api/users/{id}/roles`. |

`PermissionCatalogueValidator` refuses to start the application if the enum and the table disagree in
either direction, so the seed cannot quietly rot.

`GET /api/roles` is the authoritative role→permission table; the seed that produced it is in `V17`
and `RolePermissionSeedIT` asserts it. The seven roles ship unchanged from the enum they replaced:
requesters (`EMPLOYEE`, `MANAGER`, `HOD`, `DIRECTOR`) create and read their own tickets and read the
catalogue; support staff (`IT_SUPPORT`, `DEVELOPER`) add read-all, status change, assign and user
read; `ADMIN` adds user and application management.

A role can be **deactivated** (`auth.roles.active`) instead of deleted, which withdraws its
permissions from everyone holding it on their next request while keeping the record of who held it.

A user with **no roles** authenticates and can change their own password. Nothing else.

### Sessions

Every login writes a row in `auth.sessions`, and the token carries its id as a `sid` claim. One
indexed query per request resolves that session *and* the caller's permissions — the same query, so
revocation costs no extra round trip. A token is refused immediately when its session has been
revoked or expired, or when the account has been deactivated.

That makes three things real that the previous stateless-only model could not do:

- **Logout** actually ends the session, rather than asking the client to forget the token.
- **A password change revokes every session the user holds**, the current one included.
- **Deactivating an account** ends its live sessions instead of leaving them usable until expiry.

The filter chain is still `STATELESS`: no cookie, no `HttpSession`. Signature, expiry and issuer are
checked first, exactly as before; the session is a second gate.

Sessions are kept for `APP_SESSION_RETENTION` after expiry and then purged on a schedule, so there is
something to look at when asking which sessions existed around an incident.

There are **no refresh tokens**. A token lasts 30 minutes by default and re-authentication is a fresh
login; see [`docs/DECISIONS.md`](docs/DECISIONS.md).

## API

All errors are RFC 7807 problem documents (`application/problem+json`), including the 401 and 403
produced inside the security filter chain.

| Method | Path | Permission |
| --- | --- | --- |
| `POST` | `/api/auth/login` | public |
| `POST` | `/api/auth/logout` | authenticated |
| `POST` | `/api/tickets` | `TICKET_CREATE` |
| `GET` | `/api/tickets` | `TICKET_READ_OWN` |
| `GET` | `/api/tickets/{id}` | `TICKET_READ_OWN` |
| `GET` | `/api/tickets/{id}/history` | `TICKET_READ_OWN` |
| `PUT` | `/api/tickets/{ticketId}/assign/{userId}` | `TICKET_ASSIGN` |
| `PATCH` | `/api/tickets/{ticketId}/status` | `TICKET_STATUS_CHANGE` |
| `POST` | `/api/users` | `USER_MANAGE` |
| `PUT` | `/api/users/{id}/roles` | `USER_MANAGE` |
| `GET` | `/api/roles` | `USER_MANAGE` |
| `GET` | `/api/users` | `USER_READ` |
| `GET` | `/api/users/{id}` | authenticated (own record, or `USER_READ`) |
| `PATCH` | `/api/users/me/password` | authenticated |
| `POST` `PUT` `DELETE` | `/api/applications`, `/api/applications/{id}` | `APPLICATION_MANAGE` |
| `GET` | `/api/applications`, `/api/applications/active`, `/api/applications/{id}` | `APPLICATION_READ` |

`DELETE /api/applications/{id}` is a soft deactivation; nothing is ever deleted.

`POST /api/users` takes `"roles": ["IT_SUPPORT", "DEVELOPER"]` — required and non-empty, because an
account created by omission is an account nobody decided on. `PUT /api/users/{id}/roles` replaces the
whole set and is idempotent; an empty array strips every role. An unknown role code is a 400 naming
every one it did not recognise, never a silent omission.

Removing `USER_MANAGE` from the last active account holding it is a **409**. Nothing in the
application could undo that change, so it is refused rather than discovered later.

`PATCH /api/tickets/{id}/status` takes a JSON body, which can also carry a note for the audit trail:

```json
{ "status": "RESOLVED", "remarks": "Fixed in build 412" }
```

The older `?status=RESOLVED` query parameter still works and still means the same thing; when both are
supplied the body wins. Moving a ticket to the status it already has is a no-op: it answers 200 and
records nothing.

A ticket's `moduleName` must be the module of the application it is raised against - `applications` is a
catalogue of application-and-module pairs, so `applicationId` already determines the module. A mismatch
is a 400. The comparison ignores case and padding, and the catalogue's own spelling is what gets stored.

Reading a ticket you are neither the raiser nor the assignee of answers **404**, not 403 — a 403 would
confirm the ticket exists and let an unauthorised caller map valid ids by probing. The same applies to
reading another user's record.

### Pagination

Every collection endpoint is paged: `?page=` (zero-based), `?size=` (default 20, capped at 100),
`?sort=property` or `?sort=property,asc|desc`. Sort properties are whitelisted per endpoint and an
unknown property or direction is a 400. The body stays a plain JSON array; metadata travels in
`X-Total-Count`, `X-Total-Pages`, `X-Page-Number`, `X-Page-Size` and `X-Has-Next` (all exposed via
CORS).

`?sort=priority` orders by severity (`LOW < MEDIUM < HIGH < CRITICAL`), not alphabetically, so
`priority,desc` is worst-first. `status` is *not* lifecycle-ordered — no lifecycle is defined.

`?sort=role` on `/api/users` was removed in Phase 7 and not replaced: a user can hold several roles,
so there is no column to order by, and "sort by role" would have to be given a meaning first. It is a
400, like any other unknown sort property.

## Swagger

`http://localhost:9092/swagger-ui.html` — public by default for development; gate it with
`APP_SWAGGER_PUBLIC=false` in production.

To use a protected endpoint from the page: run `POST /api/auth/login`, copy the `accessToken` from the
response, click **Authorize** at the top and paste it — the token value alone, without a `Bearer `
prefix, which Swagger UI adds itself. Every operation except login carries a padlock and will then
send the token.

The Authorize button exists because `OpenApiConfig` declares the bearer scheme; springdoc does not
infer one from the filter chain, and without the declaration the page could only ever reach the login
endpoint.

## Tests

```bash
./mvnw test      # 47 unit tests, no infrastructure needed, ~2s
./mvnw verify    # the above plus 151 integration tests in a PostgreSQL container
```

Integration tests are named `*IT` and run under failsafe, so **`mvn test` does not run them** - use
`mvn verify` before pushing.

They need Docker. Each run starts one throwaway `postgres:18-alpine` container, applies all 18
migrations to it and lets Hibernate validate the mappings against the result, so the migrations and the
entity mappings are themselves under test. **No test touches a local database.**

Since `V17` that includes the whole authorization model — the role→permission seed, the cross-schema
foreign keys, the `ALTER TABLE ... SET SCHEMA` move and the per-request authority query are all SQL,
so all of them are exercised against the real schema rather than a stub.

| Suite | Covers |
| --- | --- |
| `PageRequestsTest` | page/size bounds, sort whitelisting and direction validation, the `id` tiebreaker |
| `RolePermissionSeedIT` | the seeded role-to-permission table, that no role is accidentally privileged, and that `auth.permissions` matches the `Permission` enum. Replaces the former `RolePermissionsTest`: the mapping is seed data now, so verifying it needs a database |
| `InMemoryLoginAttemptLimiterTest` | both throttle dimensions, window and block expiry, key bounding - driven by an injected `Clock`, so no sleeping |
| `ClientIpResolverTest` | that `X-Forwarded-For` is ignored by default and read right-most when trusted |
| `SecurityProblemWriterTest` | the hand-built problem document, including control-character escaping |
| `TicketNumberGeneratorTest` | the number format, and that it widens rather than truncating |
| `ResourceAccessControlIT` | the IDOR/BOLA boundaries: 404 rather than 403, list scoping, per-role endpoint permissions |
| `TicketLifecycleIT` | create/assign/status, `resolvedAt` rules, audit trail, severity sorting, optimistic locking |
| `AuthenticationIT` | uniform login failure, throttling, password change, duplicate detection |
| `ApiErrorHandlingIT` | every status mapping, and that no response leaks internals |
| `TicketStatusAndModuleIT` | the module-belongs-to-application rule, the status body and `remarks`, the no-op status change |
| `ApplicationCatalogueIT` | catalogue uniqueness and NOT NULL (V15), and that the dead `ticket_history` table is gone (V16) |
| `OpenApiDocumentIT` | that the document declares the bearer scheme Swagger UI needs for its Authorize button, that the requirement is global, and that login opts out while logout does not |
| `BootstrapAdminIT` | the first-administrator flow: that the created account can log in and actually manage users, and that the three guards hold - already-an-admin, unconfigured, email taken |
| `SessionLifecycleIT` | revocation: logout, revoke-all-on-password-change, deactivation ending live sessions, an expired session, a validly signed token with no `sid`, a subject/session mismatch, and the purge |
| `UserRoleAdministrationIT` | several roles per user and the union rule, creating and replacing role sets, unknown role codes, the roleless account, role deactivation, the `USER_MANAGE` lockout guard, and that a grant records its grantor |

If Testcontainers cannot find Docker on Docker Desktop for Windows, point it at the active endpoint:
`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.

## Decisions and open questions

[`docs/DECISIONS.md`](docs/DECISIONS.md) records the open business questions (status transitions,
department-scoped visibility), the limitations accepted deliberately (no token revocation, per-instance
throttling, deep-page cost), and the security headers that were reviewed and rejected.

## Known gaps

- **No refresh tokens.** A token lasts 30 minutes and then the client logs in again. Sessions made
  revocation possible but do not extend anything; rotating refresh tokens would be the next step and
  `auth.sessions` is where they would live.
- **Login throttling is per-instance** (in-memory), so it weakens if the app is scaled out.
- **No `user_permissions` table.** A permission can only be granted through a role. Granting one
  straight to a user is a legitimate pattern and deliberately not built; see
  [`docs/DECISIONS.md`](docs/DECISIONS.md).
- **Roles can only be created, renamed or deactivated in SQL.** The schema supports new roles without
  a redeploy, which was the point, but no endpoint defines them — there is no requirement yet saying
  what an eighth role would mean.
- **An account cannot be deactivated through the API.** `active` is set at creation and respected
  everywhere (login, and every request since `V17`), but there is no endpoint to flip it, and no
  endpoint to reset another user's password.
- **Status transitions are not validated.** Any status may follow any other; `UNDER_REVIEW`,
  `PENDING` and `REOPENED` are defined but unreachable through the normal flow. No agreed workflow
  exists. `TicketLifecycleIT.transitionsAreNotValidated` pins the current behaviour, so it is the test
  that should start failing once a workflow is decided.
- **Comments and attachments are not implemented.** `ticket_comments` and `ticket_attachments` exist
  in the schema (V4, V5) but have no entity, repository or endpoint. Kept deliberately; see
  [`docs/DECISIONS.md`](docs/DECISIONS.md).
- **`users.department_id` / `designation_id`** reference tables that do not exist, which is why
  `MANAGER`, `HOD` and `DIRECTOR` have no department-scoped visibility.
- **Offset pagination is O(offset)** on deep pages; keyset pagination would be a contract change.
