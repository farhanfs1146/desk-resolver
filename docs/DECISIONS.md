# Decisions, open questions and accepted limitations

This file holds the reasoning that code comments across the project point at. It exists because roughly
a dozen comments referenced `docs/SECURITY.md`, `docs/PERFORMANCE.md`, `docs/ARCHITECTURE_AUDIT.md` and
`docs/TESTING.md`, none of which were ever committed — so the arguments behind several deliberate
choices were cited but unreadable, and the open questions were recorded nowhere.

Nothing here is new policy. Every entry is either a decision the code already implements or a question
the code already declines to answer.

Measurements are **not** repeated here. They live in the migration that acts on them — `V12` for the
ticket-list indexes, `V14` for the user directory, each with its `EXPLAIN (ANALYZE, BUFFERS)` numbers and
the candidates that were built, measured and rejected.

`V17`'s indexes carry **no** measurements, and say so. They are created on empty tables in the same
migration, so there is nothing to measure and a number produced from seven roles would say nothing
about a deployment. Each is justified by naming the query it serves instead. Re-measure once
`auth.sessions` has a real login history behind it.

---

## 1. Open business questions

These need someone with authority over the support process, not a developer. Each one fails closed today:
the capability is withheld rather than guessed at.

### Status transitions are unvalidated

Any status may follow any other. The intended happy path is documented in `TicketServiceImpl` as
`OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED`, but nothing enforces it, and no specification,
acceptance criteria or `CHECK` constraint defines which transitions are legal in general — may `CLOSED`
return to `OPEN`, and from where may `REOPENED` be entered?

`UNDER_REVIEW`, `PENDING` and `REOPENED` are defined in the enum but unreachable through the normal flow;
they sit at the end of `TicketStatus` precisely because no sequence was agreed.

Encoding a transition table would mean inventing the workflow, and a wrong one blocks real support work.
`TicketLifecycleIT.transitionsAreNotValidated` pins the current behaviour, so it is the test that should
start failing the day this is decided.

**Related and also undecided:** whether a requester may move their own ticket — closing it once satisfied,
for instance. `TICKET_STATUS_CHANGE` is withheld from requester roles entirely, which is the safe
direction but probably not the final answer.

### Department-scoped visibility

`MANAGER`, `HOD` and `DIRECTOR` hold exactly the same permissions as `EMPLOYEE`. It is tempting to assume a
manager should see their department's tickets, but nothing in the schema supports it:
`users.department_id` and `users.designation_id` are bare `BIGINT` columns pointing at tables that do not
exist. There is no department to scope by, and no way to test such a rule.

Granting broad visibility on the strength of a role's name would hand three roles the ability to read
every ticket in the system. These roles stay restricted until departments exist and the rule is stated.

**Unchanged by Phase 7.** Moving roles into `auth.roles` made it easy to give `MANAGER` more
permissions, and that is exactly why it was not done: the missing thing was never the mechanism, it was
the department. `users.department_id` is still a bare `BIGINT` pointing at nothing, now in the `auth`
schema instead of `desk_resolver_db`. `RolePermissionSeedIT.seniorRequesterRolesAreNotPrivileged`
pins the three roles to `EMPLOYEE`, so a seed that quietly widened one of them would fail the build.

### May a requester be an assignee?

Yes, today, and deliberately. A rejected change is recorded here so it is not re-proposed: requiring an
assignee's role to hold `TICKET_STATUS_CHANGE`, on the reasoning that a requester-role assignee can see a
ticket but never progress it.

That is wrong, and the codebase says so in two places. `Permission.TICKET_READ_OWN` is documented as
"read tickets the user is involved in, as raiser *or as assignee*", and `TicketRow.involves` implements
that for any user regardless of role — if only status-changers could be assignees, the assignee half of
that check would be unreachable for every requester role. `ResourceAccessControlIT.assigneeCanRead`
asserts an `EMPLOYEE` assignee can read and list the ticket assigned to them.

Assignment to a requester is plausibly "this needs something from you". Narrowing it would mean inventing
a workflow rule and breaking a passing security test to do it.

### Does reassignment reset progress?

Assigning a ticket forces its status to `ASSIGNED`, including for a ticket already `IN_PROGRESS`. Whether
that is right is unanswered; the behaviour is left as it was rather than changed on a guess.

---

## 2. Accepted limitations

Known, deliberate, and not defects. Each has a cost that was weighed.

### ~~Access tokens cannot be revoked~~ — resolved in Phase 7

**This limitation is gone.** It is kept here, struck through rather than deleted, because the reasoning
that produced it was sound and the thing that changed it is worth recording.

The original position: authentication is stateless JWT, chosen because the API serves a browser SPA and
is meant to scale horizontally — sessions would need sticky routing or a shared session store, and a
shared store was out of scope. The accepted cost was that a token stayed valid until it expired
(30 minutes), so logout was a client-side gesture and a password change could not end a session somebody
already held.

What changed the calculation was that Phase 7 moved authorization into the database. Resolving a
caller's permissions became a query per request whether or not anything was revocable — so revocation
stopped needing a round trip of its own and became a predicate on a query that was already happening.
`auth.sessions` holds one row per issued token, the token names it in a `sid` claim, and
`AuthContextLoader` resolves session validity, account status, roles and permissions together.
`SessionLifecycleIT` is the evidence; every test in it would have failed before.

**The shared store the original reasoning ruled out is the database**, which was already shared. That
is the whole trick, and it is also the limit of it: this is not a session store holding state, it is a
revocation list written positively. The filter chain is still `STATELESS`, no cookie is set and no
`HttpSession` exists.

**What it cost.** One indexed lookup on the request path where there had been none, and a row per
login that has to be purged on a schedule. Both are accepted deliberately. The lookup is the same
query that database-driven authorization required anyway; the purge is `SessionService`.

**What is still true.** Signature, expiry and issuer are checked first and unchanged. An external
identity provider remains a change to `SecurityConfig.jwtDecoder` plus retiring the login endpoint —
but its tokens would carry no `sid`, so the session gate would have to be rethought at the same time.
That is the honest price of having made it a gate rather than an optimisation.

### No refresh tokens

A token lasts 30 minutes and then the client authenticates again. Sessions made revocation possible;
they do not extend anything.

Rotating refresh tokens are the standard answer and `auth.sessions` is where they would live — the
table already has the identity, the lifetime and the revocation columns such a scheme needs. They are
not built because they are a design in their own right (rotation, reuse detection, a second credential
to store in a browser safely) and nothing has asked for one. The cost in the meantime is that an active
user re-authenticates every half hour.

### Login throttling is per-instance

`InMemoryLoginAttemptLimiter` keeps its counters in the application's own memory, so it protects a single
instance. Run more than one and each enforces the limits independently, giving an attacker spreading
attempts across instances a correspondingly larger budget.

`LoginAttemptLimiter` is an interface for exactly this reason: replacing it with a shared store is
providing a different bean, and nothing in `AuthServiceImpl` changes.

### `X-Forwarded-For` assumes a single trusted hop

Ignored entirely by default — it is just a request header, and trusting it unconditionally is a
rate-limiter bypass that also lets an attacker get somebody else's address throttled.

When `app.security.rate-limit.trust-forwarded-headers` is enabled, the **right-most** entry is read,
because a proxy appends the real peer and everything to its left may be client-supplied fiction. That is
correct for exactly one trusted hop. Behind two or more proxies it yields the inner proxy's address, not
the client's, so the per-address limit becomes coarser than intended. The failure direction is safe
(over-throttling, never under-throttling). Check it against the real deployment before enabling the flag.

If the application is placed behind a load balancer *without* enabling the flag, every request appears to
come from the proxy and the per-address limit effectively becomes global. The per-account limit still
protects individual passwords.

### Deep pagination is O(offset)

Offset paging is inherently O(offset) whatever the index. `V14` measured it: at `OFFSET 2,000` the user
directory index makes execution 51× faster but reads *more* buffers, because an ordered walk of 2,020
rows replaces one sort. The real fix is keyset ("seek") pagination, which changes the API contract and was
therefore out of scope.

### Pagination metadata is in headers, not a wrapper

Collection endpoints return a plain JSON array and put the page metadata in `X-Total-Count`,
`X-Total-Pages`, `X-Page-Number`, `X-Page-Size` and `X-Has-Next`.

Headers were chosen over a wrapper object specifically so existing clients could keep parsing the body.
The behavioural change was unavoidable — an unbounded list was the original defect — but a client that
ignores the new parameters now receives the newest 20 rows instead of every row, rather than failing to
parse anything at all. All five headers are CORS-exposed, or a browser client could not read them.

### Swagger is public by default

`app.security.swagger-public` defaults to `true` for development convenience. **Set it to `false` in
production**; the API description is a map of the attack surface.

---

## 3. Security headers: what was reviewed and rejected

Spring Security's defaults already cover what matters for a JSON API, so `SecurityConfig` adds one header
and otherwise leaves them alone.

| Header | Decision |
| --- | --- |
| `X-Frame-Options: DENY` | Kept explicit. Already a default, but an API should never be framed. |
| `X-Content-Type-Options: nosniff` | Already a default; stops a browser second-guessing the declared content type. |
| `Referrer-Policy: no-referrer` | **Added.** Not a Spring default. Stops a URL containing a ticket or user id leaking to third parties. Safe for both the JSON API and the Swagger UI. |
| `Content-Security-Policy` | Not set. A JSON API renders nothing, and a policy strict enough to matter would break the Swagger UI. It belongs on whatever serves the SPA. |
| `Strict-Transport-Security` | Not set here. TLS is terminated upstream, and the component that terminates it is the one that should assert HSTS. |

**CSRF is disabled, deliberately.** CSRF protection exists because browsers attach ambient credentials —
cookies — to cross-site requests automatically. This API authenticates only via an
`Authorization: Bearer` header, which a browser never attaches on its own, and no cookie or session is
created anywhere. If cookie authentication is ever introduced, CSRF protection must come back with it.

---

## 4. The authorization model (Phase 7)

`V17` created the `auth` schema — `users`, `roles`, `permissions`, `role_permissions`, `user_roles`,
`sessions` — and `V18` dropped `users.role`. What follows is the reasoning; the migrations carry the
detail.

### Permissions are code, assignment is data

The one decision in this schema most worth understanding, and the one that is easy to get backwards.

A permission is enforced by a string literal: `@PreAuthorize("hasAuthority('TICKET_ASSIGN')")`. If the
catalogue were ordinary editable data, renaming or deleting that row would disable the check with
nothing failing anywhere — no test, no error, no log line. That is the worst available shape for an
authorization bug, so the `Permission` enum stays the source of truth for *which permissions exist*,
and `auth.permissions` is seeded from it.

What belongs in data is *assignment*: which role grants which permission, and which user holds which
role. Those are what an administrator changes, and they now change without a redeploy.

The risk the split creates is the two halves drifting, and both directions are quiet — a row with no
constant grants nothing and nobody notices; a constant with no row makes its endpoints unreachable for
everybody including administrators. `PermissionCatalogueValidator` therefore fails **startup**, not a
test, with both lists in the message. A deployment that cannot authorize correctly should not accept
traffic.

### Roles are data; there is no `Role` enum any more

Deleting the enum was the point. An enum cannot be extended without a redeploy, which is precisely
what moving roles into a table was meant to fix. The seven codes it defined are seeded unchanged.

`SystemRoles.ADMIN` is the single code application code still names, because
`BootstrapAdminInitializer` has to ask whether an administrator exists before creating one. It is a
lookup key, not a declaration: the role is resolved against the table and the bootstrap fails loudly
if it is absent.

### Permissions are a union, with no deny

A user's effective permissions are the flat union of their active roles' grants. No intersection, and
no DENY rows.

This is a choice, not an inevitability, and it has a consequence worth stating out loud: **granting a
role can only ever widen access.** An `EMPLOYEE` additionally granted `IT_SUPPORT` holds
`TICKET_READ_ALL` and sees every ticket in the system. `UserRoleAdministrationIT` asserts it, so it is
behaviour rather than an accident.

DENY was rejected rather than overlooked. It needs precedence rules, conflict resolution between two
roles that disagree, and tests for every combination — and the first question anyone asks of such a
system ("why can this person not do X?") becomes unanswerable by inspection.

### No role hierarchy

No `parent_role_id`, and Spring Security's `RoleHierarchy` is not used. With explicit
`role_permissions` rows, inheritance is redundant and it makes "where did this permission come from?"
a question requiring a graph walk. The seed computes `ADMIN` as support staff plus two permissions,
which is how the old static map read — but that is a way of *writing* seed data, not runtime
inheritance.

### No `user_permissions` table

A permission granted straight to a user, bypassing roles, is a legitimate pattern. It is deliberately
not built.

Without `granted_by`, `granted_at`, `expires_at` and a review process, direct grants become access
nobody can explain six months later, and role review stops meaning anything — you can no longer read
a user's roles and know what they can do. The table arrives when a concrete requirement does, and when
it does it will be additive grants only, time-bounded and audited.

### `users` lives in `auth`

Arguable, and the argument against it is recorded here because it is the one that was overruled rather
than refuted: `users` carries `employee_code`, `full_name`, `department_id` and `designation_id`, which
makes it an employee record as much as an identity record, and it is the target of four foreign keys
from the ticketing tables.

Those foreign keys turned out not to be the obstacle they looked like. `ALTER TABLE ... SET SCHEMA`
moves a table with its constraints, indexes and owned sequence, and a cross-schema reference inside one
database is an ordinary foreign key, so `V11`'s work was not disturbed and nothing had to be dropped
and recreated.

**The decision this forecloses:** if `auth` ever becomes a separate service with its own database,
cross-database foreign keys do not exist, and `tickets.raised_by` would have to stop being one. That
is a bigger change than moving a table back. Decide which `auth` is — a schema or a future service —
before building on this.

### The lockout guard

Removing `USER_MANAGE` from the last active account that holds it is a 409.

Nothing in the application could undo it: user administration is the capability being removed, and the
bootstrap administrator only runs when there is no administrator at all, so it would not step in
either. Recovery would mean hand-editing `auth.user_roles`.

Note how narrow it is in practice. The caller needs `USER_MANAGE` to reach the endpoint, so when they
are editing somebody else there is always at least one other holder — themselves. It can only fire on
an administrator removing their own last administrative role, which is exactly the mistake worth
catching. It was not generalised into a rule engine over permissions: one capability has this property,
and a framework for a single case reads worse than the case.

### Deactivating a role rather than deleting it

`auth.roles.active` is joined in the per-request authority query, so clearing it withdraws the role's
permissions from everyone holding it on their next request. Deleting the row would cascade the grants
away and destroy the record of who held it. Role deletion has no endpoint for the same reason.

### `role` is no longer a sort key on `/api/users`

A user can hold several roles, so there is no column to order a directory by. "Sort by role" would have
to mean primary role, or alphabetically-first role, or something else, and nothing has decided which.
An unknown sort property is already a 400, so a client still sending it is told rather than quietly
given a different order than it asked for.

### Swagger UI had no way to send a token

Found while writing up how to log in as the first administrator, and wrong since Phase 4.

The OpenAPI document declared no security scheme. Swagger UI renders its **Authorize** button only
when at least one is declared, and springdoc does not infer one — it does not read the filter chain,
so nothing told it the API authenticates with `Authorization: Bearer`. The page the README points
people at could therefore reach exactly one endpoint, `POST /api/auth/login`, and every other call
answered 401 with no way to supply the token login had just returned.

Nothing failed. The API was correct, every integration test passed, and the defect lived entirely in
the interactive client — the kind of gap that is only found by opening the page and looking for a
button. `OpenApiConfig` now declares the scheme and `OpenApiDocumentIT` asserts it, which is the
point: a documentation contract no functional test touches needs a test of its own.

The requirement is declared **globally**, mirroring `anyRequest().authenticated()` in the filter
chain — in both places the safe state is what you get by writing nothing, and a new endpoint is
documented as needing a token without anybody remembering to say so. `AuthController.login` opts out
with an empty `@SecurityRequirements`, which is one annotation on the one exception rather than an
annotation on every rule. It changes documentation only; `SecurityConfig.ALWAYS_PUBLIC` is what
actually makes that endpoint reachable.

### Bearer-token 401s go through the application's entry point

Found while testing revocation, and worth recording because it had been wrong since Phase 4.

Two different filters produce a 401. A request with no token at all is denied by `AuthorizationFilter`
and handled by `ExceptionTranslationFilter`, which uses the entry point from `exceptionHandling`. A
request whose token is *present but unusable* fails inside `BearerTokenAuthenticationFilter`, which
calls its own entry point — `BearerTokenAuthenticationEntryPoint` by default, untouched by
`exceptionHandling`.

So that second 401 had an **empty body**, breaking the project-wide invariant that every error is an
RFC 7807 document, and its `WWW-Authenticate` header carried an `error_description` saying whether the
token was malformed, expired or badly signed. That was tolerable while an unusable token meant a broken
client. With sessions it is routine — a logged-out or revoked token arrives there — so
`SecurityConfig` now names the application's entry point on the resource-server DSL as well. Both 401s
are now the same document and reveal the same nothing.

---

## 5. Unimplemented, with schema already present

`ticket_comments` (V4) and `ticket_attachments` (V5) exist as tables, with foreign keys added in V11, but
have no entity, repository, service or endpoint. Their half-built entities — every association commented
out, so neither could ever have been persisted against `NOT NULL` columns — were deleted rather than left
to look like working code.

The tables are deliberately kept. Unlike `ticket_history`, which V6 created and V7 immediately superseded
and which `V16` drops, these were never replaced by anything: the feature is simply not built. Dropping
them would discard a usable schema for a feature that is still wanted.

`Permission` has no entries for comments or attachments, on the principle that an unused permission is an
untested permission. They get one when the endpoints do.

---

## 6. Configuration and first run

Not duplicated here — see the **Running it** and **Authentication** sections of [`../README.md`](../README.md)
for every environment variable, the bootstrap-administrator flow, and the sessions model. The
authoritative role-to-permission table is GET /api/roles; the seed that produces it is in V17.

Two points that belong with the decisions above:

- **Never commit a secret.** `app.security.jwt.secret` and the bootstrap administrator's password come
  from the environment. With no JWT secret configured the application generates a random key per JVM and
  warns loudly: secure, but tokens do not survive a restart and will not validate on another instance.
- **The bootstrap administrator is a one-time door, not an account to keep.** It is created only when no
  user holds `ADMIN`, so it cannot be used to reset an existing administrator's password by restarting
  with different configuration. Change the password through `PATCH /api/users/me/password` afterwards and
  unset the variables.
