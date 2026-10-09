package com.forward.desk_resolver.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * User-specific pagination rules (Phase 6).
 *
 * <p>Sortable properties are limited to the ones a directory is plausibly ordered by, and all of them are
 * columns on {@code auth.users} - nothing here can produce a join or an expression sort.
 *
 * <p>{@code role} was one of them until Phase 7 and is deliberately not replaced. A user can hold
 * several roles now, so there is no column to order by: "sort by role" would have to mean primary
 * role, or alphabetically-first role, or something else again, and nothing has decided which. An
 * unknown sort property is already a 400, so a client that still sends it is told rather than quietly
 * given a different order than it asked for.
 *
 * <p>The default is name order, because this endpoint exists so support staff can find a person, and
 * "alphabetical" is what a human expects of a directory. {@code id} is appended as a tiebreaker, since
 * names are not unique and offset pagination needs a total order to avoid skipping or repeating rows.
 */
public final class UserPageRequests {

    private static final Set<String> SORTABLE = Set.of(
            "fullName", "email", "employeeCode", "active", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("fullName"), Sort.Order.asc("id"));

    private UserPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
