package com.forward.desk_resolver.repository.projection;

import java.time.Instant;

/**
 * One audit entry, read as a projection.
 *
 * <p>A projection rather than the entity for the usual reason in this codebase, and here it matters
 * more than most: {@code TicketHistoryTracking} has two {@code @ManyToOne} associations
 * ({@code ticket} and {@code changedBy}) which default to EAGER, so reading a page of entities would
 * issue a select per distinct referenced ticket and user - audit finding P1-1 reproduced on a new
 * endpoint. Selecting the actor's name directly makes a page exactly two statements.
 *
 * <p>{@code changedByFullName} carries the actor as a name and no id, matching
 * {@link TicketCommentRow} - which records why, including why {@code TicketResponse} no longer does
 * the same. An audit reader identifies a person, and the trail is read next to the ticket whose
 * {@code raisedById} and {@code assignedToId} are already there.
 */
public record TicketHistoryRow(
        Long id,
        String actionType,
        String fieldName,
        String oldValue,
        String newValue,
        String changedByFullName,
        String remarks,
        Instant changedAt
) {
}
