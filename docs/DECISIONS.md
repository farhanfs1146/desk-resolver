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
`auth.sessions` has a real login history behind it. The same applies to `V19`'s two indexes, created on
tables that were empty because the feature did not exist yet.

**Entries struck through have been answered or resolved.** They are kept rather than deleted: an
argument that turned out to be wrong is the one most worth being able to read, and a decision that was
reversed should not look like it was never made. §1's assignee question, §2's refresh tokens, §5 in
full, and both halves of §7 are all reversals of what this file previously recorded — §7's timestamp
entry quotes the paragraph it overturns, because the premise of that paragraph is what changed rather
than its logic.

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

### ~~May a requester be an assignee?~~ — answered: no

**No, as of the comments/attachments change.** This entry previously recorded the opposite, and the
reversal is kept visible rather than overwritten, because the argument that was wrong is instructive.

`TicketServiceImpl.requireAssignable` now refuses an assignee whose active roles do not grant
`TICKET_STATUS_CHANGE`, alongside the existing check that the account is active. Both conditions exist
for one reason: an assignment that cannot be acted on parks the ticket with a phantom owner. It reads
as `ASSIGNED`, drops out of every unassigned-work view, and the person holding it is refused by the API
the moment they try to record anything.

**What the old entry got right, and what it got wrong.** It was right that
`Permission.TICKET_READ_OWN` covers a ticket the user is assigned to, and that `TicketRow.involves` is
role-agnostic. That half is untouched: whoever sits in `assigned_to` can still read the ticket whatever
their roles are, and `ResourceAccessControlIT.assigneeCanRead` still asserts it for an `EMPLOYEE`
assignee — seeding the row directly, because rows like that exist (anything assigned before this check,
or changed in the database) and the read path has to keep answering correctly for them.

It was wrong that assignment is how you say "this needs something from you". That was the real need
behind the old decision, and it was being met by the only field that happened to be available. There is
now a comment, which any involved party can leave and read, so the assignee field no longer has to carry
a message it was never shaped for. Assignment means ownership of the work.

`TicketLifecycleIT.assignmentToRequesterRoleIsRejected` covers all four requester roles;
`assignmentFollowsTheCurrentGrants` pins that the check reads `auth.user_roles` per attempt rather than
comparing role names, so promoting somebody makes them assignable immediately; and
`bothConditionsAreCheckedIndependently` establishes that neither condition is standing in for the other.

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

### ~~No refresh tokens~~ — resolved in V21

**This limitation is gone.** The reasoning that produced it is kept because it named its own successor
precisely: *"rotating refresh tokens are the standard answer and `auth.sessions` is where they would
live"*, not built because they are *"a design in their own right (rotation, reuse detection, a second
credential to store in a browser safely)"*. All three had to be settled, and they were, in that order.

**What forced it was writing down what the cost actually was.** "An active user re-authenticates every
half hour" reads like friction and is not. The access token expires on a clock the user cannot see, so
the 401 lands mid-task — characteristically on the POST submitting a ticket description somebody spent
ten minutes writing. No client can recover from that: re-authenticating needs a password, and all a SPA
can offer is a login form over the top of the work in progress. The limitation was not an inconvenience
budget, it was a data-loss bug waiting for a front end to exist.

**The design, and the three questions it had to answer.**

*Rotation.* A refresh does not update a session in place. It marks the current row's refresh token used
and inserts a new session — new `sid`, new access token, new refresh token — carrying the same
`family_id`. A family is one login and everything descended from it. The predecessor is marked used but
**not revoked**, so the access token a client is still holding stays valid until its own `exp`: a
browser with two requests in flight when it rotates does not collect 401s for them.

*Reuse detection.* Rotation alone only shortens a window; the signal is what it buys. A refresh token is
usable exactly once, so a second presentation means two parties hold it and one of them copied it.
Nothing on the server can say which, so the whole family is revoked and both have to log in. That is the
intended trade: one interruption for the user, and an eight-hour credential destroyed for the attacker.

`refresh_used_at` is what makes this detectable, and it is why rotation marks rather than deletes. Note
the branch that is easy to collapse and must not be: *the row was already marked when it was read* is
reuse, while *the row looked usable but the conditional `UPDATE` lost the race* is a client that fired
twice — a single page does this trivially when three requests get 401 together. The first revokes the
family; the second is a plain 401 and the session survives. Revoking there would log people out for
double-clicking.

*A second credential to store in a browser safely.* See **Token storage in the browser** below, which is
the half of this that lives in the front end.

**Absolute, not sliding.** `refresh_expires_at` is copied forward unchanged on every rotation, so a
chain dies `app.security.refresh.ttl` after the login that began it however often it refreshes. A
sliding expiry would mean a session somebody uses daily never expires, which is precisely what turns one
stolen refresh token into permanent access.

**Why 8 hours and not 7 days.** The access token stays at 30 minutes — that is how long a leaked one is
usable for, and refresh tokens remove the last reason anyone had to lengthen it. Eight hours answers the
different question of how long somebody may work without typing their password, and one working shift is
the honest answer for an internal help desk: sign in on arrival, sign in again tomorrow. Seven days is
the usual default and is sized for consumer products where re-authentication is friction a team is
measured on; here it would leave a live credential in browser storage overnight and over a weekend to
save one login a week. It is also the number that would have to be defended to whoever asks why a stolen
laptop still had a live session on Monday.

**A SHA-256 of the token is stored, not a BCrypt hash** — which inverts the rule `users.password_hash`
follows, deliberately. BCrypt is slow because a password is low-entropy and guessable; a refresh token is
256 bits of `SecureRandom` output, so there is nothing to enumerate and the cost would be paid on every
refresh for nothing. More decisively, BCrypt salts per row, so the matching session could only be found
by verifying the candidate against every row in the table. A fast digest is the wrong choice for a
password and the right one here, and the difference is the entropy of the input.

**What it cost.** A row per refresh rather than a row per login, which the existing purge absorbs — and
that purge had to grow a second condition, because a session now outlives its own access token and the
row is the only place its refresh token exists. `POST /api/auth/refresh` is reachable without an access
token, necessarily: by the time a client calls it, the one it holds has expired. It is not
*unauthenticated* — it demands a 256-bit single-use credential this application issued.

**Logout changed meaning with it.** It revokes the family, not the one session. Those were the same thing
while a login *was* a single session; now a caller five refreshes into their day holds the newest
session, and revoking only that one would leave the refresh token that mints the next access token
alive. Logging out would not have logged them out — and the existing tests would have kept passing,
because they log in once and never refresh.

**What is deliberately still missing.** An idle timeout — it would log out everybody returning from
lunch, and the absolute cap is the control that matters for an internal tool. Binding a token to a device
or address — `client_ip` and `user_agent` are already recorded and already documented as never trusted
for a decision, since both are forged trivially and both change legitimately. And rate limiting on the
refresh endpoint: the credential is 256 bits, so guessing is not the threat, and a failed refresh is one
indexed `SELECT` where a failed login is a BCrypt verification. `RefreshTokenIT` covers the refusals,
which is where nearly all of the behaviour is.

### Token storage in the browser

Not a backend limitation, but the backend decided it, so it is recorded here rather than left to
whoever writes the front end first.

**The decision: the access token lives in memory only, the refresh token in `sessionStorage`.**

The access token is a JavaScript variable — a signal, a service field, not persisted anywhere. It
survives no reload, which is fine, because the refresh token exists to rebuild it. The refresh token is
the one that must survive F5, so it goes in `sessionStorage`: scoped to the tab, gone when the tab
closes, and never attached to a request by the browser on its own.

**`localStorage` was rejected.** It persists across tabs and across browser restarts, so a token that
should die with the tab instead outlives the machine being walked away from.

**An `HttpOnly` cookie is the stronger option on paper, and was still rejected.** Injected JavaScript
cannot read one, so an XSS could not carry the token away for later use. Three things weigh against it:

1. **It would cost the property this API is built on.** A cookie is an *ambient* credential — the browser
   attaches it to cross-site requests by itself. "No ambient credential exists" is the entire stated
   justification for CSRF protection being disabled (`SecurityConfig`), so one cookie means bringing CSRF
   protection back, turning `allowCredentials` on, and losing the guarantee that a cross-site request
   cannot be authenticated at all. That is a large change to the most load-bearing property in the
   security model, for one field.
2. **The protection is narrower than it sounds.** An XSS that cannot read the cookie can still call
   `/api/auth/refresh` from the page and use what comes back. It stops the token being *exfiltrated*, not
   the session being *used*. The marginal gain is "the attacker loses access when the tab closes" — real,
   and much smaller than the usual framing.
3. **Anything that can read `sessionStorage` can equally read the in-memory access token**, and act as
   the user for 30 minutes either way.

**So the controls that actually matter are elsewhere, and three of them are already decided.** A strict
`Content-Security-Policy` on whatever serves the SPA — which §3 of this document already assigns there,
on the grounds that a JSON API renders nothing — is what reduces the probability of an XSS in the first
place. The 8-hour absolute cap bounds how long a stolen refresh token is worth anything. Reuse detection
means that using a copied token destroys the session for whoever copied it.

**What the front end must do, and it is not optional.** Single-flight the refresh: one refresh in flight
at a time, every other request queued behind it. Several requests meeting a 401 together is the normal
case, not an edge case, and firing a refresh for each means all but one lose the race and get a 401 they
will read as "log the user out". The server keeps the session alive through that — losing a race is not
treated as reuse — so the only thing that breaks is a client that did not queue.

**Revisit this if** the SPA is ever served from the same origin as the API. That removes the CORS half of
objection 1 and makes a `SameSite=Strict`, refresh-only cookie considerably cheaper to adopt.

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

### ~~Swagger is public by default~~ — the default is now `false`

`app.security.swagger-public` used to default to `true`, with a note in this file saying to set it to
`false` in production. That is the shape of mitigation that does not work: it relies on every
deployment reading the documentation and overriding a default that is already wrong, and nothing fails
if one does not. The API description enumerates every endpoint, its parameters and the permission each
asserts, which is a map of the attack surface.

The default is now `false`. Set `APP_SWAGGER_PUBLIC=true` for local development or a demo environment —
an opt-in to exposure, which is the direction a security default has to run.

`SwaggerGatingIT` pins the gated path, including that the refusal is an RFC 7807 document and that an
authenticated caller can still read the document. It pays for a second application context, because the
flag is read when the filter chain is built and every other suite needs it on; that was preferable to
shipping, as the default, the branch nothing exercised.

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

## 5. ~~Unimplemented, with schema already present~~ — comments and attachments are built

This section used to record `ticket_comments` (V4) and `ticket_attachments` (V5) as tables with no
entity, repository, service or endpoint — kept rather than dropped, unlike `ticket_history`, because
they were never superseded by anything and the feature was simply not built. `V19` builds it, so there
is no unreachable schema left in this database.

It also said `Permission` would gain entries "when the endpoints do". It has:
`TICKET_COMMENT_READ`, `TICKET_COMMENT_CREATE`, `TICKET_ATTACHMENT_READ` and
`TICKET_ATTACHMENT_UPLOAD`, seeded by `V19` in the same change as the controllers that assert them and
the suites that exercise them.

### Participation is granted to every role

All four permissions go to all seven roles, which is wider than the ticket permissions in V17 and is the
decision most worth stating. Those distinguish requesters from support staff because reading *every*
ticket and changing a ticket's *state* are things a requester must not do. Commenting and attaching are
neither — they are how the person who raised a ticket supplies the screenshot and answers the question
that gets it resolved, and withholding them from requesters would leave the feature with nobody to talk
to.

The grant is safe because it is not what controls scope. Every comment and attachment endpoint runs
`TicketVisibilityGuard` first, so a requester reaches the thread on a ticket they raised or were
assigned and gets the same 404 as ever on anybody else's. Support staff reach every thread because they
hold `TICKET_READ_ALL`, not because of anything in V19.

### Comments are append-only, and change nothing about the ticket

No update or delete endpoint, no `updated_at`, no `@Version`: a comment is a record of something
somebody said at a time, and editing one would rewrite a conversation other people have already read.

Commenting also writes nothing to `ticket_history_tracking` and does not touch the ticket's status or
`updated_at`. That is what lets requesters comment at all — a comment that moved a ticket through states
would be a state change in disguise, handing out the thing `TICKET_STATUS_CHANGE` is withheld for. The
thread is its own append-only record, next to the audit trail rather than inside it.
`TicketCommentIT.commentingDoesNotTouchTheTicket` asserts it.

### Attachment bytes live on a disk, not in a `bytea` column

The row names a file; the file sits under `app.storage.attachments.directory`. A `bytea` column was
rejected: it would put arbitrary user-supplied megabytes through the connection pool and into every
backup of a database whose other tables dump in seconds, and it would turn a streaming download into a
question of how much heap one request may use.

The cost is that the two stores can disagree, and this is accepted rather than hidden. Nothing spans
PostgreSQL and a filesystem, so the order is chosen to leave the harmless inconsistency: the file is
written first and the row second, and the row's insert is flushed inside a `try` that deletes the blob
if it fails. A crash between the two statements leaks an unreferenced blob — invisible, wasted disk —
rather than a row pointing at nothing, which would be an attachment a user can see and cannot download.
`TicketAttachmentIT.aMissingBlobIsReported` covers the row-without-bytes case, which a restored backup
or a cleared volume produces: it is a 500 naming the problem, with the row left intact.

### Uploads: an allow-list, a generated path, and never `inline`

Three decisions, each refusing a class of problem rather than filtering instances of it:

- **Content types are an allow-list**, not a deny-list. A deny-list has to be complete to work, and the
  entry nobody thought of is the one that gets used. Note what this does *not* establish: the value
  matched is the `Content-Type` the client sent, and nothing sniffs the bytes.
- **The stored path is generated** — `<ticketId>/<uuid><ext>` — and the client's filename is never part
  of it. So there is no path-shaped input to sanitise, which is the version of a traversal defence that
  cannot be bypassed by an encoding nobody considered. `LocalAttachmentStorage.load` re-checks that a
  key resolves inside the root anyway, because keys come from the database and a row could have been
  written by something else.
- **Downloads are always `Content-Disposition: attachment`.** Since the declared content type is not
  trustworthy, serving a file inline would let an uploader choose how the browser executes it in this
  application's origin — stored XSS via a file picker. `X-Content-Type-Options: nosniff` is already
  global, so there are two independent reasons the bytes cannot render as script.

The client's filename is still kept, for display and for the download header, and is reduced to its last
segment with control characters and quotes stripped — not as the traversal defence, but because that
string lands in a `Content-Disposition` header where a `CR` is header injection. `SafeFileNameTest`
covers it.

### No delete endpoint for attachments

Removing an attachment is two irreversible operations that cannot be made atomic, and the rule it would
need — may the uploader remove it? the assignee? after the ticket is closed? — is not established
anywhere. Same reasoning as the status transitions above: the gap is left open rather than filled with
an invented rule.

### Local disk does not survive horizontal scaling

`LocalAttachmentStorage` writes to a directory, so a file uploaded through one instance is not readable
from another unless the directory is shared storage. This is the second thing in this application that
does not scale horizontally, alongside the in-memory login throttle.

It is behind the `AttachmentStorage` interface for exactly that reason: object storage is a new
implementation of two methods, not a change to the service or the controller. Deletion is deliberately
*not* on that interface — the feature has no delete operation, and putting one there would be shaping
the contract around a rollback path.

---

## 6. Configuration and first run

Not duplicated here — see the **Running it** and **Authentication** sections of [`../README.md`](../README.md)
for every environment variable, the bootstrap-administrator flow, and the sessions model. The
authoritative role-to-permission table is GET /api/roles; the seed that produces it is in V17.

Two points that belong with the decisions above:

- **Never commit a secret.** `app.security.jwt.secret` and the bootstrap administrator's password come
  from the environment.
- **A missing JWT secret is now a startup failure, not a warning.** It used to generate a random key per
  JVM and log loudly about it. That was secure and still wrong, because the failure it produces does not
  look like a configuration failure: the application starts, logins succeed, tokens are issued, and then
  every token stops working after a restart and none validates on a second instance. Behind a load
  balancer that is an intermittent 401 following no visible pattern, with the explanation hours up the
  log. Refusing to start reports the same mistake at the moment it is made.

  The convenience is restored by one explicit opt-in, `app.security.jwt.allow-generated-secret`
  (`APP_JWT_ALLOW_GENERATED_SECRET`), which a developer machine sets and nothing shared should. It has
  to be turned on deliberately, so it cannot be the thing nobody noticed. `JwtSigningKeyTest` covers
  both paths, including that a configured secret still wins over the flag.
- **The bootstrap administrator is a one-time door, not an account to keep.** It is created only when no
  user holds `ADMIN`, so it cannot be used to reset an existing administrator's password by restarting
  with different configuration. Change the password through `PATCH /api/users/me/password` afterwards and
  unset the variables.

---

## 7. The API's data types (V20)

Two changes to what the API *says*, as opposed to what it does. Both were made now rather than later
for the same reason: there is no front end yet, so the cost is a migration and a recompile. Both get
considerably more expensive the day a browser client depends on the old shape.

### Ticket timestamps are instants, not wall-clock times

V17 drew this distinction and took the other side of it, in writing:

> `TIMESTAMPTZ` here, `TIMESTAMP` elsewhere — a deliberate break from the convention in V3/V7. These
> columns are security lifetimes [...] Ticket timestamps are business data read by humans and keep their
> type.

**That reasoning was sound and its premise expired.** "Read by humans" meant one office and one clock. A
`TIMESTAMP` column stores the digits `14:30` and nothing else — not which `14:30`, and not whose — so
the moment two branches on different offsets read the same ticket:

- **"Raised at 14:30" means two different moments** and neither reader can tell. Nothing fails and
  nothing is logged; the field is simply wrong about when something happened, which is the one thing it
  exists to say — and the field an SLA would be measured from.
- **`ORDER BY created_at` stops being chronological** once rows are written by instances in different
  zones, so the triage queue silently mis-orders.
- **`tickets.created_at` and `auth.sessions.issued_at` could not be compared.** Two timestamps about the
  same request, and only one of them named a moment.

Pakistan Standard Time has observed no DST since 2009, which is why nothing had broken yet: there is no
repeated or skipped local hour for the conversion to resolve arbitrarily. A deployment in a DST zone
would not have been so lucky, and that is the second half of what this closes.

**Java-side it is `Instant`, not `OffsetDateTime`.** Hibernate maps both to `TIMESTAMP_UTC`, so the
column is the same either way; the difference is what the API hands a client. `OffsetDateTime` would
carry *an* offset, but not a meaningful one — PostgreSQL renders `TIMESTAMPTZ` in the session's zone, so
the offset a client received would describe the database server's configuration and nothing about the
ticket. `Instant` states the moment and leaves the rendering to the only party that knows who is
looking: the browser. `TicketLifecycleIT.timestampsAreIsoInstants` pins the wire format, because it is
decided by Jackson configuration rather than by any code here and nothing else would notice it changing.

**One breaking input change, kept rather than softened.** `CreateTicketRequest.expectedBy` now requires
an offset or `Z`; a bare `2026-10-20T17:00:00` is a 400 naming the field. It used to be accepted and
interpreted in whatever zone the server's JVM ran in — a deadline whose meaning depended on where the
application was deployed. There is no zone the API could substitute that would be right more often than
wrong, and the client always knows the one the person typing meant.

**The migration names its conversion zone as a literal**, because a bare `ALTER COLUMN ... TYPE
TIMESTAMPTZ` reinterprets the existing digits in the *database session's* zone, which is a separate
setting from the JVM's and is commonly UTC in a container. It would have been right only by coincidence
and wrong by whole hours without saying so. A Flyway placeholder was considered and rejected: it moves a
value the migration must be read to understand into `application.yaml`, where the one person who needs
to change it is least likely to be looking.

### Responses identify people and applications, not just name them

`TicketResponse` carried `raisedBy`, `assignedTo` and `applicationName` as bare display strings. It now
carries `raisedById`, `assignedToId` and `applicationId` alongside them.

This was a defect rather than a minimal API:

- **Names are not unique.** `auth.users.full_name` has no unique constraint and real directories contain
  repeats, so two colleagues with the same name were indistinguishable in a queue — including when
  deciding whether a ticket was one's own. `applications.app_name` is explicitly non-unique too;
  `ApplicationPageRequests` appends `id` as a sort tiebreaker for exactly that reason.
- **Nothing could be navigated to.** `GET /api/users/{id}` takes an id, and so do `?assignedTo=` and
  `?raisedBy=`. With only a name, a client wanting "other tickets by this person" had to fetch the whole
  user directory and match on a string — an N+1 over a list, keyed on a value that is not a key.

The names stay, because they are what a list renders and resolving them client-side would be that same
N+1 in the other direction. Both halves cost nothing: the two user ids were already on `TicketRow` for
authorization, and all three are foreign-key columns on `tickets` itself, so no query changed shape.

**`TicketCommentResponse`, `TicketAttachmentResponse` and `TicketHistoryResponse` still report people by
name only**, and that is now the minority position in the codebase rather than the rule. It is left
deliberately: visibility of a thread is decided by the ticket, so unlike `TicketRow` there is no
authorization question an id would answer, and nothing reads one yet. An unused field is an untested
field — the same rule V17 applies to speculative permissions. If a client needs to attribute a comment
to a user record rather than render a name, the id belongs there and costs nothing, because
`commented_by` is already joined. It is **not** withheld because exposing it would be unsafe:
`GET /api/users/{id}` needs `USER_READ`, which no requester role holds, and the ids are sequential
bigints nobody has to be told about to guess.
