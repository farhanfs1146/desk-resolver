package com.forward.desk_resolver.repository.projection;

/**
 * The scalar half of the ticket statistics: the four counts that are not a {@code group by}.
 *
 * <p>All four come from <strong>one</strong> query, using conditional aggregation
 * ({@code count(case when ... then 1 end)}) rather than four separate counts. The query already has
 * to scan the caller's visible tickets once; asking four times would scan it four times to answer
 * questions about the same rows.
 *
 * <p>Each field is deliberately scoped to the rows the caller may see - the same authorization
 * boundary the ticket list applies - so for a requester these are counts over their own involvement,
 * and for support staff they are counts over everything. See
 * {@code TicketQueryRepository#findTicketStats}.
 *
 * @param overdue      past {@code expected_by} and not settled. A ticket with no deadline cannot be
 *                     late, and finished work cannot be either.
 * @param unassigned   no assignee and not settled. Open work nobody owns, which is the one number a
 *                     triage queue is read for.
 * @param assignedToMe not settled and assigned to the caller - their own queue.
 * @param raisedByMe   raised by the caller, settled or not. Unlike the three above this is not a
 *                     workload figure but a "where are my requests up to" figure, so closing one
 *                     should not make it vanish from the total.
 */
public record TicketStatsRow(
        long overdue,
        long unassigned,
        long assignedToMe,
        long raisedByMe
) {
}
