package com.forward.desk_resolver.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Paging and sorting for the caller's notification inbox.
 *
 * <p>Newest first, like every list here except a comment thread: an inbox is read from the top and
 * the thing that just happened is the thing somebody opened it for.
 *
 * <p>{@code readAt} is deliberately <strong>not</strong> sortable. Sorting an inbox by when it was
 * read puts it in an order that changes as it is read, which is to say an order that is different
 * every time the same page is requested - and offset paging over a moving order skips and repeats
 * rows. Unread-first is a filter, not a sort, and {@code ?unreadOnly=true} is how it is asked for.
 *
 * <p>Same whitelist discipline as every other resource, and {@code PageRequests} appends {@code id}
 * as a tie-breaker - which matters more here than anywhere else: one assignment writes up to three
 * rows sharing a timestamp to the microsecond, so {@code createdAt} alone is not a deterministic
 * order even within a single event.
 */
public final class NotificationPageRequests {

    private static final Set<String> SORTABLE = Set.of("createdAt", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private NotificationPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
