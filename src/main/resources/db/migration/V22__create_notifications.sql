-- In-app notifications: one row per person per thing that happened to them.
--
-- WHAT THIS IS FOR
-- Until now the client's notification bell had nothing to read. The front end derived it from a page
-- of recently updated tickets - honest, but a view rather than an inbox: it had no read state, so a
-- line disappeared when its ticket stopped matching rather than when somebody had seen it, and there
-- was nothing to mark as read because nothing was being recorded.
--
-- WHY FAN-OUT ON WRITE, NOT ON READ
-- A row per (recipient, event) rather than a row per event with recipients worked out at read time.
-- The deciding factor is read state: "has Ali seen this" is a fact about Ali and this event, so it
-- has to live somewhere that is per-recipient. Computing recipients at read time would mean either
-- re-deriving them from the ticket on every poll - which gives a different answer once a ticket is
-- reassigned, so a notification would silently change hands - or a second join table that is this
-- table with extra steps.
--
-- The cost is duplication: a status change on a ticket with a raiser and an assignee writes two rows.
-- That is the right trade for a help desk, where an event concerns one or two people and occasionally
-- the handful holding TICKET_ASSIGN. It would be the wrong trade for a broadcast.
--
-- WHY THE TICKET'S NUMBER AND TITLE ARE COPIED IN
-- Denormalised deliberately, not for speed. A notification states what was true when it happened,
-- and the person it was addressed to was entitled to that text at that moment. A join to `tickets`
-- would instead render whatever the ticket says now, and would raise the question of what to show
-- somebody who has since been reassigned away - the answer being "what they were already told",
-- which is what a snapshot is. It also keeps the inbox query off `tickets` entirely.
--
-- Nothing is lost by copying: neither column can be edited through this API. There is no endpoint
-- that changes a ticket's title, and ticket_number is generated once from a sequence.
--
-- WHAT IS NOT STORED: A RENDERED SENTENCE
-- The type and the values are stored; the wording is the client's. Two reasons, and the first is
-- already a documented problem in this schema: auth.users.full_name has no unique constraint, so a
-- stored "Assigned to Farhan Ali" is ambiguous in a way an actor_id is not. The second is that a
-- sentence written into a row cannot be reworded, corrected or translated afterwards - every row
-- already written keeps the old text for ever.

CREATE TABLE notifications
(
    id                BIGSERIAL PRIMARY KEY,

    -- Whose inbox this is. The only authorization fact the read side needs: a caller may read a
    -- notification if and only if this is their id, which is why these endpoints assert no
    -- permission of their own.
    recipient_id      BIGINT       NOT NULL,

    -- Who caused it. Null for the ones nobody caused - a deadline passing is the only such event
    -- today, and it is the scheduler that notices, not a user.
    actor_id          BIGINT,

    notification_type VARCHAR(50)  NOT NULL,

    ticket_id         BIGINT       NOT NULL,
    ticket_number     VARCHAR(100) NOT NULL,
    ticket_title      TEXT         NOT NULL,

    -- What changed, for the types where that is the news: the old and new status, or the old and new
    -- assignee's name. Null where the type carries the whole meaning on its own.
    old_value         TEXT,
    new_value         TEXT,

    created_at        TIMESTAMPTZ  NOT NULL,

    -- Null until read. A nullable timestamp rather than a boolean: "when" is strictly more than
    -- "whether", costs the same, and answers how long something sat unread.
    read_at           TIMESTAMPTZ,

    -- WHAT MAKES A REPEAT HARMLESS
    -- Identifies the logical event, so the same event cannot produce two rows in one inbox.
    --
    -- This is load-bearing for exactly one producer and insurance for the rest. The event-driven
    -- types are written inside the transaction that made the change, so a retry rolls the rows back
    -- with it and there is nothing to collide with. The hourly overdue scan is different: it sees
    -- the same overdue ticket on every run for as long as it stays overdue, and the key - which
    -- includes the deadline - is the only thing stopping it from announcing it again every hour.
    -- Change the deadline and it is a new event, which is correct.
    event_key         VARCHAR(200) NOT NULL,

    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_id) REFERENCES auth.users (id) ON DELETE CASCADE,

    -- CASCADE, unlike this schema's usual RESTRICT, and for both of these the reason is the same:
    -- a notification is not a record of the ticket or of the account, it is a message to somebody
    -- about one. With its recipient or its subject gone it is unreachable noise, so RESTRICT would
    -- only mean a deletion failing until somebody cleared an inbox by hand. Contrast
    -- ticket_history_tracking, which is an audit trail and must outlive everything it mentions.
    --
    -- In practice neither fires through the API, which deletes neither users nor tickets.
    CONSTRAINT fk_notifications_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,

    -- SET NULL rather than CASCADE: the event still happened, and the recipient is still entitled to
    -- know. Losing the actor costs a name in the rendered line, not the notification.
    CONSTRAINT fk_notifications_actor
        FOREIGN KEY (actor_id) REFERENCES auth.users (id) ON DELETE SET NULL,

    CONSTRAINT uq_notifications_recipient_event
        UNIQUE (recipient_id, event_key)
);

-- The inbox listing: one recipient's notifications, newest first.
--
-- `id DESC` after `created_at DESC` for the reason PageRequests appends it to every sort - several
-- rows of one fan-out share a timestamp to the microsecond, and ordering by a non-unique column
-- alone makes offset paging skip and repeat rows between pages.
CREATE INDEX idx_notifications_recipient_created_at
    ON notifications (recipient_id, created_at DESC, id DESC);

-- The badge count, which is requested far more often than the list itself - every poll asks for it
-- and most answers are a small number or zero.
--
-- Partial, so the index holds only unread rows. The read ones are the overwhelming majority over
-- time and none of them can ever match this query, so indexing them would mean a structure that
-- grows for ever to answer a question about a set that does not.
CREATE INDEX idx_notifications_unread
    ON notifications (recipient_id) WHERE read_at IS NULL;
