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
| `APP_JWT_SECRET` | *(none)* | **Required.** HS256 signing key, minimum 32 bytes. With no secret the application **refuses to start** — see below. |
| `APP_JWT_ALLOW_GENERATED_SECRET` | `false` | Developer-machine escape hatch: generate a random key per JVM instead of failing. Every token dies on restart and none validates on another instance. Never enable anywhere shared. |
| `APP_REFRESH_TTL` | `8h` | How long a login may be renewed without a password. **Absolute** — refreshing never extends it. One working shift; see below. |
| `APP_CORS_ORIGINS` | `http://localhost:4200` | Exact origins, never a wildcard. |
| `APP_SWAGGER_PUBLIC` | `false` | Set `true` for local development or a demo. The API description is a map of the attack surface, so exposure is opt-in. |
| `APP_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | *(none)* | Both required to create the first ADMIN. See below. |
| `APP_RATE_LIMIT_ENABLED` | `true` | Login throttling. |
| `APP_TRUST_FORWARDED_HEADERS` | `false` | Enable **only** behind a proxy that appends `X-Forwarded-For`. |
| `APP_SESSION_RETENTION` | `P7D` | How long an expired session row is kept before the purge deletes it. |
| `APP_SESSION_PURGE_INTERVAL` | `PT1H` | How often that purge runs. |
| `APP_ATTACHMENT_DIR` | `./data/attachments` | Where uploaded files are written. The default is relative to the working directory — point it at durable, shared storage in any real deployment. |
| `APP_ATTACHMENT_MAX_FILE_SIZE` | `10MB` | Per-file limit enforced by the application, which answers 400 with the limit in the message. |
| `APP_MULTIPART_MAX_FILE_SIZE` | `12MB` | Container-level backstop (answers 413). Keep it above the limit above, so the readable error is the one clients normally meet. |

A minimum run therefore needs a signing key:

```bash
APP_JWT_SECRET='<at least 32 bytes>' ./mvnw spring-boot:run
```

Or, on a single developer machine where losing every token on restart is fine:

```bash
APP_JWT_ALLOW_GENERATED_SECRET=true ./mvnw spring-boot:run
```

**Why a missing secret is a startup failure rather than a warning.** It used to generate a random key
and log about it. The application would start, logins would succeed, and then tokens would stop working
after a restart and never validate on a second instance — an intermittent 401 with its explanation
hours up the log. Refusing to start reports the mistake where it was made.

Serves on port **9211**. The schema is managed entirely by Flyway
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

`POST /api/auth/login` with `{"email":..., "password":...}` returns **two** credentials:

| Field | What it is | Lifetime |
| --- | --- | --- |
| `accessToken` | Signed JWT. Send as `Authorization: Bearer <token>` on every other endpoint. | `30m` |
| `refreshToken` | Opaque single-use token. POST it to `/api/auth/refresh` to get a new pair, no password. | `8h`, absolute |

`/api/auth/login` and `/api/auth/refresh` are the only endpoints reachable without an access token, for
the same reason — both exist to produce one. Neither is *unauthenticated*: login demands a password and
refresh demands a 256-bit single-use token this application issued.

`POST /api/auth/logout` ends the **whole login** — the current session and every one rotated out of it,
so neither credential works on the next request.

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
and `V19`, and `RolePermissionSeedIT` asserts it. The seven roles ship unchanged from the enum they
replaced: requesters (`EMPLOYEE`, `MANAGER`, `HOD`, `DIRECTOR`) create and read their own tickets and
read the catalogue; support staff (`IT_SUPPORT`, `DEVELOPER`) add read-all, status change, assign and
user read; `ADMIN` adds user and application management.

**Every role** also holds the four participation permissions — `TICKET_COMMENT_READ`,
`TICKET_COMMENT_CREATE`, `TICKET_ATTACHMENT_READ`, `TICKET_ATTACHMENT_UPLOAD`. That is wider than the
ticket permissions on purpose: commenting and attaching are how the person who raised a ticket answers
the question that gets it resolved, so withholding them from requesters would leave the feature with
nobody to talk to. They do not widen *which* tickets anyone can reach — that is still decided per row
by involvement, before any comment or attachment endpoint does anything.

A role can be **deactivated** (`auth.roles.active`) instead of deleted, which withdraws its
permissions from everyone holding it on their next request while keeping the record of who held it.

A user with **no roles** authenticates and can change their own password. Nothing else.

### Sessions

Every login writes a row in `auth.sessions`, and the token carries its id as a `sid` claim. One
indexed query per request resolves that session *and* the caller's permissions — the same query, so
revocation costs no extra round trip. A token is refused immediately when its session has been
revoked or expired, or when the account has been deactivated.

That makes three things real that the previous stateless-only model could not do:

- **Logout** actually ends the login, rather than asking the client to forget the token.
- **A password change revokes every session the user holds**, the current one included.
- **Deactivating an account** ends its live sessions instead of leaving them usable until expiry.

Since `V21` a session also carries the refresh token that replaces it, chained by a `family_id`: one
login plus every token rotated out of it. A refresh **inserts** a session rather than updating one, so
the rows of a family are the history of a single login, and revoking the family is what logout and
reuse detection both do.

The filter chain is still `STATELESS`: no cookie, no `HttpSession`. Signature, expiry and issuer are
checked first, exactly as before; the session is a second gate.

Sessions are kept for `APP_SESSION_RETENTION` after expiry and then purged on a schedule, so there is
something to look at when asking which sessions existed around an incident.

### Refreshing a session

`POST /api/auth/refresh` with `{"refreshToken": "..."}` returns a fresh pair — the same body as login,
so a client builds the same state from either.

Three rules, and all three matter to whoever writes the client:

**The token rotates.** Every call returns a new refresh token and kills the one presented. Replace what
you stored; never keep both.

**Presenting a used token revokes the entire login.** A refresh token is usable exactly once, so a
second presentation means two parties hold it and one of them copied it. Nothing on the server can say
which, so every session descended from that login is revoked and both have to log in again. This is the
point of rotating at all — rotation on its own only shortens a window; what it buys is the signal.

**Refreshing cannot extend the window.** `refreshExpiresIn` counts down towards zero across the day,
because the cap is measured from the original login. After `APP_REFRESH_TTL` a password is required
again, whatever happened in between. Read that field to warn somebody *before* their session ends
rather than after.

The old access token is **not** killed by a refresh — it expires on its own `exp`, at most 30 minutes
later. That is deliberate: a browser with requests already in flight when it rotates should not collect
401s for them. What dies immediately is the credential that can mint more tokens.

> **The client must single-flight refreshes.** One refresh in flight at a time, with every other request
> queued behind it. Several requests meeting a 401 together is the normal case, not an edge case, and
> firing a refresh for each means all but one lose the race and get a 401 they will read as "log the
> user out". The server keeps the session alive through that — losing a race is not treated as reuse —
> so the only thing that breaks is a client that did not queue.

**Where to keep them:** access token in memory only, refresh token in `sessionStorage`. Not
`localStorage`, and deliberately not an `HttpOnly` cookie — the reasoning, including what that costs, is
in [`docs/DECISIONS.md`](docs/DECISIONS.md) under *Token storage in the browser*. The same section
assigns a strict `Content-Security-Policy` to whatever serves the SPA, which is the control that
actually reduces XSS risk.

## API

All errors are RFC 7807 problem documents (`application/problem+json`), including the 401 and 403
produced inside the security filter chain.

| Method | Path | Permission |
| --- | --- | --- |
| `POST` | `/api/auth/login` | public |
| `POST` | `/api/auth/refresh` | public (takes a refresh token) |
| `POST` | `/api/auth/logout` | authenticated |
| `POST` | `/api/tickets` | `TICKET_CREATE` |
| `GET` | `/api/tickets` | `TICKET_READ_OWN` |
| `GET` | `/api/tickets/{id}` | `TICKET_READ_OWN` |
| `GET` | `/api/tickets/{id}/history` | `TICKET_READ_OWN` |
| `PUT` | `/api/tickets/{ticketId}/assign/{userId}` | `TICKET_ASSIGN` |
| `PATCH` | `/api/tickets/{ticketId}/status` | `TICKET_STATUS_CHANGE` |
| `POST` | `/api/tickets/{ticketId}/comments` | `TICKET_COMMENT_CREATE` + involvement |
| `GET` | `/api/tickets/{ticketId}/comments` | `TICKET_COMMENT_READ` + involvement |
| `POST` | `/api/tickets/{ticketId}/attachments` | `TICKET_ATTACHMENT_UPLOAD` + involvement |
| `GET` | `/api/tickets/{ticketId}/attachments` | `TICKET_ATTACHMENT_READ` + involvement |
| `GET` | `/api/tickets/{ticketId}/attachments/{attachmentId}/content` | `TICKET_ATTACHMENT_READ` + involvement |
| `POST` | `/api/users` | `USER_MANAGE` |
| `PUT` | `/api/users/{id}/roles` | `USER_MANAGE` |
| `GET` | `/api/roles` | `USER_MANAGE` |
| `GET` | `/api/users` | `USER_READ` |
| `GET` | `/api/users/{id}` | authenticated (own record, or `USER_READ`) |
| `PATCH` | `/api/users/me/password` | authenticated |
| `GET` | `/api/notifications` | authenticated (own inbox) |
| `GET` | `/api/notifications/unread-count` | authenticated (own inbox) |
| `PATCH` | `/api/notifications/{id}/read` | authenticated (own inbox) |
| `POST` | `/api/notifications/read-all` | authenticated (own inbox) |
| `POST` `PUT` `DELETE` | `/api/applications`, `/api/applications/{id}` | `APPLICATION_MANAGE` |
| `GET` | `/api/applications`, `/api/applications/active`, `/api/applications/{id}` | `APPLICATION_READ` |

`DELETE /api/applications/{id}` is a soft deactivation; nothing is ever deleted.

The four notification endpoints assert **no permission**, and that is deliberate rather than an
oversight. The resource is defined as the caller's own: the recipient comes from the verified
token and appears in the `where` clause of every query, so the only caller who can reach a
notification is the one it was addressed to. A `NOTIFICATION_READ` permission would have to mean
something an administrator could grant, and the only thing it could mean is reading other
people's inboxes. See [Notifications](#notifications).

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

An **assignee must be able to progress the ticket**: the account has to be active *and* hold
`TICKET_STATUS_CHANGE` through an active role, or the assignment is a 400 naming the missing
permission. Assigning to somebody who cannot change status parks the ticket with a phantom owner — it
reads as `ASSIGNED`, drops out of every unassigned-work view, and the holder is refused the moment they
try to record anything. If you want to ask a requester for something, leave a comment.

Reading a ticket you are neither the raiser nor the assignee of answers **404**, not 403 — a 403 would
confirm the ticket exists and let an unauthorised caller map valid ids by probing. The same applies to
reading another user's record.

### Comments and attachments

Both are sub-resources of a ticket, and both are **append-only** — there is no edit and no delete. A
comment is a record of something somebody said; an attachment is a row plus a file, and the rule for
who may remove one is not established anywhere.

Every comment and attachment endpoint checks visibility of the ticket in the path **before anything
else**, so the permission governs whether you may use the endpoint and involvement governs whose
threads you reach. Reaching somebody else's is the same 404 as a ticket that does not exist.

A comment **changes nothing about the ticket**: no status change, no `updated_at`, no audit row. That is
what makes it safe to grant to requesters.

Uploads are `multipart/form-data` with one part named `file`:

```bash
curl -X POST http://localhost:9211/api/tickets/1/attachments \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@payslip-march.png'
```

- Refused with **400** if the file is empty, over `APP_ATTACHMENT_MAX_FILE_SIZE`, or of a content type
  outside the configured allow-list (an allow-list, because a deny-list has to be complete to work).
- Refused with **413** if the servlet container rejected the body before the application saw it.
- The stored path is **generated** (`<ticketId>/<uuid><ext>`); the client's filename is kept for display
  and for the download header, and is never used to build a path.
- Downloads are always `Content-Disposition: attachment`, never `inline`. The stored content type is
  whatever the client declared — nothing sniffs the bytes — so letting a browser render it would be
  stored XSS via a file picker.

Bytes live on disk under `APP_ATTACHMENT_DIR`, not in the database. One consequence is worth knowing:
that directory is **per-instance**, so a horizontally scaled deployment needs shared storage or a new
`AttachmentStorage` implementation. See [`docs/DECISIONS.md`](docs/DECISIONS.md) §5.

### Filtering and search

`GET /api/tickets` takes five optional filters, all ANDed together, and each one is in the API because
something uses it rather than because it might be useful:

| Parameter | Matches | Index |
| --- | --- | --- |
| `status` | exactly | `idx_tickets_status_created_at` serves the count |
| `priority` | exactly | none - a dedicated one was built, measured, chosen zero times, dropped (`V12`) |
| `assignedTo` | the assignee's user id | `idx_tickets_assigned_to_created_at` |
| `raisedBy` | the raiser's user id | `idx_tickets_raised_by_created_at` |
| `q` | free text, see below | **none, deliberately** |

`q` is a case-insensitive substring match over `ticket_number`, `title` and `description`. Three
properties of it are worth knowing:

- **`%` and `_` are escaped.** They are LIKE's wildcards, and a search box hands them straight to it.
  Unescaped, a lone `%` returns every ticket - which reads as the filter being ignored - and `TKT_1`
  would quietly match `TKT-1`. `TicketSearchIT` has the tests that fail if the `ESCAPE` clause is ever
  removed as redundant.
- **Blank means absent.** `?q=` and `?q=%20%20` are what a search box sends when nobody is searching,
  and `TicketFilter`'s constructor normalises both to no filter rather than to `LIKE '%%'`.
- **It cannot widen visibility.** Like the rest of the list, it is ANDed with the ownership
  restriction, so a caller without `TICKET_READ_ALL` cannot search their way to a ticket they could
  not already open.

**There is no index for `q`, and that is a decision.** A leading wildcard leaves a B-tree nothing to
seek on, so a search reads the rows it filters. The fix would be `pg_trgm` plus a GIN index per
searched column - but every index in `V12` earned its place by being measured, and `V12`'s fifth
candidate was deleted for failing the same test. No equivalent measurement exists here: there is no
production table, and a seeded one would answer a question about invented data. The recipe for
settling it with numbers is written out on `TicketQueryRepository#findTicketRows`.

Searching is a **parameter on the list**, not a `/search` endpoint. A separate endpoint would have
grown its own paging, sorting, filters and - the part that matters - its own copy of the ownership
restriction, and a search that forgets that restriction is a way to read other people's tickets.

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

## Notifications

Added in `V22`. One row per person per thing that happened to them, written by the request that
caused it.

**Recipients come from the ticket, not from a role.** `raised_by` and `assigned_to`, and that is
all - with one exception: a ticket nobody owns yet has no assignee to tell, so
`TICKET_AWAITING_ASSIGNMENT` goes to the holders of `TICKET_ASSIGN`. Fanning out by role instead
would put every event in the system in front of every `DIRECTOR`, who would stop reading them by the
third day.

**The actor is never a recipient.** Removed once, in `NotificationFanOut.save`, rather than at each
call site - "do not tell somebody what they just did" is the single largest source of notification
noise, and it should be one line of code rather than a convention five methods have to remember. A
recipient also appears at most once per event, because one person can be both the raiser and the
assignee.

| Type | Who gets it |
| --- | --- |
| `TICKET_AWAITING_ASSIGNMENT` | holders of `TICKET_ASSIGN`, when a new ticket has no assignee |
| `TICKET_ASSIGNED_TO_YOU` | the new assignee |
| `TICKET_NOW_ASSIGNED` | the requester - somebody owns it now |
| `TICKET_TAKEN_FROM_YOU` | the previous assignee, on reassignment |
| `TICKET_STATUS_CHANGED` | requester and assignee |
| `TICKET_COMMENTED` | the other party on the thread |
| `TICKET_OVERDUE` | the assignee (or the triage queue if unowned) and the requester |

**The type is written from the recipient's point of view**, which is why one assignment produces
three of them. The alternative was a single `TICKET_ASSIGNED` plus a comparison at render time, and
the only thing a client could compare is a full name - `auth.users.full_name` has no unique
constraint, so two colleagues sharing a name would each read the other's assignment as their own.

**No rendered sentence is stored.** The type, the ticket and the two values are; the wording is the
client's. A sentence written into a row cannot be reworded, corrected or translated afterwards, and
every row already written would keep the old text for ever.

**The ticket's number and title are copied in, not joined.** A notification states what was true when
it happened, and the person it was addressed to was entitled to that text at that moment. Neither
column can be changed through this API, so the snapshot cannot drift - and it is what keeps a
notification readable by somebody since reassigned away, which is the right outcome: they are being
shown what they were already told. The link still 404s for them, because `TicketVisibilityGuard` has
not changed.

**Recording happens inside the transaction that caused the change.** A notification for a status
change that rolled back would announce something that never happened, and the only way to be certain
it cannot is for the rows to live or die with the change. That is also why `NotificationFanOut` does
nothing but build and save: the transaction is somebody's request, so anything slow in there is
latency on their click.

**A comment notifies without becoming an audit event.** The decision that a comment writes no
`ticket_history_tracking` row and does not bump `updated_at` is untouched - see
`TicketCommentServiceImpl`. A notification is a message to a person; an audit row is a record of a
state change. A comment is the first and not the second.

### The overdue scan

Every other notification is caused by somebody doing something. Nobody causes a deadline to pass, so
`OverdueTicketScanner` looks - hourly by default
(`APP_OVERDUE_SCAN_INTERVAL`, ISO-8601). Scanning rather than a timer per deadline: a query against
an indexed column has no state to get wrong, where durable per-ticket timers would need cancelling
when a deadline moves or a ticket is resolved. The cost is that a ticket is announced up to one
interval after it goes late, which for a business deadline measured in days is not a delay anybody
notices.

**Idempotency is the whole design**, because the scan meets the same overdue ticket on every run for
as long as it stays overdue. The event key carries the deadline, so one ticket passing one deadline
is one event however many scans see it - and a *moved* deadline that is missed again is correctly a
new one. Keys already present are filtered out before inserting rather than letting the unique
constraint reject them: a constraint violation would roll the whole scan back and take the tickets it
had not reached yet with it.

Settled work is never late. The test is on status, not on `resolved_at`, because a ticket can be
closed without ever passing through `RESOLVED`.

### What is not built

- **No delivery.** These are recorded, not sent. Nothing pushes and nothing emails. Delivery does not
  belong in `NotificationFanOut` - an SMTP call inside the transaction that assigns a ticket would
  make a mail outage look like an assignment failure. It is an
  `@TransactionalEventListener(AFTER_COMMIT)` reading these rows, and it is deliberately absent rather
  than stubbed.
- **No per-user preferences.** Everybody gets everything their involvement earns them. Preferences
  are worth having once there is a channel noisy enough to want turning off, which in-app is not.
- **No digest.** `MANAGER`, `HOD` and `DIRECTOR` receive nothing unless they raised or were assigned
  the ticket, deliberately: what they want is an SLA summary, which is a different mechanism from a
  feed and needs reporting endpoints that do not exist.

## Swagger

`http://localhost:9211/swagger-ui.html` — **gated by default.** Set `APP_SWAGGER_PUBLIC=true` for local
development or a demo environment; the document enumerates every endpoint, its parameters and the
permission each asserts, so exposure is an opt-in rather than something to remember to switch off.
With the flag off, both the UI and `/v3/api-docs` need a token like any other endpoint.

To use a protected endpoint from the page: run `POST /api/auth/login`, copy the `accessToken` from the
response, click **Authorize** at the top and paste it — the token value alone, without a `Bearer `
prefix, which Swagger UI adds itself. Every operation except login carries a padlock and will then
send the token.

The Authorize button exists because `OpenApiConfig` declares the bearer scheme; springdoc does not
infer one from the filter chain, and without the declaration the page could only ever reach the login
endpoint.

## Tests

```bash
./mvnw test      # 106 unit tests, no infrastructure needed, ~2s
./mvnw verify    # the above plus 220 integration tests in a PostgreSQL container
```

Integration tests are named `*IT` and run under failsafe, so **`mvn test` does not run them** - use
`mvn verify` before pushing.

They need Docker. Each run starts one throwaway `postgres:18-alpine` container, applies all 21
migrations to it and lets Hibernate validate the mappings against the result, so the migrations and the
entity mappings are themselves under test. **No test touches a local database.** Attachments are
written to a temporary directory, so no run touches `./data/attachments` either.

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
| `JwtSigningKeyTest` | that a missing secret refuses startup, that the message carries the way out, and that the opt-in generates a strong but unstable key |
| `LocalAttachmentStorageTest` | generated keys, extension validation, and the keys that escape the storage root - which cannot be reached through the API, so only a unit test can send them |
| `SafeFileNameTest` | what a client filename is reduced to before it reaches a column and a `Content-Disposition` header |
| `ResourceAccessControlIT` | the IDOR/BOLA boundaries: 404 rather than 403, list scoping, per-role endpoint permissions |
| `TicketLifecycleIT` | create/assign/status, `resolvedAt` rules, audit trail, severity sorting, optimistic locking, that every name in a response is accompanied by an id on both mappers, and that timestamps serialize as ISO-8601 instants rather than epoch numbers |
| `AuthenticationIT` | uniform login failure, throttling, password change, duplicate detection |
| `ApiErrorHandlingIT` | every status mapping, and that no response leaks internals |
| `TicketStatusAndModuleIT` | the module-belongs-to-application rule, the status body and `remarks`, the no-op status change |
| `ApplicationCatalogueIT` | catalogue uniqueness and NOT NULL (V15), and that the dead `ticket_history` table is gone (V16) |
| `OpenApiDocumentIT` | that the document declares the bearer scheme Swagger UI needs for its Authorize button, that the requirement is global, and that login and refresh opt out while logout does not |
| `BootstrapAdminIT` | the first-administrator flow: that the created account can log in and actually manage users, and that the three guards hold - already-an-admin, unconfigured, email taken |
| `SessionLifecycleIT` | revocation: logout, revoke-all-on-password-change, deactivation ending live sessions, an expired session, a validly signed token with no `sid`, a subject/session mismatch, and the purge - including that it spares a session whose refresh window is still open |
| `RefreshTokenIT` | rotation, and mostly the refusals: single use, that reusing a token revokes the whole login, that an unknown token revokes nothing, the absolute cap, a missing refresh window, a deactivated account, logout and password change killing the refresh token, and that losing a concurrency race is a 401 rather than reuse |
| `UserRoleAdministrationIT` | several roles per user and the union rule, creating and replacing role sets, unknown role codes, the roleless account, role deactivation, the `USER_MANAGE` lockout guard, and that a grant records its grantor |
| `TicketCommentIT` | the thread end to end, that both sides can take part, that a comment changes nothing about the ticket, chronological ordering, and that involvement - not the permission - decides whose thread you reach |
| `TicketAttachmentIT` | upload/list/download, the three refusals, that a generated path cannot be steered by a traversal filename, that an attachment is bound to its ticket, and the row-whose-file-is-gone case |
| `SwaggerGatingIT` | the now-default gated path, including that its 401 is a problem document. Runs in its own context, because the flag is read when the filter chain is built |

If Testcontainers cannot find Docker on Docker Desktop for Windows, point it at the active endpoint:
`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.

## Decisions and open questions

[`docs/DECISIONS.md`](docs/DECISIONS.md) records the open business questions (status transitions,
department-scoped visibility), the limitations accepted deliberately (per-instance throttling,
deep-page cost), the security headers that were reviewed and rejected, and — in §7 — the two changes
`V20` made to what the API reports, with the earlier reasoning each one overturns.

Two entries there are worth reading before writing a client: *Token storage in the browser*, which
decides where each credential lives and why not a cookie, and §2's refresh-token entry, which explains
reuse detection and the one thing a client must not get wrong.

## Known gaps

- **Login throttling is per-instance** (in-memory), so it weakens if the app is scaled out.
- **The overdue scan is not safe on two instances.** Both would read the same candidates, see the
  same empty set of existing keys, and the second to commit would fail on
  `uq_notifications_recipient_event` - so one scan succeeds and the other rolls back having done
  nothing. Nothing is duplicated and nothing is lost, but a scan is wasted, and under contention
  it is possible for neither to progress. It needs a lock before a second instance runs:
  `SELECT ... FOR UPDATE SKIP LOCKED` over the candidates, or ShedLock around the method. Stated
  rather than fixed because there is no multi-instance deployment here to test it in, and an
  untested lock is a worse answer than a documented limit.
- **Attachment storage is per-instance.** `LocalAttachmentStorage` writes to a local directory, so a
  file uploaded through one instance is not readable from another unless `APP_ATTACHMENT_DIR` points at
  shared storage. Object storage is a new implementation of the two-method `AttachmentStorage`
  interface, which is why that interface exists.
- **Attachment rows and blobs are not written atomically.** Nothing spans PostgreSQL and a filesystem.
  The order is chosen so a crash leaks an unreferenced file rather than a row pointing at nothing, and
  a row whose file has gone is reported as a 500 naming the attachment; see
  [`docs/DECISIONS.md`](docs/DECISIONS.md) §5.
- **Upload content types are taken on trust.** The allow-list matches the `Content-Type` the client
  sent; nothing sniffs the bytes. Downloads are therefore always served as attachments, never inline.
- **Comments and attachments cannot be edited or deleted.** Both are append-only. For attachments the
  blocker is a rule nobody has set — may the uploader remove it, the assignee, after closure?
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
- **`users.department_id` / `designation_id`** reference tables that do not exist, which is why
  `MANAGER`, `HOD` and `DIRECTOR` have no department-scoped visibility.
- **Offset pagination is O(offset)** on deep pages; keyset pagination would be a contract change.
- **Free-text search is unindexed.** `?q=` on the ticket list reads the rows it filters, because a
  substring match cannot use a B-tree. Documented rather than fixed: see **Filtering and search** for
  why, and for the measurement that would decide it.
