package com.forward.desk_resolver.dto.request;

import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;

/**
 * Optional filters for the ticket list. Every field may be {@code null}, meaning "do not filter".
 *
 * <p>The set is deliberately small, and each entry is backed by measured evidence rather than by
 * guessing at what might be useful:
 *
 * <ul>
 *   <li>{@code status} - the core support-queue question ("what is still open"); its filtered
 *       {@code count} becomes an index-only scan via {@code idx_tickets_status_created_at}.
 *   <li>{@code priority} - triage. Served by the {@code created_at} index at these volumes; a
 *       dedicated priority index was built, measured, used zero times, and discarded.
 *   <li>{@code assignedToUserId} - an agent's own queue; uses
 *       {@code idx_tickets_assigned_to_created_at}.
 *   <li>{@code raisedByUserId} - "tickets I reported"; uses {@code idx_tickets_raised_by_created_at}.
 *   <li>{@code q} - free text. The one filter here that is not served by an index, deliberately; see
 *       below.
 * </ul>
 *
 * <p>No date-range or application filter is offered, because nothing in the application requires one
 * yet and an unused filter would only invite an unused index. V12 records the index that was built
 * for a filter, measured, chosen zero times and discarded.
 *
 * <p><strong>{@code q} and the missing index.</strong> A substring match cannot use a B-tree index at
 * all - {@code lower(col) LIKE '%term%'} has no left anchor - so this filter reads the rows it
 * filters. The fix would be a trigram index ({@code pg_trgm} plus a GIN index per searched column), or
 * a {@code tsvector} column if word-stem matching were wanted instead of substrings. Neither is
 * created, for the reason the rest of this list exists: V12's indexes were kept because the planner
 * chose them and the buffer count dropped, and its fifth candidate was discarded because it was not
 * chosen. No such measurement is available for this one, and a 300 kB GIN index maintained on every
 * ticket write is not something to add on the strength of an expectation. The recipe for deciding it
 * is written down in {@code TicketQueryRepository#findTicketRows}, so whoever has the real table can
 * settle it with numbers rather than re-argue it.
 */
public record TicketFilter(
        TicketStatus status,
        Priority priority,
        Long assignedToUserId,
        Long raisedByUserId,
        String q
) {

    /**
     * Normalises {@code q} so that "no search" has exactly one representation.
     *
     * <p>{@code ?q=} and {@code ?q=%20%20} both arrive as a present-but-empty string, and a UI with a
     * search box produces them constantly - clearing the field, or submitting it untouched. Were they
     * passed through, every one of them would add {@code LIKE '%%'} to the query: three predicates and
     * a full scan to match every row, which the absent filter already does for free. Doing this in the
     * record rather than at the controller means it holds for every caller, including the tests.
     *
     * <p>Not length-capped. The container bounds the request line long before a {@code LIKE} pattern
     * becomes the expensive part, and silently truncating somebody's search term would return results
     * that do not match what they typed.
     */
    public TicketFilter {
        q = q == null || q.isBlank() ? null : q.strip();
    }

    public static TicketFilter none() {
        return new TicketFilter(null, null, null, null, null);
    }
}
