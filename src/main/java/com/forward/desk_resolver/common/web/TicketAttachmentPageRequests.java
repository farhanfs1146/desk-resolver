package com.forward.desk_resolver.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Paging and sorting for a ticket's attachments.
 *
 * <p>Newest first, like tickets and history: the file somebody just added is the one being looked for.
 *
 * <p>Paged rather than returned whole, even though most tickets have few attachments. The alternative
 * - unpaged, as {@code GET /api/roles} is - is only defensible for a bounded collection, and nothing
 * bounds how many files a long-running ticket accumulates. An endpoint whose response size depends on
 * user behaviour is paged.
 */
public final class TicketAttachmentPageRequests {

    private static final Set<String> SORTABLE = Set.of("uploadedAt", "fileName", "sizeBytes", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("uploadedAt"), Sort.Order.desc("id"));

    private TicketAttachmentPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
