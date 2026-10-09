-- The auth schema: identity, roles, permissions and sessions (Phase 7).
--
-- WHAT THIS REPLACES
-- Until now authorization was half data and half code. A user carried ONE role in users.role
-- (VARCHAR(50), no CHECK constraint), and the role-to-permission mapping lived in a compiled static
-- map (security/RolePermissions.java). Three consequences:
--   * A user could hold exactly one role. Somebody who is both a DEVELOPER and an ADMIN needed two
--     accounts - a workaround, not a model.
--   * Changing what a role may do required a redeploy.
--   * users.role had no CHECK constraint, so any string could be stored. The JWT converter failed
--     closed on an unknown role, but the read path did not: an unmappable value made Hibernate throw
--     while building the user directory, i.e. HTTP 500 on GET /api/users. A foreign key to
--     auth.roles makes that class of value impossible rather than merely unlikely.
--
-- WHAT STAYS IN CODE, AND WHY THAT IS NOT INCONSISTENT
-- The permission VOCABULARY stays in the Permission enum, and auth.permissions is seeded from it by
-- this migration. The reason is that code is what enforces a permission:
-- @PreAuthorize("hasAuthority('TICKET_ASSIGN')") names it as a literal. If the row were free-form
-- data, renaming or deleting it would silently disable an authorization check with no test failing.
-- So:
--     which permissions exist          -> code (enum), seeded here, verified at startup
--     which role holds which           -> data (auth.role_permissions)
--     which user holds which role      -> data (auth.user_roles)
-- PermissionCatalogueValidator fails startup if the enum and the table ever drift apart.
--
-- MOVING users INTO auth
-- users is referenced by four foreign keys added in V11 (tickets.raised_by, tickets.assigned_to,
-- ticket_history_tracking.changed_by, ticket_comments.commented_by), all ON DELETE RESTRICT.
-- ALTER TABLE ... SET SCHEMA moves the table together with its indexes, constraints and the sequence
-- owned by its BIGSERIAL column, and inbound foreign keys from other schemas keep pointing at it, so
-- nothing has to be dropped and recreated. Verified by the integration suite, which applies every
-- migration to an empty PostgreSQL and then lets Hibernate validate the mappings against the result.
--
-- The application connects with search_path = desk_resolver_db (spring.datasource.hikari.schema), so
-- every reference to an auth table is schema-qualified here and in the entity mappings
-- (@Table(schema = "auth")). Nothing relies on search_path order.
--
-- TRANSACTIONALITY
-- PostgreSQL has transactional DDL and Flyway runs a migration in one transaction, so if the
-- backfill guard below raises, the whole migration - schema, tables, seed and all - rolls back.
--
-- DELETE BEHAVIOUR, which deliberately differs from V11's RESTRICT-everywhere policy
--   * auth.role_permissions, auth.user_roles, auth.sessions -> ON DELETE CASCADE.
--     These are GRANTS and LIVE STATE, not audit records. A deleted role whose grants survive is a
--     contradiction, and a deleted user whose sessions survive is a security bug. V11's rule exists
--     to protect history (tickets, audit trail); none of that applies here.
--   * auth.role_permissions.permission_id -> ON DELETE RESTRICT.
--     The opposite direction, on purpose. Deleting a permission row would disable the check that
--     names it in code. RESTRICT makes that require an explicit, deliberate cleanup.
--   * auth.user_roles.granted_by -> ON DELETE RESTRICT, while user_id on the same table CASCADEs.
--     The asymmetry is the point. user_id CASCADEs because a deleted user holds no roles. granted_by
--     must not: cascading there would delete somebody ELSE'''s grant because the person who made it
--     left, which both removes their access and destroys the record of where it came from. RESTRICT
--     means a user who has granted a role cannot be deleted while that grant exists - the same
--     protection V11 gives the ticket audit trail, for the same reason.
--
-- INDEXES HERE ARE NOT MEASURED, unlike V12 and V14, and that is a deliberate difference rather than
-- an omission. Those two added indexes to existing tables with real row counts, so EXPLAIN (ANALYZE,
-- BUFFERS) was the only honest way to decide. These tables are created empty by this migration:
-- there is nothing to measure, and a number produced from seven roles and nine permissions would say
-- nothing about a deployment. Each index below is justified instead by naming the query it serves and
-- the fact that PostgreSQL does not index a referencing column on its own. Re-measure once
-- auth.sessions has a production login history behind it.
--
-- COLUMNS DELIBERATELY NOT CREATED, recorded so they are not re-proposed
--   * roles.parent_role_id / any hierarchy. With explicit role_permissions rows, inheritance is
--     redundant and makes "where did this permission come from?" unanswerable by inspection.
--     Effective permissions are the flat union of the user's roles' grants. Nothing else.
--   * roles.system_role / builtin. It would only be read by a role-deletion endpoint, which does not
--     exist. What actually prevents lockout is enforced in UserServiceImpl: the last account holding
--     USER_MANAGE cannot have it removed. An unused column is an untested column.
--   * user_permissions (a permission granted straight to a user, bypassing roles). Legitimate
--     pattern, deliberately not built yet: without granted_by/granted_at/expires_at and a review
--     process it turns into access nobody can explain six months later, and role review stops
--     meaning anything. It gets a table when a concrete requirement arrives, and additive grants
--     only - never DENY, which would need precedence rules and their tests.
--   * sessions.last_seen_at. It would mean a write on every authenticated request to populate a
--     column nothing reads. Rejected on cost.
--
-- TIMESTAMPTZ here, TIMESTAMP elsewhere - a deliberate break from the convention in V3/V7.
-- These columns are security lifetimes: sessions.expires_at is compared against Instant.now() in the
-- application and now() in SQL, and it has to line up with the JWT's exp claim, which is an absolute
-- instant. A wall-clock type with no offset is exactly where a DST bug would let an expired session
-- live for an extra hour. Ticket timestamps are business data read by humans and keep their type.

CREATE SCHEMA IF NOT EXISTS auth;

ALTER TABLE users SET SCHEMA auth;

--------------------------------------------------------------------------------------------------
-- auth.permissions - the capability vocabulary, seeded from the Permission enum
--------------------------------------------------------------------------------------------------
CREATE TABLE auth.permissions
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(500)
);

COMMENT ON TABLE auth.permissions IS
    'Capability vocabulary. One row per constant in the Permission enum; code is what @PreAuthorize names. Seeded by migration, verified at startup by PermissionCatalogueValidator. Never edited by hand.';

--------------------------------------------------------------------------------------------------
-- auth.roles
--------------------------------------------------------------------------------------------------
CREATE TABLE auth.roles
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN auth.roles.code IS
    'Stable identifier. Referenced by seed data, the bootstrap administrator and tests; treat as immutable. Display text belongs in name.';
COMMENT ON COLUMN auth.roles.active IS
    'A deactivated role grants nothing: the per-request authority query joins on active = true. Preferred over deleting a role, which would discard the record of who held it.';

--------------------------------------------------------------------------------------------------
-- auth.role_permissions
--------------------------------------------------------------------------------------------------
CREATE TABLE auth.role_permissions
(
    role_id       BIGINT NOT NULL REFERENCES auth.roles (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES auth.permissions (id) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, permission_id)
);

-- The primary key already serves role_id -> permissions, which is the per-request direction.
-- This covers the reverse - "which roles grant USER_MANAGE" - used by the lockout guard in
-- UserServiceImpl. PostgreSQL does not index a referencing column automatically.
CREATE INDEX idx_role_permissions_permission ON auth.role_permissions (permission_id);

--------------------------------------------------------------------------------------------------
-- auth.user_roles - many-to-many, which is the whole point of this migration
--------------------------------------------------------------------------------------------------
CREATE TABLE auth.user_roles
(
    user_id    BIGINT      NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    role_id    BIGINT      NOT NULL REFERENCES auth.roles (id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Nullable: rows created by this migration's backfill and by the bootstrap administrator have no
    -- human grantor. Every grant made through the API records one.
    granted_by BIGINT REFERENCES auth.users (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

COMMENT ON COLUMN auth.user_roles.granted_by IS
    'Who granted this role. The first question asked after a privilege incident, and not recoverable later if it was never written down.';

-- PK (user_id, role_id) covers the read direction. This covers "who holds this role".
CREATE INDEX idx_user_roles_role ON auth.user_roles (role_id);

--------------------------------------------------------------------------------------------------
-- auth.sessions
--------------------------------------------------------------------------------------------------
-- Makes an access token revocable, which docs/DECISIONS.md previously recorded as an accepted
-- limitation of stateless JWTs. A token now carries a sid claim; the request path looks the session
-- up, and a revoked, expired or deactivated-user session is a 401 immediately rather than at the end
-- of the token's 30 minutes. That is what logout, and revoke-all-on-password-change, needed.
--
-- The cost is one indexed query per authenticated request - and it is the same query that resolves
-- the caller's permissions, so it replaces the static map lookup rather than adding a round trip.
-- It is also what makes a role change take effect on the next request, which was already a property
-- of this application and had to be preserved.
--
-- The id is a client-opaque UUID generated by the application, not a sequence: it travels inside the
-- token, and a guessable, enumerable session identifier is not something to hand out.
CREATE TABLE auth.sessions
(
    id             UUID        PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(50),
    client_ip      VARCHAR(64),
    user_agent     VARCHAR(255)
);

-- Revoking every session a user holds (password change, deactivation). Partial, because a session
-- that is already revoked never needs revoking again, and the live set is the small one.
CREATE INDEX idx_sessions_user_live ON auth.sessions (user_id) WHERE revoked_at IS NULL;

-- The scheduled purge of long-expired rows. Without it this table only grows: one row per login.
CREATE INDEX idx_sessions_expires_at ON auth.sessions (expires_at);

--------------------------------------------------------------------------------------------------
-- Seed: permissions. One row per Permission enum constant, descriptions taken from its javadoc.
--------------------------------------------------------------------------------------------------
INSERT INTO auth.permissions (code, description)
VALUES ('TICKET_CREATE', 'Raise a ticket.'),
       ('TICKET_READ_OWN', 'Read tickets the user is involved in, as raiser or as assignee.'),
       ('TICKET_READ_ALL', 'Read any ticket regardless of involvement.'),
       ('TICKET_STATUS_CHANGE', 'Change the status of a ticket.'),
       ('TICKET_ASSIGN', 'Assign or reassign a ticket to a user.'),
       ('USER_READ', 'List and read user records.'),
       ('USER_MANAGE', 'Create user accounts and assign their roles.'),
       ('APPLICATION_READ', 'Read the application/module catalogue.'),
       ('APPLICATION_MANAGE', 'Create, update or deactivate applications.');

--------------------------------------------------------------------------------------------------
-- Seed: roles. Exactly the seven the Role enum already defined - no role is invented here.
--------------------------------------------------------------------------------------------------
INSERT INTO auth.roles (code, name, description)
VALUES ('EMPLOYEE', 'Employee', 'Raises tickets and tracks their own.'),
       ('MANAGER', 'Manager', 'Same capabilities as an employee today; department-scoped visibility needs a departments table that does not exist. See docs/DECISIONS.md.'),
       ('HOD', 'Head of Department', 'Same capabilities as an employee today; see MANAGER.'),
       ('DIRECTOR', 'Director', 'Same capabilities as an employee today; see MANAGER.'),
       ('IT_SUPPORT', 'IT Support', 'Triages, assigns and progresses every ticket.'),
       ('DEVELOPER', 'Developer', 'IT-side role, identical to IT_SUPPORT; this codebase draws no distinction between working a ticket and assigning one.'),
       ('ADMIN', 'Administrator', 'Support staff plus user and application management.');

--------------------------------------------------------------------------------------------------
-- Seed: role_permissions. A transcription of security/RolePermissions.java, which this replaces.
-- The groupings are unchanged, so no role gains or loses a capability in this migration -
-- RolePermissionSeedIT asserts exactly that, including that no role is accidentally privileged.
--------------------------------------------------------------------------------------------------
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM auth.roles r
         CROSS JOIN auth.permissions p
WHERE
  -- Requesters: people who raise tickets.
    (r.code IN ('EMPLOYEE', 'MANAGER', 'HOD', 'DIRECTOR')
        AND p.code IN ('TICKET_CREATE', 'TICKET_READ_OWN', 'APPLICATION_READ'))
  -- Support staff: the IT side.
   OR (r.code IN ('IT_SUPPORT', 'DEVELOPER')
    AND p.code IN ('TICKET_CREATE', 'TICKET_READ_OWN', 'APPLICATION_READ',
                   'TICKET_READ_ALL', 'TICKET_STATUS_CHANGE', 'TICKET_ASSIGN', 'USER_READ'))
  -- Administrators: support staff plus user and application management.
   OR (r.code = 'ADMIN'
    AND p.code IN ('TICKET_CREATE', 'TICKET_READ_OWN', 'APPLICATION_READ',
                   'TICKET_READ_ALL', 'TICKET_STATUS_CHANGE', 'TICKET_ASSIGN', 'USER_READ',
                   'USER_MANAGE', 'APPLICATION_MANAGE'));

--------------------------------------------------------------------------------------------------
-- Backfill: every existing user keeps exactly the role they had.
--------------------------------------------------------------------------------------------------
-- GUARD FIRST. users.role never had a CHECK constraint, so an unmappable value is possible, and
-- backfilling past it would silently strip that user of every permission - a fail-closed outcome,
-- but a silent one, and the sort of thing discovered weeks later. This raises instead, and the
-- enclosing transaction rolls the whole migration back.
DO
$$
    DECLARE
        unmapped BIGINT;
        examples TEXT;
    BEGIN
        SELECT count(*), coalesce(string_agg(DISTINCT u.role, ', '), '')
        INTO unmapped, examples
        FROM auth.users u
                 LEFT JOIN auth.roles r ON r.code = u.role
        WHERE r.id IS NULL;

        IF unmapped > 0 THEN
            RAISE EXCEPTION
                'V17 aborted: % user(s) carry a role value with no matching auth.roles.code (%). Add the missing role(s) to the seed above or correct the user rows, then re-run. Nothing was migrated.',
                unmapped, examples;
        END IF;
    END
$$;

INSERT INTO auth.user_roles (user_id, role_id)
SELECT u.id, r.id
FROM auth.users u
         JOIN auth.roles r ON r.code = u.role;

-- users.role is intentionally still here. V18 drops it, as a separate and separately revertable
-- step, once the backfill above has been applied and verified.
