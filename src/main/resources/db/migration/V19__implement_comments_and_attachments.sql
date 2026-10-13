-- Brings ticket_comments (V4) and ticket_attachments (V5) into service.
--
-- Both tables have existed since the first migrations and have been unreachable ever since: no entity,
-- no repository, no endpoint. docs/DECISIONS.md section 5 recorded them as "unimplemented, with schema
-- already present" and gave the reason they were not dropped alongside the dead ticket_history table in
-- V16 - unlike that one, these were never superseded. The feature was simply not built. This migration
-- is the other half of that decision finally being paid off.
--
-- Three things happen here, in this order:
--   1. the four permissions the new endpoints assert are seeded, and granted to roles;
--   2. ticket_attachments gains the columns an upload actually needs;
--   3. both tables gain the index their list endpoint reads through.
--
-- ticket_comments needs no column changes: comment/created_at/ticket_id/commented_by is exactly the
-- shape the feature wants, which is why it was worth keeping.


-- ---------------------------------------------------------------------------
-- 1. Permissions
-- ---------------------------------------------------------------------------
-- Seeded from the Permission enum, as in V17. PermissionCatalogueValidator fails startup if these and
-- the enum disagree in either direction, so this INSERT and that enum are a matched pair - neither can
-- be changed alone.
INSERT INTO auth.permissions (code, description)
VALUES ('TICKET_COMMENT_READ', 'Read the discussion thread on a ticket.'),
       ('TICKET_COMMENT_CREATE', 'Add a comment to a ticket.'),
       ('TICKET_ATTACHMENT_READ', 'List a ticket''s attachments and download their contents.'),
       ('TICKET_ATTACHMENT_UPLOAD', 'Attach a file to a ticket.');

-- Granted to every role, including the requester roles.
--
-- This is a wider grant than the ticket permissions in V17, and deliberately so. Those distinguish
-- requesters from support staff because reading EVERY ticket and changing a ticket's STATE are things a
-- requester must not do. Commenting and attaching are neither: they are how the person who raised a
-- ticket supplies the screenshot and answers the question that gets it resolved. Withholding them from
-- requesters would leave the feature with nobody to talk to.
--
-- Ticket scope is not what these control, and that is the reason the wide grant is safe. Every comment
-- and attachment endpoint runs TicketVisibilityGuard before anything else, so a requester reaches the
-- thread on a ticket they raised or were assigned, and gets the same 404 as ever on anyone else's.
-- Support staff reach every thread because they hold TICKET_READ_ALL, not because of anything here.
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM auth.roles r
         CROSS JOIN auth.permissions p
WHERE p.code IN ('TICKET_COMMENT_READ', 'TICKET_COMMENT_CREATE',
                 'TICKET_ATTACHMENT_READ', 'TICKET_ATTACHMENT_UPLOAD')
  AND r.code IN ('EMPLOYEE', 'MANAGER', 'HOD', 'DIRECTOR',
                 'IT_SUPPORT', 'DEVELOPER', 'ADMIN');


-- ---------------------------------------------------------------------------
-- 2. ticket_attachments: the columns an upload needs
-- ---------------------------------------------------------------------------
-- VERIFIED EMPTY FIRST, and verified again here rather than only in a comment.
--
-- Both columns below are NOT NULL with no default, which PostgreSQL rejects outright if any row
-- exists. A bare ALTER would therefore fail with a constraint error that says nothing about why, in
-- whichever environment happened to have data - so this raises first, with an explanation and an
-- instruction. V16 made the same check by hand and wrote the count into a comment; a DO block is the
-- version that still holds in an environment nobody checked.
--
-- There is deliberately no backfill. A pre-existing attachment row would name a file on a disk this
-- application has never managed, and inventing an uploader or a size for it would be fabricating audit
-- data. If this ever raises, the rows have to be looked at by a person.
DO
$$
    DECLARE
        existing BIGINT;
    BEGIN
        SELECT count(*) INTO existing FROM ticket_attachments;
        IF existing > 0 THEN
            RAISE EXCEPTION
                'V19 aborted: ticket_attachments holds % row(s). The table was unreachable from the application until now, so rows in it came from somewhere this migration cannot account for, and uploaded_by/size_bytes cannot be backfilled without inventing audit data. Inspect those rows and migrate or remove them by hand, then re-run. Nothing was changed.',
                existing;
        END IF;
    END
$$;

ALTER TABLE ticket_attachments
    -- Who uploaded this. The same reasoning as auth.user_roles.granted_by: it is the first thing asked
    -- about a file that should not be there, and it cannot be reconstructed later if it was never
    -- written down. RESTRICT, like every other user reference in this schema - a user who attached a
    -- file cannot be deleted out from under it.
    ADD COLUMN uploaded_by BIGINT NOT NULL REFERENCES auth.users (id) ON DELETE RESTRICT,
    -- Stored, not derived from the file on disk. The list endpoint shows it, and reading it from the
    -- filesystem would mean one stat() per row per page - and would report 0 rather than the truth for
    -- a file that has gone missing, which is the one case worth being able to see.
    ADD COLUMN size_bytes BIGINT NOT NULL,
    -- One row per stored blob. file_path is generated per upload (a UUID, never the client's filename),
    -- so a collision means a bug in that generation rather than a user action - and without this
    -- constraint it would show up as two rows quietly sharing one file, where deleting either breaks
    -- the other.
    ADD CONSTRAINT uq_ticket_attachments_file_path UNIQUE (file_path);

COMMENT ON COLUMN ticket_attachments.file_name IS
    'The name the client sent, kept for display and download only. Never used to build a path: it is attacker-controlled and may contain separators or traversal segments.';

COMMENT ON COLUMN ticket_attachments.file_path IS
    'Storage key relative to app.storage.attachments.directory, generated by the application as <ticketId>/<uuid><ext>. Not a client-supplied path.';


-- ---------------------------------------------------------------------------
-- 3. Indexes for the list endpoints
-- ---------------------------------------------------------------------------
-- Both list endpoints are "one ticket's rows, in time order", which is the same access path
-- idx_ticket_history_tracking_ticket_changed_at (V8) exists for. Without these, paging a thread is a
-- scan of every comment in the system filtered down to one ticket's.
--
-- The ordering direction differs between the two, and matches each endpoint's default sort rather than
-- being copied: a comment thread reads oldest-first, because a conversation does, while attachments and
-- audit rows read newest-first.
CREATE INDEX idx_ticket_comments_ticket_created_at
    ON ticket_comments (ticket_id, created_at, id);

CREATE INDEX idx_ticket_attachments_ticket_uploaded_at
    ON ticket_attachments (ticket_id, uploaded_at DESC, id DESC);
