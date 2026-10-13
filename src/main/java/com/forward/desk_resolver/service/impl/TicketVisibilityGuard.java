package com.forward.desk_resolver.service.impl;

import com.forward.desk_resolver.common.exception.ResourceNotFoundException;
import com.forward.desk_resolver.common.identity.CurrentUserProvider;
import com.forward.desk_resolver.repository.TicketRepository;
import com.forward.desk_resolver.repository.projection.TicketRow;
import com.forward.desk_resolver.security.Permission;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "may this caller see this ticket".
 *
 * <p><strong>Why it was extracted.</strong> The rule lived as a private method on
 * {@code TicketServiceImpl}, where two call sites needed it. Comments and attachments are sub-resources
 * of a ticket, and every one of their operations has to answer the same question - a ticket's comment
 * thread leaks exactly as much as the ticket does. Three services duplicating an authorization
 * predicate is how the copies drift, and the copy that drifts is the one that stops refusing.
 *
 * <p><strong>404, never 403.</strong> A 403 confirms the row exists, which hands an enumerator the
 * answer they came for. "Not visible to you" and "not there" are the same response on purpose; see
 * {@code ResourceAccessControlIT} for what that pins down.
 *
 * <p><strong>It costs no extra query.</strong> The check needs {@code raised_by} and
 * {@code assigned_to}, and {@link TicketRow} already carries both for this reason - so the row the
 * caller was going to be shown is also the row the decision is made from. Callers get that row back
 * rather than re-fetching it.
 */
@Component
@RequiredArgsConstructor
public class TicketVisibilityGuard {

    private static final Logger log = LoggerFactory.getLogger(TicketVisibilityGuard.class);

    private final TicketRepository ticketRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * @param ticketId the ticket being reached for
     * @return its row, so the caller does not pay for a second read
     * @throws ResourceNotFoundException if the ticket does not exist <em>or</em> the caller may not see
     *                                   it - deliberately indistinguishable
     */
    public TicketRow requireVisible(Long ticketId) {
        TicketRow row = ticketRepository.findTicketRowById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));
        requireVisible(row);
        return row;
    }

    /** The same rule for a row the caller already holds. */
    public void requireVisible(TicketRow row) {
        if (currentUserProvider.hasPermission(Permission.TICKET_READ_ALL)) {
            return;
        }
        Long callerId = currentUserProvider.requireCurrentUserId();
        if (!row.involves(callerId)) {
            log.info("User {} was denied access to ticket {} (not raiser or assignee)",
                    callerId, row.id());
            throw ResourceNotFoundException.of("Ticket", row.id());
        }
    }
}
