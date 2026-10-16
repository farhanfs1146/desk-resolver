package com.forward.desk_resolver.repository;

import com.forward.desk_resolver.dto.request.TicketFilter;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.repository.projection.TicketCountRow;
import com.forward.desk_resolver.repository.projection.TicketRow;
import com.forward.desk_resolver.repository.projection.TicketStatsRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

/**
 * Hand-built read queries for the ticket list.
 *
 * <p>This exists because of a measured problem with the obvious alternative. A single static JPQL
 * query using the familiar {@code (:param is null or column = :param)} idiom produces one SQL
 * statement for every filter combination, which is convenient - but the resulting SQL is not
 * plan-stable. Measured against 20,000 tickets:
 *
 * <pre>
 *   custom plan  (parameters known)   Index Scan using idx_tickets_created_at_id        145 buffers
 *   generic plan (parameters opaque)  Seq Scan + Hash joins + top-N heapsort         86,026 buffers
 * </pre>
 *
 * <p>PostgreSQL's default {@code plan_cache_mode = auto} keeps choosing the custom plan here, because
 * the generic estimate is much more expensive - so the cliff is not reached today. But that safety
 * rests entirely on cost estimates staying that way as data grows, and the PostgreSQL JDBC driver
 * switches to server-side prepared statements after five executions of a statement, which is what
 * makes the generic plan reachable at all. Depending on an estimate to avoid a 590x regression is not
 * a property worth shipping.
 *
 * <p>Building the predicates dynamically removes the question: each filter combination produces SQL
 * that mentions only the filters actually supplied, so there is no opaque parameter for the planner to
 * guess around and every plan is the custom plan.
 */
public interface TicketQueryRepository {

    /**
     * One page of tickets, projected to exactly the columns the response needs.
     *
     * <p><strong>The free-text filter is the one unindexed predicate here, and that is a decision
     * rather than an oversight.</strong> {@code TicketFilter#q} becomes
     * {@code lower(col) LIKE '%term%'} over three columns. A B-tree cannot serve it - a leading
     * wildcard leaves nothing to seek on - so a search reads the rows it filters. At the volumes this
     * application has seen that is the same full read the unfiltered {@code count} already does, so
     * there was nothing to gain by pre-empting it.
     *
     * <p>Every index in V12 earned its place by being measured, and V12's fifth candidate was
     * discarded the same way: built, chosen by the planner zero times, deleted. There is no equivalent
     * measurement for a search index here - no production table, and a seeded one would be answering a
     * question about invented data. Rather than guess, the recipe:
     *
     * <ol>
     *   <li>Find the real shape: how many rows, and how often is {@code q} actually sent? A filter
     *       used twice a day does not justify an index maintained on every write.
     *   <li>{@code EXPLAIN (ANALYZE, BUFFERS)} a representative search. A Seq Scan is only a problem
     *       when its buffer count is one, and the latency is one somebody waits on.
     *   <li>If it is: {@code CREATE EXTENSION pg_trgm}, then a GIN index per searched column -
     *       {@code CREATE INDEX ... USING gin (lower(title) gin_trgm_ops)} and the same for
     *       {@code description}. {@code ticket_number} needs none; it is already unique-indexed, and an
     *       exact-prefix search on it can be made to use that index instead.
     *   <li>Measure again, and check {@code pg_stat_user_indexes.idx_scan} is non-zero after real
     *       traffic. If it is zero, drop it - that is precisely what happened to
     *       {@code idx_tickets_priority_created_at}.
     * </ol>
     *
     * <p>Note that trigram indexes need a term of at least three characters to be useful, so a short
     * search would fall back to a scan regardless. If word matching rather than substring matching
     * turns out to be what people want, a {@code tsvector} column is the different and better answer -
     * and that is a product question, not a tuning one.
     *
     * @param filter   optional filters; any {@code null} field is omitted from the SQL entirely
     * @param pageable page, size and sort; sort properties are validated before reaching here
     */
    Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable);

    /**
     * As above, but restricted to tickets the given user raised or is assigned to.
     *
     * <p>This is an authorization boundary applied by the service for callers without
     * {@code TICKET_READ_ALL}. It is ANDed with the caller's own filters, so it can only narrow a
     * result set, never widen one.
     */
    Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable, Long restrictToUserId);

    /**
     * The four scalar counts behind the dashboard, in one query.
     *
     * <p>Unlike the list queries above there are no optional filters here, so the plan-stability
     * problem this interface exists for does not arise: the SQL is the same shape every time apart
     * from the authorization predicate, which is present or absent rather than parameterised.
     *
     * @param now               the instant "overdue" is measured against. Passed in rather than read
     *                          here so a test can choose it, the same way {@code findOverdue} does.
     * @param currentUserId     the caller, for the two "mine" counts. Required - these counts are
     *                          about a person, and there is no sensible value for "nobody".
     * @param restrictToUserId  when non-null, every count is restricted to tickets this user raised
     *                          or is assigned to. The same authorization boundary
     *                          {@link #findTicketRows(TicketFilter, Pageable, Long)} applies, and it
     *                          matters more here than there: an unrestricted count tells a requester
     *                          how many tickets exist in total, which is information the list
     *                          endpoint would never have given them.
     */
    TicketStatsRow findTicketStats(Instant now, Long currentUserId, Long restrictToUserId);

    /**
     * How many tickets hold each status, for the rows the caller may see.
     *
     * <p>Only statuses that occur are returned - see {@link TicketCountRow}. The total and the
     * open count are both derived from this list by the service rather than queried again, because
     * summing eight numbers in Java is cheaper than two more scans and cannot disagree with the
     * breakdown it is shown next to.
     *
     * @param restrictToUserId the same authorization boundary as {@link #findTicketStats}
     */
    List<TicketCountRow<TicketStatus>> countTicketsByStatus(Long restrictToUserId);

    /**
     * How many tickets hold each priority, for the rows the caller may see.
     *
     * <p>Counts <strong>unsettled</strong> tickets only, which is the one place this breakdown
     * differs from the status one above. Priority describes how urgent outstanding work is; a year of
     * closed criticals would dominate the chart and say nothing about today.
     *
     * @param restrictToUserId the same authorization boundary as {@link #findTicketStats}
     */
    List<TicketCountRow<Priority>> countOpenTicketsByPriority(Long restrictToUserId);
}
