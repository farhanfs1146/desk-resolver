package com.forward.desk_resolver.repository.projection;

import java.time.Instant;

/**
 * Flat read projection for a ticket's comment thread.
 *
 * <p>Same reasoning as {@link TicketRow}: selecting into a record makes Hibernate emit one query with
 * an explicit join and load no managed entities, where selecting {@code TicketComment} entities would
 * resolve each one's {@code commentedBy} association separately - a page of twenty comments from
 * twenty different people being twenty follow-up selects.
 *
 * <p>The author is carried as a <strong>name, not an id</strong>, and no id for them appears here at
 * all. A comment thread is the widest-read part of a ticket, so it is the last place to hand out user
 * ids that another endpoint takes as a path variable. Nothing about a comment needs one: visibility
 * was already decided by the ticket, so unlike {@link TicketRow} there is no authorization question
 * here that an id would answer.
 *
 * <p><strong>This is now the minority position in the codebase, and still deliberate.</strong>
 * {@code TicketResponse} does carry {@code raisedById}, {@code assignedToId} and
 * {@code applicationId} - added because a client holding only a non-unique display name could neither
 * tell two identically named people apart nor call any endpoint about either of them. That argument
 * applies to a thread too, and if a client ever needs to attribute comments to a user record rather
 * than render a name, the id belongs here and costs nothing: {@code commented_by} is already joined.
 * It is left out until something actually reads it, on the same rule V17 applies to speculative
 * permissions - an unused field is an untested field - and not because exposing it would be unsafe.
 * {@code GET /api/users/{id}} needs {@code USER_READ}, which no requester role holds, and ids are
 * sequential bigints that nobody has to be told about to guess.
 */
public record TicketCommentRow(
        Long id,
        String comment,
        String commentedByFullName,
        Instant createdAt
) {
}
