package com.forward.desk_resolver.repository;


import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.repository.projection.TicketRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketQueryRepository {

    /**
     * Draws the next value from {@code ticket_number_seq} (audit finding P0-5).
     *
     * <p>{@code nextval} is atomic and never hands the same value to two callers, whatever the
     * concurrency. It is also intentionally non-transactional: if the surrounding ticket creation
     * rolls back, the number is simply not reused. Gaps are acceptable; duplicates are not.
     */
    @Query(value = "SELECT nextval('ticket_number_seq')", nativeQuery = true)
    long nextTicketNumberValue();

    /**
     * Single-ticket read, projected for the same reasons as the list query.
     *
     * <p>Loading the entity here already cost only one statement, because Hibernate's entity loader
     * joins eager to-one associations. The projection is still preferable: it keeps the response path
     * free of managed entities and dirty checking, and reads only the columns the response renders.
     *
     * <p>This one stays as static JPQL - it has no optional filters, so there is no plan-stability
     * concern of the kind that drove the list query into {@link TicketQueryRepositoryImpl}.
     */
    @Query("""
            select new com.forward.desk_resolver.repository.projection.TicketRow(
                t.id, t.ticketNumber, t.title, t.description, t.issueType, t.priority, t.status,
                t.businessImpact, t.expectedBy, t.createdAt, t.updatedAt, t.resolvedAt,
                raiser.fullName, assignee.fullName, app.appName, t.moduleName,
                raiser.id, assignee.id, app.id)
            from Ticket t
                join t.raisedBy raiser
                left join t.assignedTo assignee
                join t.application app
            where t.id = :id
            """)
    Optional<TicketRow> findTicketRowById(@Param("id") Long id);

    /**
     * Tickets whose deadline has passed and which nobody has finished.
     *
     * <p>Read by {@code OverdueTicketScanner}, and it returns <strong>entities</strong> rather than
     * the projection every other read here uses. That is a deliberate exception, and it costs
     * nothing: the scanner hands each ticket to {@code NotificationFanOut}, which wants the raiser's
     * and assignee's ids and the ticket's number and title. The two names are on this row, and the
     * two ids are the foreign keys of LAZY to-one associations - so reading them initialises no
     * proxy and issues no further statement. A projection would carry the same five fields and then
     * have to be mapped back into something the fan-out could take.
     *
     * <p>Three conditions, and each excludes something that would otherwise be announced wrongly:
     *
     * <ul>
     *   <li>{@code expectedBy is not null} - a ticket with no deadline cannot miss one. Most have
     *       none; the field is optional on the create form.
     *   <li>{@code expectedBy < :now} - the deadline is in the past.
     *   <li>{@code status not in (:settled)} - resolved and closed work is finished. Telling somebody
     *       their completed ticket is late would be wrong rather than merely noisy, and
     *       {@code resolved_at} is not the test: a ticket can be closed without passing through
     *       RESOLVED.
     * </ul>
     *
     * <p>Oldest deadline first, so that a {@code Pageable}-capped scan works through the worst
     * backlog before the rest of it - see the scanner for why it is capped at all.
     */
    @Query("""
            select t
            from Ticket t
            where t.expectedBy is not null
              and t.expectedBy < :now
              and t.status not in :settled
            order by t.expectedBy asc, t.id asc
            """)
    List<Ticket> findOverdue(@Param("now") Instant now,
                             @Param("settled") Collection<TicketStatus> settled,
                             Pageable pageable);
}
