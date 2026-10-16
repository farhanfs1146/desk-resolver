package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/tickets?q=} - the free-text filter.
 *
 * <p>Three groups of tests, and the middle group is the reason this file is longer than the feature.
 *
 * <p><strong>What it matches.</strong> Three columns, case-insensitively, as a substring. Each of
 * those is one clause that can be dropped, or written as an equality, without anything failing - the
 * endpoint would simply find less than it claims to, which is the kind of defect a user reports as
 * "search is broken" and a developer cannot reproduce.
 *
 * <p><strong>What it refuses to treat as a pattern.</strong> {@code LIKE} has two wildcards, and a
 * search box hands them straight to it. Unescaped, a lone percent sign returns every ticket - which
 * reads as the filter being ignored - and an underscore makes a ticket-number search match the wrong
 * tickets quietly. These are the tests that would fail if the escaping were ever removed as
 * redundant; nothing else here would.
 *
 * <p><strong>What it must not widen.</strong> A search is ANDed with the other filters and with the
 * ownership restriction. Were it ORed, or applied in memory after the page had been fetched, a
 * requester could search their way to other people's tickets - so the boundary is asserted here as
 * well as in {@code ResourceAccessControlIT}.
 */
class TicketSearchIT extends AbstractPostgresIT {

    private static final String TICKETS = "/api/tickets";

    /** A ticket with a known number, title and description; {@code givenTicket} fixes all three. */
    private Ticket seed(User raisedBy, User assignedTo, Application application,
                        String ticketNumber, String title, String description) {
        Ticket ticket = givenTicket(raisedBy, assignedTo, application);
        ticket.setTicketNumber(ticketNumber);
        ticket.setTitle(title);
        ticket.setDescription(description);
        return ticketRepository.save(ticket);
    }

    // ---------------------------------------------------------------- what it matches

    @Test
    @DisplayName("matches a substring of the title, whatever case either side is in")
    void matchesTitleIgnoringCase() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Payslip PDF will not open", "Acrobat error");
        seed(employee, null, application, "TKT-3002", "Leave balance wrong", "Shows 4, should be 9");

        // Mixed case on both sides - "PaySLIP" against "Payslip" - to catch a comparison that folds
        // only one of the two.
        mockMvc.perform(authenticated(get(TICKETS).param("q", "PaySLIP"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));
    }

    @Test
    @DisplayName("matches a ticket number, including a fragment of one")
    void matchesTicketNumber() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Payslip PDF will not open", "Acrobat error");
        seed(employee, null, application, "TKT-9100", "Leave balance wrong", "Shows 4, should be 9");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "tkt-3001"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));

        // A fragment, which is the useful case: somebody remembers the tail of a reference.
        mockMvc.perform(authenticated(get(TICKETS).param("q", "9100"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-9100"));
    }

    /**
     * The description is searched too. It is the column people actually put the detail in, and it is
     * also the only one of the three mapped as {@code TEXT} rather than {@code varchar} - so a mapping
     * change that broke it would break it alone.
     */
    @Test
    @DisplayName("matches text that appears only in the description")
    void matchesDescription() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Cannot log in", "Getting a 403 from the gateway");
        seed(employee, null, application, "TKT-3002", "Cannot log in", "Password reset loops");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "gateway"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));
    }

    @Test
    @DisplayName("a term that appears nowhere returns an empty page, not everything")
    void unmatchedTermReturnsNothing() throws Exception {
        User support = givenUser("IT_SUPPORT");
        seed(givenUser("EMPLOYEE"), null, givenApplication(),
                "TKT-3001", "Payslip PDF will not open", "Acrobat error");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "chandelier"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * Both forms a search box produces when nobody is searching. A UI that keeps {@code q} in the URL
     * submits an empty one every time the field is cleared, and that has to mean the unfiltered list -
     * not a pattern of two bare wildcards, and certainly not an empty page.
     */
    @Test
    @DisplayName("an empty or whitespace-only q is no filter at all")
    void blankTermIsNoFilter() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Payslip PDF will not open", "Acrobat error");
        seed(employee, null, application, "TKT-3002", "Leave balance wrong", "Shows 4, should be 9");

        mockMvc.perform(authenticated(get(TICKETS).param("q", ""), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"));

        mockMvc.perform(authenticated(get(TICKETS).param("q", "   "), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"));
    }

    /** Surrounding whitespace is a typing artefact, not part of what somebody meant to search for. */
    @Test
    @DisplayName("a term is stripped before it is matched")
    void termIsStripped() throws Exception {
        User support = givenUser("IT_SUPPORT");
        seed(givenUser("EMPLOYEE"), null, givenApplication(),
                "TKT-3001", "Payslip PDF will not open", "Acrobat error");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "  payslip  "), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------------------------------------------------------------- wildcards are not operators

    /**
     * The test that fails if the {@code ESCAPE} clause is ever dropped as redundant. Unescaped, a lone
     * percent sign is a pattern that matches every row, and the only visible symptom is a search box
     * that appears to do nothing.
     */
    @Test
    @DisplayName("a percent sign is matched literally, not as match-everything")
    void percentIsNotAWildcard() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Disk 90% full on the file server", "Alerting");
        seed(employee, null, application, "TKT-3002", "Leave balance wrong", "Shows 4, should be 9");
        seed(employee, null, application, "TKT-3003", "Payslip PDF will not open", "Acrobat error");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "90%"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));

        // The worst case: on its own it would otherwise return the entire table.
        mockMvc.perform(authenticated(get(TICKETS).param("q", "%"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));
    }

    /**
     * The quieter half of the same defect. An underscore is LIKE's single-character wildcard, so
     * searching for one ticket number would match a different ticket whose number merely has that
     * shape - results that are not what was typed, which is worse than no results.
     */
    @Test
    @DisplayName("an underscore is matched literally, not as any-single-character")
    void underscoreIsNotAWildcard() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Payslip PDF will not open", "Acrobat error");
        seed(employee, null, application, "TKT_3001", "A different ticket entirely", "Unrelated");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "TKT_3001"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].title").value("A different ticket entirely"));
    }

    /**
     * A backslash is the escape character itself. Left alone it would escape whatever followed it, so
     * the term would stop matching its own text - and at the end of a pattern it is a dangling escape,
     * which PostgreSQL rejects outright.
     */
    @Test
    @DisplayName("a backslash is matched literally")
    void backslashIsMatchedLiterally() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        seed(employee, null, application, "TKT-3001", "Cannot reach the share",
                "Path is C:\\Users\\sales");
        seed(employee, null, application, "TKT-3002", "Leave balance wrong", "Shows 4, should be 9");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "C:\\Users"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].ticketNumber").value("TKT-3001"));

        // Trailing: the dangling-escape case, which errors rather than returning the wrong rows.
        mockMvc.perform(authenticated(get(TICKETS).param("q", "sales\\"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }

    // ---------------------------------------------------------------- it narrows, never widens

    @Test
    @DisplayName("a search is ANDed with the other filters")
    void searchCombinesWithStatusFilter() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        Ticket open = seed(employee, null, application, "TKT-3001", "Payslip wrong", "March");
        Ticket closed = seed(employee, null, application, "TKT-3002", "Payslip wrong", "February");
        closed.setStatus(TicketStatus.CLOSED);
        ticketRepository.save(closed);

        // Both tickets match the term; one matches the status. An OR here would return two.
        mockMvc.perform(authenticated(get(TICKETS)
                        .param("q", "payslip")
                        .param("status", "OPEN"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].id").value(open.getId()));
    }

    /**
     * The boundary. Six tickets mention the term; the requester is involved in two of them, so a
     * search finds two - and {@code X-Total-Count} says two, because the restriction is in the SQL
     * rather than applied to the page after it was read.
     */
    @Test
    @DisplayName("a requester's search cannot reach tickets they are not involved in")
    void searchRespectsOwnership() throws Exception {
        User requester = givenUser("EMPLOYEE");
        User other = givenUser("EMPLOYEE");
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();

        seed(requester, null, application, "TKT-4001", "Payslip wrong", "raised by the requester");
        seed(other, requester, application, "TKT-4002", "Payslip wrong", "assigned to the requester");
        seed(other, null, application, "TKT-4003", "Payslip wrong", "neither");
        seed(other, other, application, "TKT-4004", "Payslip wrong", "neither");
        seed(other, support, application, "TKT-4005", "Payslip wrong", "neither");
        seed(other, support, application, "TKT-4006", "Payslip wrong", "neither");

        mockMvc.perform(authenticated(get(TICKETS).param("q", "payslip"), tokenFor(requester)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$", hasSize(2)));

        // The same search as support staff, to show the term matches all six - so the two above are
        // the restriction working, not the term failing to match.
        mockMvc.perform(authenticated(get(TICKETS).param("q", "payslip"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "6"));
    }

    /**
     * Paging is over the matches, not over the table. The count has to come from the same filtered
     * query as the rows; a count that ignored {@code q} would render as a pager whose later pages are
     * empty when visited.
     */
    @Test
    @DisplayName("paging metadata describes the matches")
    void pagingDescribesTheMatches() throws Exception {
        User support = givenUser("IT_SUPPORT");
        Application application = givenApplication();
        User employee = givenUser("EMPLOYEE");

        for (int index = 1; index <= 5; index++) {
            seed(employee, null, application, "TKT-50" + index, "Payslip wrong", "month " + index);
        }
        seed(employee, null, application, "TKT-5099", "Printer offline", "third floor");

        mockMvc.perform(authenticated(get(TICKETS)
                        .param("q", "payslip")
                        .param("size", "2"), tokenFor(support)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(header().string("X-Total-Pages", "3"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
