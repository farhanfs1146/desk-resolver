package com.forward.desk_resolver.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum TicketStatus {
    OPEN,
    UNDER_REVIEW,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    REOPENED,
    PENDING;

    /**
     * Work that is finished, whatever its deadline says.
     *
     * <p>Status rather than {@code resolvedAt}: a ticket can be closed without having passed through
     * RESOLVED, so a timestamp check would count closed work as still open.
     *
     * <p><strong>Why this lives on the enum.</strong> It was a private constant inside
     * {@code OverdueTicketScanner}, which was the only thing that needed it. The ticket statistics
     * endpoint needs the same set to answer "how many are still open" and "how many are overdue", and
     * a second copy is a definition that eventually disagrees with the first - with the half that is
     * wrong being the half nobody notices. One copy, on the type the question is about.
     *
     * <p>This is <strong>not</strong> the lifecycle decision. Which transitions are legal is still the
     * open business question recorded in docs/DECISIONS.md, and nothing here implies an order. It only
     * names the two states that mean "nobody is working on this any more", which holds under any
     * workflow that question is eventually settled with.
     */
    private static final Set<TicketStatus> SETTLED =
            Collections.unmodifiableSet(EnumSet.of(RESOLVED, CLOSED));

    /** The settled statuses, as an immutable set suitable for an {@code in} clause. */
    public static Set<TicketStatus> settled() {
        return SETTLED;
    }

    /** True when a ticket in this status is finished work. */
    public boolean isSettled() {
        return SETTLED.contains(this);
    }
}
