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

### Access tokens cannot be revoked

Authentication is stateless JWT, chosen because the API serves a browser SPA and is meant to scale
horizontally — sessions would need sticky routing or a shared session store, and a shared store is out of
scope. Stateless tokens also keep the door open for an external identity provider: that becomes a change
to `SecurityConfig.jwtDecoder` plus retiring the login endpoint, with no effect on business services.

The cost is that a token stays valid until it expires (30 minutes by default). A password change stops new
tokens being minted with the old password, which is what the bootstrap-administrator case needs, but it
does not cut off a session someone already holds. Authorities are recomputed from the user's role on every
request rather than read from the token, so a permission change does take effect immediately.

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

## 4. Unimplemented, with schema already present

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

## 5. Configuration and first run

Not duplicated here — see the **Running it** and **Authentication** sections of [`../README.md`](../README.md)
for every environment variable, the bootstrap-administrator flow, and the role-to-permission table.

Two points that belong with the decisions above:

- **Never commit a secret.** `app.security.jwt.secret` and the bootstrap administrator's password come
  from the environment. With no JWT secret configured the application generates a random key per JVM and
  warns loudly: secure, but tokens do not survive a restart and will not validate on another instance.
- **The bootstrap administrator is a one-time door, not an account to keep.** It is created only when no
  user holds `ADMIN`, so it cannot be used to reset an existing administrator's password by restarting
  with different configuration. Change the password through `PATCH /api/users/me/password` afterwards and
  unset the variables.
