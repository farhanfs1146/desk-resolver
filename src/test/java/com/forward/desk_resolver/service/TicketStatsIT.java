package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/tickets/stats} - the dashboard's aggregate counts.
 *
 * <p>Two things are worth testing here and the second is the reason this file exists.
 *
 * <p><strong>The arithmetic.</strong> Each count excludes something on purpose - settled work from
 * the open and overdue figures, tickets with no deadline from overdue, settled work from the priority
 * breakdown - and every one of those exclusions is a line of SQL that can be dropped without anything
 * failing. A wrong number on a dashboard is believed.
 *
 * <p><strong>The authorization boundary.</strong> This endpoint returns no ticket, which is exactly
 * what makes it worth pinning down: it would be easy to treat "just counts" as harmless and leave the
 * ownership restriction off, and a requester could then read the size of the whole ticket table.
 * {@code ResourceAccessControlIT} covers the same boundary for rows; these are the counts.
 */
class TicketStatsIT extends AbstractPostgresIT {

    private static final String STATS = "/api/tickets/stats";

    /** Puts a ticket into a status and gives it a priority, which {@code givenTicket} does not. */
    private Ticket seed(User raisedBy, User assignedTo, Application application,
                        TicketStatus status, Priority priority) {
        Ticket ticket = givenTicket(raisedBy, assignedTo, application);
        ticket.setStatus(status);
        ticket.setPriority(priority);
        return ticketRepository.save(ticket);
    }

    /** A deadline in the past or the future, at the microsecond precision the column stores. */
    private Ticket withDeadline(Ticket ticket, long hoursFromNow) {
        ticket.setExpectedBy(Instant.now().plus(hoursFromNow, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS));
        return ticketRepository.save(ticket);
    }

    // ---------------------------------------------------------------- the arithmetic

    @Test
    @DisplayName("support staff see totals over every ticket, and scopedToMe says so")
    void totalsForSupportStaff() throws Exception {
        User support = givenUser("IT_SUPPORT");
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        seed(employee, null, application, TicketStatus.OPEN, Priority.HIGH);
        seed(employee, support, application, TicketStatus.IN_PROGRESS, Priority.CRITICAL);
        seed(employee, support, application, TicketStatus.RESOLVED, Priority.LOW);
        seed(employee, support, application, TicketStatus.CLOSED, Priority.LOW);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                // RESOLVED and CLOSED are settled; the other two are not.
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.scopedToMe").value(false))
                .andExpect(jsonPath("$.byStatus.OPEN").value(1))
                .andExpect(jsonPath("$.byStatus.IN_PROGRESS").value(1))
                .andExpect(jsonPath("$.byStatus.RESOLVED").value(1))
                .andExpect(jsonPath("$.byStatus.CLOSED").value(1));
    }

    /**
     * The zero-filling. {@code group by} returns only the groups that exist, so without the service
     * filling the gaps a client could not render a complete breakdown without its own copy of the enum.
     */
    @Test
    @DisplayName("every status and priority appears, including the ones no ticket holds")
    void breakdownsListEveryConstant() throws Exception {
        User support = givenUser("IT_SUPPORT");
        seed(givenUser("EMPLOYEE"), null, givenApplication(), TicketStatus.OPEN, Priority.HIGH);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.*", hasSize(TicketStatus.values().length)))
                .andExpect(jsonPath("$.byPriority.*", hasSize(Priority.values().length)))
                .andExpect(jsonPath("$.byStatus.PENDING").value(0))
                .andExpect(jsonPath("$.byPriority.LOW").value(0))
                .andExpect(jsonPath("$.byPriority.HIGH").value(1));
    }

    /**
     * Three exclusions, each of which would otherwise overstate the figure a dashboard shows in red:
     * a ticket with no deadline, a deadline still in the future, and finished work.
     */
    @Test
    @DisplayName("overdue counts only unsettled tickets whose deadline has actually passed")
    void overdueExcludesNoDeadlineFutureAndSettled() throws Exception {
        User support = givenUser("IT_SUPPORT");
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        // Counts: open, and the deadline is in the past.
        withDeadline(seed(employee, null, application, TicketStatus.OPEN, Priority.HIGH), -4);
        // Does not: no deadline to miss.
        seed(employee, null, application, TicketStatus.OPEN, Priority.HIGH);
        // Does not: the deadline has not arrived.
        withDeadline(seed(employee, null, application, TicketStatus.OPEN, Priority.HIGH), 48);
        // Does not: finished work cannot be late. Status rather than resolvedAt - a ticket can be
        // closed without passing through RESOLVED, which is why TicketStatus.settled() is the test.
        withDeadline(seed(employee, support, application, TicketStatus.CLOSED, Priority.HIGH), -72);
        withDeadline(seed(employee, support, application, TicketStatus.RESOLVED, Priority.HIGH), -72);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(1));
    }

    @Test
    @DisplayName("unassigned counts open work with no assignee, and ignores settled work")
    void unassignedCountsOpenWorkOnly() throws Exception {
        User support = givenUser("IT_SUPPORT");
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        seed(employee, null, application, TicketStatus.OPEN, Priority.MEDIUM);
        seed(employee, null, application, TicketStatus.REOPENED, Priority.MEDIUM);
        // Has an assignee.
        seed(employee, support, application, TicketStatus.IN_PROGRESS, Priority.MEDIUM);
        // Unassigned, but nobody needs to pick it up.
        seed(employee, null, application, TicketStatus.CLOSED, Priority.MEDIUM);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unassigned").value(2));
    }

    /**
     * {@code assignedToMe} is a workload figure and drops settled work; {@code raisedByMe} is a
     * "where are my requests up to" figure and keeps it. The asymmetry is deliberate, so it is pinned.
     */
    @Test
    @DisplayName("assignedToMe excludes settled work; raisedByMe keeps it")
    void mineCountsDifferAboutSettledWork() throws Exception {
        User support = givenUser("IT_SUPPORT");
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        seed(employee, support, application, TicketStatus.IN_PROGRESS, Priority.HIGH);
        seed(employee, support, application, TicketStatus.RESOLVED, Priority.HIGH);
        seed(support, support, application, TicketStatus.CLOSED, Priority.HIGH);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToMe").value(1))
                .andExpect(jsonPath("$.raisedByMe").value(1));

        mockMvc.perform(authenticated(get(STATS), tokenFor(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToMe").value(0))
                .andExpect(jsonPath("$.raisedByMe").value(2));
    }

    /** Priority describes outstanding urgency, so a pile of closed criticals must not appear in it. */
    @Test
    @DisplayName("the priority breakdown covers open work only")
    void priorityBreakdownIgnoresSettledWork() throws Exception {
        User support = givenUser("IT_SUPPORT");
        User employee = givenUser("EMPLOYEE");
        Application application = givenApplication();

        seed(employee, null, application, TicketStatus.OPEN, Priority.CRITICAL);
        seed(employee, support, application, TicketStatus.CLOSED, Priority.CRITICAL);
        seed(employee, support, application, TicketStatus.RESOLVED, Priority.CRITICAL);

        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byPriority.CRITICAL").value(1))
                // The status breakdown, by contrast, counts all three.
                .andExpect(jsonPath("$.total").value(3));
    }

    // ---------------------------------------------------------------- the authorization boundary

    /**
     * The collection half of the IDOR boundary, as a count.
     *
     * <p>Six tickets exist and the requester is involved in two. Were the restriction missing, the
     * total below would read 6 - which is how a requester would learn the size of the ticket table
     * from a screen meant to show them their own requests.
     */
    @Test
    @DisplayName("a requester's counts cover only their own tickets, and scopedToMe says so")
    void requesterSeesOnlyTheirOwnCounts() throws Exception {
        User employee = givenUser("EMPLOYEE");
        User stranger = givenUser("EMPLOYEE");
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();

        // Theirs: one they raised, one assigned to them.
        seed(employee, null, application, TicketStatus.OPEN, Priority.HIGH);
        seed(stranger, employee, application, TicketStatus.IN_PROGRESS, Priority.LOW);
        // Not theirs.
        seed(stranger, null, application, TicketStatus.OPEN, Priority.CRITICAL);
        seed(stranger, support, application, TicketStatus.IN_PROGRESS, Priority.CRITICAL);
        seed(stranger, support, application, TicketStatus.OPEN, Priority.CRITICAL);
        seed(stranger, support, application, TicketStatus.CLOSED, Priority.CRITICAL);

        mockMvc.perform(authenticated(get(STATS), tokenFor(employee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.scopedToMe").value(true))
                // The four tickets they are not involved in are invisible to every figure, the
                // breakdowns included.
                .andExpect(jsonPath("$.byPriority.CRITICAL").value(0))
                .andExpect(jsonPath("$.byStatus.CLOSED").value(0));

        // Same database, different caller: the numbers the restriction is hiding.
        mockMvc.perform(authenticated(get(STATS), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6));
    }

    @Test
    @DisplayName("no token is a 401")
    void anonymousIsUnauthorised() throws Exception {
        mockMvc.perform(get(STATS)).andExpect(status().isUnauthorized());
    }

    /**
     * A user holding no role holds no permission, so the endpoint is a 403 rather than an empty set of
     * counts. Returning zeroes would say "you have no tickets" to somebody who may not ask.
     */
    @Test
    @DisplayName("a caller without a ticket-read permission is forbidden")
    void rolelessCallerIsForbidden() throws Exception {
        mockMvc.perform(authenticated(get(STATS), tokenFor(givenUser())))
                .andExpect(status().isForbidden());
    }

    /**
     * {@code /stats} must not be swallowed by {@code /{id}}. Spring prefers the literal pattern over
     * the template, so this passes - but the two mappings are one file apart, and a future rename
     * could silently turn this endpoint into a request for ticket number "stats", whose 400 would look
     * like a client bug.
     */
    @Test
    @DisplayName("the literal /stats path wins over the /{id} template")
    void statsPathIsNotParsedAsATicketId() throws Exception {
        mockMvc.perform(authenticated(get(STATS), tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists());
    }
}
