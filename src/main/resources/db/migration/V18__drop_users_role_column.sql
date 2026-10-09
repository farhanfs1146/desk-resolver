-- Retires users.role, now that auth.user_roles is the only source of a user's roles (Phase 7).
--
-- Deliberately a separate migration from V17 rather than its last statement. V17 creates the auth
-- schema, seeds it and backfills every user's role into auth.user_roles; this drops the column that
-- backfill read from. Keeping them apart means the structural change and the destructive one can be
-- applied, inspected and reasoned about independently - and if V17's backfill had been wrong, the
-- original values would still have been there to look at.
--
-- Nothing in the application reads the column any more: the User entity has no role field, the JWT
-- carries no role claim, and authorities come from the per-request query over auth.sessions,
-- auth.user_roles and auth.role_permissions. Hibernate runs with ddl-auto: validate, so a mapping
-- that still referenced it would fail startup rather than fail quietly.
--
-- The sort whitelist on GET /api/users lost "role" at the same time (UserPageRequests): a user can
-- now hold several roles, so there is no single column to order a directory by. "Sort by role" would
-- have to mean something first - primary role, or alphabetically-first role - and nothing has decided
-- which, so the capability is withheld rather than guessed at.

ALTER TABLE auth.users
    DROP COLUMN role;
