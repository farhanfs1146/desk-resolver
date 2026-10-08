package com.forward.desk_resolver.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Ticket-specific pagination rules.
 *
 * <p>The mechanics live in {@link PageRequests}; this holds only what is specific to tickets - which
 * properties may be sorted on, and the default order.
 *
 * <p>It used to re-export {@code PageRequests.DEFAULT_PAGE_SIZE}, {@code MAX_PAGE_SIZE} and
 * {@code headers(...)} as well. No other {@code *PageRequests} did, so {@code TicketController} reached
 * the same helper two different ways in two adjacent methods. The aliases are gone: shared mechanics are
 * called on {@link PageRequests} directly.
 */
public final class TicketPageRequests {

    /** Backed by indexes from V12 where it matters; that migration records which and why. */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "updatedAt", "priority", "status", "ticketNumber", "id");

    /** Newest first: what a support queue wants, and what idx_tickets_created_at_id serves. */
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private TicketPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
