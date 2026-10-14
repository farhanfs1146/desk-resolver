package com.forward.desk_resolver.repository.projection;

import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;

import java.time.Instant;

/**
 * Flat read projection for ticket queries - exactly the columns {@code TicketResponse} needs and
 * nothing else.
 *
 * <p><strong>Why a projection rather than entities.</strong> The list endpoint used to call
 * {@code findAll()}, which loads {@code Ticket} entities whose three {@code @ManyToOne} associations
 * are {@code EAGER}. Spring Data's {@code findAll} issues {@code select t from Ticket t}, and
 * Hibernate resolves eager to-one associations in HQL results with follow-up selects rather than a
 * join - producing one extra query per distinct referenced user and application. Measured worst case:
 * 200 tickets to 401 statements.
 *
 * <p>Selecting into this record makes Hibernate emit a single query with explicit joins, and loads
 * <em>no managed entities at all</em>: nothing enters the persistence context, nothing is
 * dirty-checked, and only the three name columns actually rendered in the response are read from the
 * associated tables.
 *
 * <p>It is a separate type from {@code TicketResponse} on purpose. Binding JPQL constructor
 * expressions straight to the response DTO would couple the query to that DTO's field order, so
 * reordering a field in the API model would silently shuffle values between columns. Mapping through
 * this record costs one allocation per row and makes such a mistake a compile error instead.
 */
public record TicketRow(
        Long id,
        String ticketNumber,
        String title,
        String description,
        IssueType issueType,
        Priority priority,
        TicketStatus status,
        String businessImpact,
        Instant expectedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        String raisedByFullName,
        String assignedToFullName,
        String applicationName,
        String moduleName,

        /**
         * Identifiers of the records this row names, carried for two purposes at once.
         *
         * <p><strong>Authorization.</strong> Resource-level checks need to know who raised a ticket and
         * who it is assigned to. Reading these from the same projection keeps the Phase 3 guarantee
         * intact - the check costs no additional query, because the ids travel with the row that was
         * being fetched anyway. That is why the two user ids were here before anything rendered them.
         *
         * <p><strong>The response.</strong> They are now mapped into {@code TicketResponse} as well,
         * alongside the names. The javadoc here used to state the opposite - "neither field is mapped
         * into {@code TicketResponse}, so neither changes the API contract" - which was true and was
         * the problem: a client held a non-unique display name and no key, so it could neither tell two
         * identically named people apart nor call any endpoint about either of them. See
         * {@code TicketResponse} for why that is a defect rather than a minimal API.
         *
         * <p>{@code applicationId} joined them for the same reason and at the same cost.
         * {@code applications.app_name} is deliberately non-unique - {@code ApplicationPageRequests}
         * appends {@code id} as a sort tiebreaker because of it - so the name alone cannot identify the
         * application a ticket was raised against.
         *
         * <p>All three are foreign-key columns on {@code tickets} itself. Hibernate renders an
         * association id on the root as the FK column, so reading them adds no join to either query -
         * the same property the filter predicates in {@code TicketQueryRepositoryImpl} rely on.
         */
        Long raisedByUserId,
        Long assignedToUserId,
        Long applicationId
) {

    /** Whether the given user raised this ticket or is its assignee. */
    public boolean involves(Long userId) {
        if (userId == null) {
            return false;
        }
        return userId.equals(raisedByUserId) || userId.equals(assignedToUserId);
    }
}
