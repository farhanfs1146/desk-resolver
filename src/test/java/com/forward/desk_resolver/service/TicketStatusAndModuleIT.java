package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The module-belongs-to-application rule, the status request body, and the no-op status change.
 *
 * <p>All three close gaps where the application accepted a request and then recorded something untrue:
 * a ticket naming a module that does not exist, an audit entry that could not say why a ticket moved, and
 * an audit entry claiming a change that never happened.
 */
class TicketStatusAndModuleIT extends AbstractPostgresIT {

    /** {@code givenApplication()} always catalogues the module as "Salary". */
    private MvcResult createTicket(String token, long applicationId) throws Exception {
        return mockMvc.perform(authenticated(post("/api/tickets"), token)
                        .content("{\"title\":\"Payslip incorrect\",\"description\":\"Net pay wrong\","
                                + "\"issueType\":\"BUG\",\"priority\":\"HIGH\","
                                + "\"applicationId\":" + applicationId + ",\"moduleName\":\"Salary\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private long createTicketId(String token, long applicationId) throws Exception {
        return ((Number) read(createTicket(token, applicationId), "$.id")).longValue();
    }

    // ---------------------------------------------------------------- module belongs to application

    /**
     * {@code applications} is a catalogue of application-and-module pairs, so {@code applicationId}
     * already determines the module. The ticket's own {@code module_name} was unconstrained free text, so
     * a ticket could claim a module that does not exist, or belongs to a different application, and still
     * be filed - landing in a queue nobody owns, with no constraint violated to report it.
     */
    @Test
    @DisplayName("a module that does not belong to the chosen application is rejected")
    void moduleMustBelongToApplication() throws Exception {
        Application application = givenApplication();
        String token = tokenFor(givenUser("EMPLOYEE"));

        mockMvc.perform(authenticated(post("/api/tickets"), token)
                        .content("{\"title\":\"t\",\"description\":\"d\",\"issueType\":\"BUG\","
                                + "\"priority\":\"LOW\",\"applicationId\":" + application.getId()
                                + ",\"moduleName\":\"Payroll\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid reference"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Payroll")));

        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    @DisplayName("a module belonging to a different application is rejected")
    void moduleOfAnotherApplicationIsRejected() throws Exception {
        Application target = givenApplication();
        Application other = applicationRepository.findAll().stream()
                .filter(a -> !a.getId().equals(target.getId()))
                .findFirst()
                .orElseGet(() -> {
                    Application extra = new Application();
                    extra.setAppName("HRMS");
                    extra.setModuleName("attendance");
                    extra.setActive(true);
                    return applicationRepository.save(extra);
                });

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("{\"title\":\"t\",\"description\":\"d\",\"issueType\":\"BUG\","
                                + "\"priority\":\"LOW\",\"applicationId\":" + target.getId()
                                + ",\"moduleName\":\"" + other.getModuleName() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the module match ignores case and padding, and stores the catalogue's spelling")
    void moduleMatchIsForgivingButCanonical() throws Exception {
        Application application = givenApplication();

        // Rejecting "  sAlArY " for "Salary" would be pedantry rather than integrity...
        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("{\"title\":\"t\",\"description\":\"d\",\"issueType\":\"BUG\","
                                + "\"priority\":\"LOW\",\"applicationId\":" + application.getId()
                                + ",\"moduleName\":\"  sAlArY \"}"))
                .andExpect(status().isCreated())
                // ...but the stored value is the catalogue's, so the column cannot drift into several
                // casings of one module and split a queue that way instead.
                .andExpect(jsonPath("$.moduleName").value("Salary"));
    }

    // ---------------------------------------------------------------- status body and remarks

    /**
     * {@code ticket_history_tracking.remarks} has existed since V7, and every entry carried the same fixed
     * string because the endpoint had nowhere to put a caller's note - so the column meant to answer
     * <em>why</em> a ticket moved only ever repeated <em>that</em> it moved.
     */
    @Test
    @DisplayName("a status change can carry remarks, which reach the audit trail")
    void statusBodyRecordsRemarks() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status"), token)
                        .content("{\"status\":\"RESOLVED\",\"remarks\":\"Fixed in build 412\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remarks").value("Fixed in build 412"));
    }

    @Test
    @DisplayName("omitting remarks falls back to a generic description")
    void remarksAreOptional() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status"), token)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(jsonPath("$[0].remarks").value("Ticket status updated"));
    }

    /** The query parameter predates the body and must keep working, or every existing client breaks. */
    @Test
    @DisplayName("the legacy ?status= parameter still works")
    void queryParameterStillWorks() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"),
                        tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @DisplayName("when both are supplied the body wins, because it can carry more")
    void bodyWinsOverParameter() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());

        mockMvc.perform(authenticated(
                        patch("/api/tickets/" + id + "/status").param("status", "CLOSED"),
                        tokenFor(givenUser("IT_SUPPORT")))
                        .content("{\"status\":\"IN_PROGRESS\",\"remarks\":\"body takes precedence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    /**
     * Making the parameter optional must not change what a caller who supplies nothing is told, which is
     * why the controller throws the same exception Spring would have.
     */
    @Test
    @DisplayName("supplying neither body nor parameter is the same 400 as before")
    void neitherSourceIsStillBadRequest() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status"),
                        tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing parameter"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("the status body is validated")
    void bodyIsValidated() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status"), token)
                        .content("{\"status\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.status").value("Status is required"));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status"), token)
                        .content("{\"status\":\"RESOLVED\",\"remarks\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.remarks").exists());
    }

    // ---------------------------------------------------------------- no-op status change

    /**
     * A request that does not change the status used to write "STATUS_CHANGED status: OPEN -> OPEN", bump
     * {@code updated_at} and - for a ticket already RESOLVED - re-stamp {@code resolved_at}. None of that
     * happened. An audit trail of non-events is worse than a short one, because every reader then has to
     * work out which entries mean something.
     */
    @Test
    @DisplayName("moving a ticket to the status it already has records nothing")
    void noOpStatusChangeRecordsNothing() throws Exception {
        Application application = givenApplication();
        MvcResult created = createTicket(tokenFor(givenUser("EMPLOYEE")), application.getId());
        long id = ((Number) read(created, "$.id")).longValue();
        String updatedAtBefore = read(created, "$.updatedAt");
        String token = tokenFor(givenUser("IT_SUPPORT"));

        // Idempotent: the caller asked for a state the ticket is already in, and it is in it.
        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "OPEN"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.updatedAt").value(updatedAtBefore));

        // Only the CREATED entry, with no OPEN -> OPEN beside it.
        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].actionType").value("CREATED"));
    }

    @Test
    @DisplayName("re-sending RESOLVED does not move resolvedAt")
    void noOpDoesNotRestampResolvedAt() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());
        String token = tokenFor(givenUser("IT_SUPPORT"));

        String resolvedAt = read(mockMvc.perform(authenticated(
                        patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"), token))
                .andExpect(status().isOk()).andReturn(), "$.resolvedAt");

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "RESOLVED"),
                        token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").value(resolvedAt));

        // And still exactly one STATUS_CHANGED entry.
        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(header().string("X-Total-Count", "2"));
    }

    @Test
    @DisplayName("a real change after a no-op is still recorded")
    void realChangeAfterNoOpStillWorks() throws Exception {
        Application application = givenApplication();
        long id = createTicketId(tokenFor(givenUser("EMPLOYEE")), application.getId());
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "OPEN"), token))
                .andExpect(status().isOk());
        mockMvc.perform(authenticated(patch("/api/tickets/" + id + "/status").param("status", "IN_PROGRESS"),
                        token))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/tickets/" + id + "/history"), token))
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].oldValue").value("OPEN"))
                .andExpect(jsonPath("$[0].newValue").value("IN_PROGRESS"));
    }
}
