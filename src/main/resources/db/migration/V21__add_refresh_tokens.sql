-- Rotating refresh tokens, carried by auth.sessions.
--
-- WHAT THIS CLOSES
-- docs/DECISIONS.md recorded the gap and named this as the fix:
--
--     "No refresh tokens. A token lasts 30 minutes and then the client logs in again. Rotating
--      refresh tokens are the standard answer and auth.sessions is where they would live."
--
-- The cost of not having them is not an inconvenience, it is lost work. An access token expires on a
-- wall clock the user cannot see, so the 401 arrives mid-task - typically on the POST that submits a
-- ticket description somebody has been writing for ten minutes. There is nothing a client can do
-- about that: re-authenticating needs a password, and the only thing it can offer is a login form
-- over the top of the form being filled in.
--
-- WHY THE REFRESH TOKEN LIVES ON THE SESSION ROW AND NOT IN ITS OWN TABLE
-- A session already IS the server-side record of one issued access token: who it belongs to, when it
-- expires, whether it was revoked. A refresh token is the credential that replaces that token with
-- the next one. Keeping them on one row means the two cannot disagree - revoking a session revokes
-- its refresh token in the same statement, with nothing to remember - and the per-request authority
-- query in AuthContextLoader is untouched, because none of these four columns is on its path.
--
-- A separate auth.refresh_tokens table would hold exactly the same four facts plus a foreign key,
-- and would make "is this credential still usable" two questions instead of one.
--
-- ROTATION: ONE ROW PER ACCESS TOKEN, CHAINED BY family_id
-- A refresh does not update a session in place. It marks the current refresh token as used and
-- INSERTS a new session - new id, new sid claim, new access token, new refresh token - carrying the
-- same family_id. A family is therefore one login and every token descended from it, and
--
--   * logging out revokes the family, so "log out" means the login ends rather than one 30-minute
--     slice of it ending;
--   * presenting a refresh token whose row is already marked used revokes the family (see below);
--   * the chain is the audit trail. "This session was refreshed fourteen times from this address" is
--     readable off the table, which an in-place UPDATE would have overwritten each time.
--
-- family_id for a login is the session's own id. No separate generation, and no row without a family.
--
-- WHY THE OLD SESSION IS MARKED USED BUT NOT REVOKED
-- Revoking it would kill the access token the client is still holding at the instant it rotates, and
-- a browser with two requests already in flight would get 401s for both. Marking it used makes the
-- refresh token unusable immediately - which is the credential that matters - while the old access
-- token dies on its own exp, at most jwt.ttl later. Nothing is extended: expires_at is untouched.
--
-- REUSE DETECTION, which is the only reason to rotate at all
-- Rotation on its own just shortens the window. What it buys is a signal: a refresh token is used
-- exactly once, so a SECOND presentation of one means two parties hold it, and one of them stole it.
-- There is no way to tell which, so the whole family is revoked and both have to log in again. That
-- is the intended outcome - the legitimate user is interrupted once, and the attacker loses an
-- 8-hour credential.
--
-- refresh_used_at is what makes this detectable, and it is why rotation marks rather than deletes.
--
-- ABSOLUTE LIFETIME, NOT SLIDING
-- refresh_expires_at is copied UNCHANGED from the previous session on every rotation. So a chain
-- cannot outlive app.security.refresh.ttl measured from the login that started it, however many
-- times it rotates. A sliding expiry - re-deriving it on each refresh - would mean a session that is
-- used daily never expires, which is the shape that turns a stolen refresh token into permanent
-- access.
--
-- WHY THE STORED VALUE IS A SHA-256 AND NOT A BCRYPT HASH
-- Unlike a password, a refresh token is 256 bits straight from a CSPRNG. There is no guessable
-- structure for a slow KDF to protect, so BCrypt's cost would buy nothing and be paid on every
-- refresh. It would also make the lookup impossible: BCrypt salts per row, so finding the matching
-- session would mean verifying the candidate against every row in the table. SHA-256 is
-- deterministic, so the token is found by an indexed equality lookup, and the column still holds
-- nothing an attacker who reads the table can present.
--
-- WHAT IS DELIBERATELY NOT HERE
--   * An idle timeout. It would need the refresh path to reject a chain whose previous access token
--     expired more than N minutes ago, which logs out everybody who comes back from lunch. For an
--     internal help desk the absolute cap is the control that matters; this gets reconsidered if a
--     shared-workstation requirement arrives.
--   * A device or client identifier to bind the token to. client_ip and user_agent are already
--     recorded and are already documented as never trusted for a decision - both are forged
--     trivially and both change legitimately (mobile networks re-NAT, browsers auto-update), so
--     binding to them would refuse real users without stopping a real attacker.
--   * Deleting a used row. The purge in SessionService already removes long-expired sessions, and
--     until then the used rows ARE the chain reuse detection reads.

--------------------------------------------------------------------------------------------------
-- Columns
--------------------------------------------------------------------------------------------------
ALTER TABLE auth.sessions
    ADD COLUMN family_id          UUID,
    ADD COLUMN refresh_token_hash VARCHAR(64),
    ADD COLUMN refresh_expires_at TIMESTAMPTZ,
    ADD COLUMN refresh_used_at    TIMESTAMPTZ;

-- Every pre-existing row is a login of its own: it has no ancestor, because rotation did not exist
-- when it was issued.
UPDATE auth.sessions SET family_id = id WHERE family_id IS NULL;

ALTER TABLE auth.sessions
    ALTER COLUMN family_id SET NOT NULL;

COMMENT ON COLUMN auth.sessions.family_id IS
    'The login this session descends from; equal to id for a session opened by POST /api/auth/login. Revoked as a unit by logout and by refresh-token reuse.';

-- refresh_token_hash is NULLABLE, and it has to be: the rows that existed before this migration were
-- issued without a refresh token and there is nothing to backfill them with. They remain usable as
-- access-token sessions until they expire, and simply cannot be refreshed - which is correct, not a
-- gap. Every row written after this migration carries one.
COMMENT ON COLUMN auth.sessions.refresh_token_hash IS
    'SHA-256 (hex) of the opaque refresh token. The token itself is returned once, at issue, and is never stored. NULL only on sessions predating V21, which cannot be refreshed.';

COMMENT ON COLUMN auth.sessions.refresh_expires_at IS
    'Absolute end of this chain, copied unchanged on every rotation - so refreshing never extends it beyond app.security.refresh.ttl from the original login.';

COMMENT ON COLUMN auth.sessions.refresh_used_at IS
    'Set when the refresh token on this row was exchanged. A second presentation of the same token is theft by definition and revokes the whole family.';

--------------------------------------------------------------------------------------------------
-- Indexes
--------------------------------------------------------------------------------------------------
-- The refresh lookup: one equality probe on a 64-character hash, which is the entire read path of
-- POST /api/auth/refresh.
--
-- UNIQUE because a duplicate would be a defect with real consequences - two sessions answering to
-- one credential, so rotating one would leave the other usable and reuse detection would miss it.
-- 256 bits of CSPRNG will not collide; what this constraint actually guards against is a future
-- change that reuses or copies a hash, and it makes that fail loudly at the INSERT.
--
-- Partial, so the pre-V21 rows with NULL hashes are not carried in it. (A unique index would permit
-- them anyway - NULLs do not conflict - but there is no reason to index rows this query can never
-- match.)
CREATE UNIQUE INDEX idx_sessions_refresh_token_hash
    ON auth.sessions (refresh_token_hash)
    WHERE refresh_token_hash IS NOT NULL;

-- Revoking a family: logout, and reuse detection. Partial on the live set for the same reason
-- idx_sessions_user_live is - an already-revoked session never needs revoking again, and the live
-- rows in one family are a handful.
CREATE INDEX idx_sessions_family_live
    ON auth.sessions (family_id)
    WHERE revoked_at IS NULL;
