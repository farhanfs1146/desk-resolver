-- Ticket timestamps become absolute instants (TIMESTAMPTZ) instead of wall-clock times (TIMESTAMP).
--
-- WHY THIS IS A CORRECTNESS FIX, NOT A PREFERENCE
-- V17 already made this distinction and took the other side of it, deliberately:
--
--     "TIMESTAMPTZ here, TIMESTAMP elsewhere - a deliberate break from the convention in V3/V7.
--      These columns are security lifetimes [...] Ticket timestamps are business data read by
--      humans and keep their type."
--
-- That reasoning held while "read by humans" meant one office, one clock. It stops holding the
-- moment two branches in different offsets read the same ticket, which is the stated direction of
-- this application. A TIMESTAMP column stores the digits 14:30 and nothing else: not which 14:30,
-- and not whose. So:
--
--   * "Raised at 14:30" means two different moments to Sialkot and to a branch on another offset,
--     and neither reader can tell. Nothing fails and nothing is logged - the API is simply wrong
--     about when things happened, in the one field an SLA would be measured from.
--   * ORDER BY created_at stops being chronological once rows are written by instances in different
--     zones, so the triage queue silently mis-orders.
--   * tickets.created_at and auth.sessions.issued_at - two timestamps about the same request - could
--     not be compared, because only one of them named a moment.
--
-- WHY NOW
-- The conversion is a table rewrite, so its cost scales with the row count. These tables are small
-- today and no client reads the field yet (there is no front end). Both of those stop being true at
-- the same time, and a rewrite of a live ticket history under an ACCESS EXCLUSIVE lock is a
-- maintenance window rather than a migration.
--
-- THE CONVERSION ZONE, AND WHY IT IS A LITERAL
-- Existing rows were written by LocalDateTime.now() in the application JVM's default zone - not the
-- database's, which is a separate setting and is commonly UTC in a container. A bare
-- ALTER COLUMN ... TYPE TIMESTAMPTZ would reinterpret those digits in the DATABASE SESSION's zone,
-- which is exactly the mistake this migration exists to stop making: it would be right only by
-- coincidence, and wrong by whole hours without saying so.
--
-- So the zone is named here instead of inferred. Every row in every database this migration will
-- ever meet was written by a single instance running in Pakistan Standard Time (UTC+5).
--
--     IF YOU ARE APPLYING THIS TO A DATABASE WHOSE ROWS WERE WRITTEN ELSEWHERE, CHANGE THE
--     LITERAL BELOW FIRST. There is no way for the migration to detect the mistake afterwards:
--     the digits convert successfully whatever zone is named, and every value simply lands on the
--     wrong instant.
--
-- A Flyway placeholder was considered instead and rejected: it moves a value this file must be read
-- to understand into application.yaml, where the one person who needs to change it is the one least
-- likely to be looking. A literal next to the paragraph explaining it is the form a reviewer can
-- check.
--
-- No value in these columns is ambiguous, which is worth stating because it is the reason nothing has
-- broken yet: Asia/Karachi has observed no DST since 2009, so there is no repeated or skipped local
-- hour for AT TIME ZONE to resolve arbitrarily. A deployment in a DST zone would not have been so
-- lucky, and that is the second half of the bug this closes.
--
-- INDEXES AND CONSTRAINTS
-- Nothing is dropped or recreated here. ALTER COLUMN ... TYPE rewrites the table and rebuilds every
-- dependent index as part of the same statement, so V8's (ticket_id, changed_at DESC), V12's four
-- created_at indexes and V19's two remain, with their ordering intact. The columns carry no DEFAULT
-- and no CHECK - every one of them is written by the application - so there is no expression to
-- re-cast.
--
-- Each table is converted in a single ALTER TABLE with several ALTER COLUMN clauses, which is one
-- rewrite per table rather than one per column.
--
-- WHAT DOES NOT CHANGE
--   * auth.* was already TIMESTAMPTZ (V17) and is untouched.
--   * ticket_history.action_time is not here because V16 dropped that table.
--   * Precision stays at the PostgreSQL default of microseconds, which is what
--     TicketServiceImpl.now() truncates to.

--------------------------------------------------------------------------------------------------
-- tickets
--------------------------------------------------------------------------------------------------
ALTER TABLE tickets
    ALTER COLUMN expected_by TYPE TIMESTAMPTZ USING expected_by AT TIME ZONE 'Asia/Karachi',
    ALTER COLUMN created_at  TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Karachi',
    ALTER COLUMN updated_at  TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Karachi',
    ALTER COLUMN resolved_at TYPE TIMESTAMPTZ USING resolved_at AT TIME ZONE 'Asia/Karachi';

COMMENT ON COLUMN tickets.created_at IS
    'Absolute instant, stored UTC. Rendered in the reader''s own zone by the client, never by the API.';
COMMENT ON COLUMN tickets.expected_by IS
    'Absolute instant the requester wants this resolved by. An offset-less datetime is rejected by the API rather than guessed at.';

--------------------------------------------------------------------------------------------------
-- ticket_comments
--------------------------------------------------------------------------------------------------
ALTER TABLE ticket_comments
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Karachi';

--------------------------------------------------------------------------------------------------
-- ticket_attachments
--------------------------------------------------------------------------------------------------
ALTER TABLE ticket_attachments
    ALTER COLUMN uploaded_at TYPE TIMESTAMPTZ USING uploaded_at AT TIME ZONE 'Asia/Karachi';

--------------------------------------------------------------------------------------------------
-- ticket_history_tracking
--------------------------------------------------------------------------------------------------
-- The audit trail is the column where a wrong instant is least recoverable: it is append-only and
-- nothing rewrites it, so a value stored against the wrong moment stays wrong permanently.
ALTER TABLE ticket_history_tracking
    ALTER COLUMN changed_at TYPE TIMESTAMPTZ USING changed_at AT TIME ZONE 'Asia/Karachi';
