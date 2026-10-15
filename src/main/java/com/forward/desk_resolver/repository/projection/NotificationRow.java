package com.forward.desk_resolver.repository.projection;

import com.forward.desk_resolver.enums.NotificationType;

import java.time.Instant;

/**
 * One notification, read as a projection.
 *
 * <p>A projection for a different reason from the others in this package. {@code Notification} maps
 * no associations at all, so reading entities would cost no extra selects - the N+1 argument that
 * justifies {@link TicketHistoryRow} does not apply here.
 *
 * <p>What it buys instead is the actor's <em>name</em>. The row stores {@code actor_id}, because the
 * write path has no use for anything more; the read path has to render "Farhan Ali commented", so
 * the name is joined in once per page rather than resolved per row by the caller. That join is also
 * the only reason the read side touches {@code auth.users} - everything else it needs is already on
 * the notification.
 *
 * <p>{@code actorFullName} is null where {@code actorId} is - a deadline passing has no actor - and
 * the join is therefore a left join. An inner join would silently drop every
 * {@code TICKET_OVERDUE} row.
 */
public record NotificationRow(
        Long id,
        NotificationType notificationType,
        Long ticketId,
        String ticketNumber,
        String ticketTitle,
        String oldValue,
        String newValue,
        Long actorId,
        String actorFullName,
        Instant createdAt,
        Instant readAt
) {
}
