package com.forward.desk_resolver.repository.projection;

import java.time.LocalDateTime;

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
 * ids that another endpoint takes as a path variable. The ownership ids {@link TicketRow} carries
 * exist because an authorization decision needs them; nothing about a comment does, because visibility
 * was already decided by the ticket.
 */
public record TicketCommentRow(
        Long id,
        String comment,
        String commentedByFullName,
        LocalDateTime createdAt
) {
}
