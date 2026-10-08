package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.Role;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ticket lifecycle through the API, against a real PostgreSQL.
 *
 * <p>Covers the behaviour that depends on the database rather than on Java: sequence-backed ticket
 * numbers, the audit trail written in the same transaction as the change, {@code @Version} refusing a
 * stale write, and the severity ordering that a plain column sort gets wrong.
 */
class TicketLifecycleIT extends AbstractPostgresIT {

    private String createBody(long applicationId, String priority) {
        return """
                {"title":"Payslip incorrect",
                 "description":"Net pay miscalculated for March",
                 "issueType":"BUG",
                 "priority":"%s",
                 "businessImpact":"Department",
                 "applicationId":%d,
                 "moduleName":"Salary"}""".formatted(priority, applicationId);
    }

    private MvcResult createTicket(String token, long applicationId, String priority) throws Exception {
        return mockMvc.perform(authenticated(post("/api/tickets"), token)
                        .content(createBody(applicationId, priority)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    // ---------------------------------------------------------------- creation

    @Test
    @DisplayName("creating a ticket answers 201 and echoes every field back, module included")
    void createReturnsFullRepresentation() throws Exception {
        Application application = givenApplication();
        User raiser = givenUser(Role.EMPLOYEE);

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(raiser))
                        .content(createBody(application.getId(), "HIGH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.ticketNumber").value(org.hamcrest.Matchers.matchesPattern("TKT-\\d+")))
                .andExpect(jsonPath("$.applicationName").value(application.getAppName()))
                // moduleName was stored NOT NULL and returned by nothing, so a client could write it and
                // never read it back.
                .andExpect(jsonPath("$.moduleName").value("Salary"))
                .andExpect(jsonPath("$.raisedBy").value(raiser.getFullName()))
                .andExpect(jsonPath("$.assignedTo").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    @DisplayName("createdAt and updatedAt are identical on a new ticket")
    void timestampsMatchOnCreate() throws Exception {
        Application application = givenApplication();
        MvcResult result = createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW");

        String createdAt = read(result, "$.createdAt");
        String updatedAt = read(result, "$.updatedAt");

        // Two separate LocalDateTime.now() calls could land in different milliseconds.
        assertThat(createdAt).isEqualTo(updatedAt);
    }

    /**
     * The previous generator was {@code "TKT-" + System.currentTimeMillis()}: 200 simultaneous calls
     * produced 68 distinct values, and every collision violated {@code tickets_ticket_number_key} and
     * surfaced as a 500. Correctness now lives in {@code nextval}, which is why this test needs a real
     * sequence rather than a mock.
     */
    @Test
    @DisplayName("ticket numbers come from the sequence: distinct and strictly increasing")
    void ticketNumbersAreUniqueAndOrdered() throws Exception {
        Application application = givenApplication();
        String token = tokenFor(givenUser(Role.EMPLOYEE));

        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            numbers.add(read(createTicket(token, application.getId(), "LOW"), "$.ticketNumber"));
        }

        assertThat(numbers).doesNotHaveDuplicates().hasSize(6);
        assertThat(numbers).isSorted();
    }

    @Test
    @DisplayName("a ticket cannot be raised against a deactivated application")
    void inactiveApplicationIsRejected() throws Exception {
        Application retired = givenApplication(false);

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser(Role.EMPLOYEE)))
                        .content(createBody(retired.getId(), "HIGH")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid reference"));
    }

    @Test
    @DisplayName("an unknown applicationId is a 400, not a 404 - the URL was right, the body was not")
    void unknownApplicationIsBadRequest() throws Exception {
        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser(Role.EMPLOYEE)))
                        .content(createBody(999_999L, "HIGH")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("creation writes a CREATED audit row in the same transaction")
    void createWritesAuditRow() throws Exception {
        Application application = givenApplication();
        User raiser = givenUser(Role.EMPLOYEE);
        String token = tokenFor(raiser);
        long id = ((Number) read(createTicket(token, application.getId(), "LOW"), "$.id")).longValue();

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].actionType").value("CREATED"))
                .andExpect(jsonPath("$[0].fieldName").value("status"))
                .andExpect(jsonPath("$[0].oldValue").doesNotExist())
                .andExpect(jsonPath("$[0].newValue").value("OPEN"))
                .andExpect(jsonPath("$[0].changedBy").value(raiser.getFullName()));
    }

    // ---------------------------------------------------------------- assignment

    /**
     * {@code changed_by} means "who made this change". Attributing an assignment to the person who
     * received it made the trail wrong about the one thing it exists to record.
     */
    @Test
    @DisplayName("assignment is attributed to the assigner, not the assignee")
    void assignmentRecordsTheActor() throws Exception {
        Application application = givenApplication();
        User raiser = givenUser(Role.EMPLOYEE);
        User assignee = givenUser(Role.DEVELOPER);
        User agent = givenUser(Role.IT_SUPPORT);

        long id = ((Number) read(createTicket(tokenFor(raiser), application.getId(), "HIGH"), "$.id"))
                .longValue();
        String agentToken = tokenFor(agent);

        mockMvc.perform(authenticated(put("/api/tickets/" + id + "/assign/" + assignee.getId()), agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignedTo").value(assignee.getFullName()));

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), agentToken))
                .andExpect(status().isOk())
                // CREATED, plus the ASSIGNED and STATUS_CHANGED pair, newest first.
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$[0].changedBy").value(agent.getFullName()))
                .andExpect(jsonPath("$[1].changedBy").value(agent.getFullName()));
    }

    @Test
    @DisplayName("a ticket cannot be assigned to a deactivated account")
    void assignmentToInactiveUserIsRejected() throws Exception {
        Application application = givenApplication();
        User inactive = givenInactiveUser(Role.DEVELOPER);
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();

        // Assigning to somebody who cannot sign in parks the ticket with a phantom owner: it stays
        // ASSIGNED forever and disappears from every unassigned-work view.
        mockMvc.perform(authenticated(put("/api/tickets/" + id + "/assign/" + inactive.getId()),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown assignee id is a 400")
    void assignmentToUnknownUserIsRejected() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();

        mockMvc.perform(authenticated(put("/api/tickets/" + id + "/assign/999999"),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- status and resolvedAt

    /**
     * Setting {@code resolved_at} on RESOLVED was always there; <em>clearing</em> it was not. A ticket
     * resolved and then reopened kept the timestamp, so it counted as resolved in every MTTR and
     * "resolved this week" query while sitting in REOPENED - a wrong number rather than a visible error.
     */
    @Test
    @DisplayName("resolvedAt is stamped on RESOLVED, kept on CLOSED and cleared on anything else")
    void resolvedAtTracksTheStatus() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "HIGH"),
                "$.id")).longValue();
        String token = tokenFor(givenUser(Role.IT_SUPPORT));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"),
                        token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").exists());

        // A closed ticket was resolved; wiping the timestamp at the final step of the happy path would
        // destroy the metric this rule protects.
        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "CLOSED"),
                        token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").exists());

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "REOPENED"),
                        token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());

        // Re-resolving stamps again, so the column answers "when was this last resolved".
        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"),
                        token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").exists());
    }

    @Test
    @DisplayName("a ticket closed without being resolved keeps a null resolvedAt")
    void closedWithoutResolvingStaysNull() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "CLOSED"),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    /**
     * Documents current behaviour rather than endorsing it: no transition table exists, so any status may
     * follow any other. If a workflow is ever agreed, this test is the one that should start failing.
     */
    @Test
    @DisplayName("transitions are unvalidated today, including CLOSED back to OPEN")
    void transitionsAreNotValidated() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();
        String token = tokenFor(givenUser(Role.IT_SUPPORT));

        for (String status : new String[]{"CLOSED", "OPEN", "PENDING", "UNDER_REVIEW"}) {
            mockMvc.perform(authenticated(
                            patch("/api/tickets/" + id + "/status").param("status", status), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(status));
        }
    }

    // ---------------------------------------------------------------- listing

    /**
     * {@code Priority} is stored as a string, so the database sorts it alphabetically: ascending gave
     * CRITICAL, HIGH, LOW, MEDIUM, and {@code priority,desc} - the obvious way to ask for worst-first -
     * put MEDIUM at the top of a triage queue. The service sorts by a severity expression instead.
     */
    @Test
    @DisplayName("sorting by priority orders by severity, not alphabetically")
    void prioritySortsBySeverity() throws Exception {
        Application application = givenApplication();
        String raiserToken = tokenFor(givenUser(Role.EMPLOYEE));
        for (String priority : new String[]{"LOW", "CRITICAL", "MEDIUM", "HIGH"}) {
            createTicket(raiserToken, application.getId(), priority);
        }

        mockMvc.perform(authenticated(get("/api/tickets").param("sort", "priority,desc"),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].priority").value("CRITICAL"))
                .andExpect(jsonPath("$[1].priority").value("HIGH"))
                .andExpect(jsonPath("$[2].priority").value("MEDIUM"))
                .andExpect(jsonPath("$[3].priority").value("LOW"));

        mockMvc.perform(authenticated(get("/api/tickets").param("sort", "priority,asc"),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].priority").value("LOW"))
                .andExpect(jsonPath("$[3].priority").value("CRITICAL"));
    }

    @Test
    @DisplayName("the list is paged, with metadata in headers and a plain array body")
    void listIsPaged() throws Exception {
        Application application = givenApplication();
        String raiserToken = tokenFor(givenUser(Role.EMPLOYEE));
        for (int i = 0; i < 5; i++) {
            createTicket(raiserToken, application.getId(), "LOW");
        }
        String agentToken = tokenFor(givenUser(Role.IT_SUPPORT));

        mockMvc.perform(authenticated(get("/api/tickets").param("size", "2"), agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(header().string("X-Total-Pages", "3"))
                .andExpect(header().string("X-Page-Number", "0"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(authenticated(get("/api/tickets").param("size", "2").param("page", "2"), agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("filters narrow the list and are reflected in the total")
    void filtersApply() throws Exception {
        Application application = givenApplication();
        String raiserToken = tokenFor(givenUser(Role.EMPLOYEE));
        createTicket(raiserToken, application.getId(), "CRITICAL");
        createTicket(raiserToken, application.getId(), "LOW");
        createTicket(raiserToken, application.getId(), "LOW");
        String agentToken = tokenFor(givenUser(Role.IT_SUPPORT));

        mockMvc.perform(authenticated(get("/api/tickets").param("priority", "LOW"), agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"));

        mockMvc.perform(authenticated(get("/api/tickets").param("status", "OPEN"), agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"));

        mockMvc.perform(authenticated(get("/api/tickets").param("status", "CLOSED"), agentToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }

    @Test
    @DisplayName("history is newest first and paged")
    void historyIsNewestFirst() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();
        String token = tokenFor(givenUser(Role.IT_SUPPORT));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "IN_PROGRESS"),
                token)).andExpect(status().isOk());
        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"),
                token)).andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$[0].newValue").value("RESOLVED"))
                .andExpect(jsonPath("$[1].newValue").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[2].actionType").value("CREATED"));
    }

    // ---------------------------------------------------------------- optimistic locking

    /**
     * The audit measured six concurrent status writes all returning 200, last writer silently winning.
     * {@code @Version} turns that into a refused write, which {@code GlobalExceptionHandler} reports as
     * 409 so the client knows to re-read.
     */
    @Test
    @DisplayName("a stale write is refused rather than silently overwriting")
    void staleWriteIsRefused() throws Exception {
        Application application = givenApplication();
        long id = ((Number) read(createTicket(tokenFor(givenUser(Role.EMPLOYEE)), application.getId(), "LOW"),
                "$.id")).longValue();

        Ticket first = ticketRepository.findById(id).orElseThrow();
        Ticket second = ticketRepository.findById(id).orElseThrow();
        assertThat(first).isNotSameAs(second);

        first.setStatus(TicketStatus.IN_PROGRESS);
        ticketRepository.saveAndFlush(first);

        // second still carries the version it was read at.
        second.setStatus(TicketStatus.CLOSED);
        assertThatThrownBy(() -> ticketRepository.saveAndFlush(second))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(ticketRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(TicketStatus.IN_PROGRESS);
    }
}
