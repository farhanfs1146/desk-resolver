package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket comments: the feature {@code V4} created a table for and nothing used until {@code V19}.
 *
 * <p>Two things are worth testing here and the second is the one that matters. The first is ordinary -
 * a comment can be written and read back. The second is that the permission and the row rule are
 * independent: {@code TICKET_COMMENT_READ} says a caller may use these endpoints, and involvement in
 * the ticket says <em>whose</em> threads they reach. Every role holds the comment permissions, so if
 * the row rule were missing, the permission would read as though it granted access to every thread in
 * the system.
 */
class TicketCommentIT extends AbstractPostgresIT {

    private static final String BODY = """
            {"comment":"%s"}""";

    private MvcResult comment(long ticketId, String token, String text) throws Exception {
        return mockMvc.perform(authenticated(post("/api/tickets/" + ticketId + "/comments"), token)
                        .content(BODY.formatted(text)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    // ---------------------------------------------------------------- writing

    @Test
    @DisplayName("the raiser can comment on their own ticket, and the author is taken from the token")
    void theRaiserCanComment() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        MvcResult created = comment(ticket.getId(), tokenFor(raiser), "Still happening this morning.");

        assertThat(this.<Integer>read(created, "$.id")).isNotNull();
        assertThat(this.<String>read(created, "$.comment")).isEqualTo("Still happening this morning.");
        // Not supplied in the body and not suppliable: the author is the authenticated caller.
        assertThat(this.<String>read(created, "$.commentedBy")).isEqualTo(raiser.getFullName());
        assertThat(this.<String>read(created, "$.createdAt")).isNotBlank();
    }

    /**
     * The exchange the feature exists for: a requester and an agent on one thread.
     *
     * <p>This is also what makes the wide permission grant in {@code V19} the right call - a thread
     * only requester-side or only support-side would not be a conversation.
     */
    @Test
    @DisplayName("a requester and support staff can hold a conversation on one ticket")
    void bothSidesCanComment() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        User agent = givenUser("IT_SUPPORT");
        Ticket ticket = givenTicket(raiser, agent, givenApplication());

        comment(ticket.getId(), tokenFor(raiser), "Which log should I send?");
        comment(ticket.getId(), tokenFor(agent), "The payroll service log for 03 March.");

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(raiser)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].commentedBy").value(raiser.getFullName()))
                .andExpect(jsonPath("$[1].commentedBy").value(agent.getFullName()));
    }

    /**
     * Commenting leaves the ticket untouched - no status change, no {@code updated_at} bump, and
     * nothing in the audit trail.
     *
     * <p>This is the property that lets requesters comment at all. If a comment were a workflow event
     * it would be a state change in disguise, and granting it to the people who raise tickets would
     * hand them the thing {@code TICKET_STATUS_CHANGE} is withheld for.
     */
    @Test
    @DisplayName("a comment changes nothing about the ticket and writes no audit row")
    void commentingDoesNotTouchTheTicket() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);

        MvcResult before = mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), token))
                .andExpect(status().isOk())
                .andReturn();

        comment(ticket.getId(), token, "Adding detail.");

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(this.<String>read(before, "$.status")))
                .andExpect(jsonPath("$.updatedAt").value(this.<String>read(before, "$.updatedAt")));

        // The audit trail is for changes to the ticket. The thread is its own record.
        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/history"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }

    @Test
    @DisplayName("a blank comment is a 400 naming the field")
    void aBlankCommentIsRejected() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        mockMvc.perform(authenticated(post("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(raiser))
                        .content(BODY.formatted("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.comment").exists());
    }

    @Test
    @DisplayName("a comment over 4000 characters is a 400 rather than a database error")
    void anOverlongCommentIsRejected() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        mockMvc.perform(authenticated(post("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(raiser))
                        .content(BODY.formatted("x".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.comment").exists());
    }

    // ---------------------------------------------------------------- ordering

    /**
     * Oldest first, which is the opposite of every other list in this API.
     *
     * <p>A reply above the message it answers is unreadable, so the default sort is ascending. Asserted
     * because it is the kind of default that gets "corrected" to match the others by someone who has
     * not read the thread it produces.
     */
    @Test
    @DisplayName("a thread reads oldest first by default")
    void theThreadReadsChronologically() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);

        comment(ticket.getId(), token, "first");
        comment(ticket.getId(), token, "second");
        comment(ticket.getId(), token, "third");

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/comments"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].comment").value("first"))
                .andExpect(jsonPath("$[2].comment").value("third"));

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/comments?sort=createdAt,desc"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].comment").value("third"));
    }

    @Test
    @DisplayName("an unsortable property is a 400 listing what is allowed")
    void sortIsWhitelisted() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/comments?sort=comment"), tokenFor(raiser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("not sortable")));
    }

    // ---------------------------------------------------------------- visibility

    /**
     * The row rule, on the read side. 404 and not 403: see {@code TicketVisibilityGuard}.
     */
    @Test
    @DisplayName("a thread on somebody else's ticket is a 404, not a 403")
    void anUninvolvedUserCannotReadTheThread() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());
        User outsider = givenUser("EMPLOYEE");

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(outsider)))
                .andExpect(status().isNotFound());
    }

    /**
     * The row rule on the write side, and the indistinguishability that makes it a non-disclosure.
     *
     * <p>An outsider posting a valid comment gets the same 404 for a ticket that exists but is not
     * theirs as for one that does not exist at all. That is the comparison worth making, because it is
     * the one an enumerator would make.
     *
     * <p>Note where bean validation sits: {@code @Valid} runs during argument resolution, so a
     * malformed body is a 400 <em>before</em> the handler and therefore before the guard. That is not a
     * leak - the 400 is identical for a real ticket and an imaginary one, so it answers the question
     * "is my body valid" and never "does this ticket exist". The responses only have to be
     * indistinguishable for equal inputs, and they are.
     */
    @Test
    @DisplayName("an uninvolved user cannot comment, and cannot tell the ticket apart from a missing one")
    void anUninvolvedUserCannotComment() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());
        String outsiderToken = tokenFor(givenUser("EMPLOYEE"));

        mockMvc.perform(authenticated(post("/api/tickets/" + ticket.getId() + "/comments"), outsiderToken)
                        .content(BODY.formatted("Let me in.")))
                .andExpect(status().isNotFound());

        mockMvc.perform(authenticated(post("/api/tickets/999999/comments"), outsiderToken)
                        .content(BODY.formatted("Let me in.")))
                .andExpect(status().isNotFound());

        assertThat(ticketCommentRepository.count()).isZero();
    }

    /** The assignee is involved too, so their side of the thread works without TICKET_READ_ALL. */
    @Test
    @DisplayName("the assignee can read and write the thread on a ticket assigned to them")
    void theAssigneeCanParticipate() throws Exception {
        User assignee = givenUser("DEVELOPER");
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), assignee, givenApplication());

        comment(ticket.getId(), tokenFor(assignee), "Picked this up.");

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(assignee)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IT_SUPPORT", "DEVELOPER", "ADMIN"})
    @DisplayName("holders of TICKET_READ_ALL reach the thread on any ticket")
    void supportStaffReachEveryThread(String role) throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());
        String token = tokenFor(givenUser(role));

        comment(ticket.getId(), token, "Triaging.");

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/comments"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"));
    }

    @Test
    @DisplayName("a thread on a ticket that does not exist is a 404")
    void anUnknownTicketIsNotFound() throws Exception {
        mockMvc.perform(authenticated(get("/api/tickets/999999/comments"), tokenFor(givenUser("ADMIN"))))
                .andExpect(status().isNotFound());
    }

    /**
     * A caller holding no roles authenticates and is refused by {@code @PreAuthorize}.
     *
     * <p>403 rather than 404 here, and the difference is not an inconsistency: this one never reaches
     * the service, so nothing has looked at a ticket id yet. The endpoint is refused, not the resource.
     */
    @Test
    @DisplayName("a user with no roles is refused the endpoint itself")
    void arolelessUserIsForbidden() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/comments"), tokenFor(givenUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("comments need a token like everything else")
    void anonymousAccessIsRefused() throws Exception {
        Application application = givenApplication();
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, application);

        mockMvc.perform(get("/api/tickets/" + ticket.getId() + "/comments"))
                .andExpect(status().isUnauthorized());
    }
}
