package com.forward.desk_resolver.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Paging and sorting for a ticket's comment thread.
 *
 * <p><strong>The default sort is ascending</strong>, which makes this the one list in the application
 * that does not read newest-first. Tickets, history entries and attachments are collections you scan
 * for the latest thing; a thread is a conversation, and a reply above the message it answers is
 * unreadable. Page 0 being the start of the discussion is also what lets a client append to the end as
 * it loads more.
 *
 * <p>Same whitelist discipline as every other resource: only {@code createdAt} and {@code id} are
 * sortable, and {@code PageRequests} appends {@code id} as a tie-breaker so two comments written in the
 * same microsecond cannot swap places between pages.
 */
public final class TicketCommentPageRequests {

    private static final Set<String> SORTABLE = Set.of("createdAt", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private TicketCommentPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
