package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.enums.IssueType;
import com.forward.desk_resolver.enums.Priority;
import com.forward.desk_resolver.enums.Role;
import com.forward.desk_resolver.enums.TicketStatus;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resource-level authorization: who may see and change which ticket.
 *
 * <p>Several comments in the production code reason about what "ResourceAccessControlTest" asserts -
 * for example {@code TicketServiceImpl.requireAssignable} cites it when explaining why assigning a
 * ticket to an EMPLOYEE is deliberate rather than an oversight. No such test existed, so those
 * arguments rested on a file nobody could run. This is that test.
 *
 * <p>The behaviour being pinned down is the part no annotation can express. {@code @PreAuthorize}
 * decides whether a caller may use an endpoint at all; whether they may see <em>this</em> ticket depends
 * on the row, and is enforced in the service.
 */
class ResourceAccessControlIT extends AbstractPostgresIT {

    private Ticket givenTicket(User raisedBy, User assignedTo, Application application) {
        Ticket ticket = new Ticket();
        ticket.setTicketNumber("TKT-" + System.nanoTime());
        ticket.setTitle("Payslip incorrect");
        ticket.setDescription("Net pay miscalculated for March");
        ticket.setIssueType(IssueType.BUG);
        ticket.setPriority(Priority.HIGH);
        ticket.setStatus(assignedTo == null ? TicketStatus.OPEN : TicketStatus.ASSIGNED);
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticket.setRaisedBy(raisedBy);
        ticket.setAssignedTo(assignedTo);
        ticket.setApplication(application);
        ticket.setModuleName("Salary");
        return ticketRepository.save(ticket);
    }

    // ---------------------------------------------------------------- reading one ticket

    /**
     * The IDOR/BOLA boundary. The response is <strong>404, not 403</strong>, and that is the point: a 403
     * would confirm the ticket exists, letting an unauthorised caller map out valid ids by walking the
     * URL. A ticket someone may not see must be indistinguishable from one that does not exist.
     */
    @Test
    @DisplayName("a stranger's ticket answers 404, not 403, so ids cannot be probed")
    void strangerGetsNotFoundNotForbidden() throws Exception {
        User owner = givenUser(Role.EMPLOYEE);
        User stranger = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(owner, null, givenApplication());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), tokenFor(stranger)))
                .andExpect(status().isNotFound());

        // Indistinguishable from an id that was never issued.
        mockMvc.perform(authenticated(get("/api/tickets/999999"), tokenFor(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the raiser can read their own ticket")
    void raiserCanRead() throws Exception {
        User owner = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(owner, null, givenApplication());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticket.getId()));
    }

    /**
     * Being the assignee is involvement. This is the behaviour {@code requireAssignable} cites when
     * declining to restrict assignment to roles that can change status: a requester-role assignee has to
     * be able to see the work handed to them.
     */
    @Test
    @DisplayName("an EMPLOYEE assignee can read and list the ticket assigned to them")
    void assigneeCanRead() throws Exception {
        User raiser = givenUser(Role.EMPLOYEE);
        User assignee = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(raiser, assignee, givenApplication());
        String token = tokenFor(assignee);

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), token))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(get("/api/tickets"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"IT_SUPPORT", "DEVELOPER", "ADMIN"})
    @DisplayName("holders of TICKET_READ_ALL see tickets they are not involved in")
    void supportStaffSeeEverything(Role role) throws Exception {
        User owner = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(owner, null, givenApplication());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), tokenFor(givenUser(role))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MANAGER", "HOD", "DIRECTOR"})
    @DisplayName("seniority does not grant visibility, because no department rule exists")
    void seniorRolesAreStillRestricted(Role role) throws Exception {
        User owner = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(owner, null, givenApplication());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId()), tokenFor(givenUser(role))))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- listing tickets

    /**
     * The collection half of the same boundary. The restriction is pushed into the SQL and ANDed with the
     * caller's own filters, so asking for someone else's tickets returns nothing rather than leaking a
     * count.
     */
    @Test
    @DisplayName("the list shows only tickets the caller is involved in")
    void listIsScopedToInvolvement() throws Exception {
        Application application = givenApplication();
        User caller = givenUser(Role.EMPLOYEE);
        User other = givenUser(Role.EMPLOYEE);

        givenTicket(caller, null, application);          // raised by caller
        givenTicket(other, caller, application);         // assigned to caller
        givenTicket(other, null, application);           // neither
        givenTicket(other, other, application);          // neither

        mockMvc.perform(authenticated(get("/api/tickets"), tokenFor(caller)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"));

        mockMvc.perform(authenticated(get("/api/tickets"), tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "4"));
    }

    @Test
    @DisplayName("filtering by another user's id cannot widen the result or leak a count")
    void filterCannotEscapeTheBoundary() throws Exception {
        Application application = givenApplication();
        User caller = givenUser(Role.EMPLOYEE);
        User other = givenUser(Role.EMPLOYEE);
        givenTicket(other, null, application);
        givenTicket(other, other, application);

        mockMvc.perform(authenticated(
                        get("/api/tickets").param("raisedBy", String.valueOf(other.getId())),
                        tokenFor(caller)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---------------------------------------------------------------- history

    /**
     * History is guarded by the same check as the ticket itself. A caller who cannot read a ticket must
     * get the same 404 for its history - an empty page would confirm the ticket exists.
     */
    @Test
    @DisplayName("history is unreadable for a ticket the caller cannot read")
    void historyFollowsTicketVisibility() throws Exception {
        User owner = givenUser(Role.EMPLOYEE);
        User stranger = givenUser(Role.EMPLOYEE);
        Ticket ticket = givenTicket(owner, null, givenApplication());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/history"),
                        tokenFor(stranger)))
                .andExpect(status().isNotFound());

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/history"),
                        tokenFor(owner)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- user records

    /**
     * The item endpoint and the collection endpoint have to be guarded together. Without this, any
     * authenticated user could walk {@code /api/users/1..n} and rebuild the directory that locking down
     * {@code GET /api/users} was meant to protect.
     */
    @Test
    @DisplayName("a user may read their own record but not another's")
    void userRecordsAreSelfOnlyWithoutUserRead() throws Exception {
        User caller = givenUser(Role.EMPLOYEE);
        User other = givenUser(Role.EMPLOYEE);
        String token = tokenFor(caller);

        mockMvc.perform(authenticated(get("/api/users/" + caller.getId()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(caller.getEmail()))
                // A password hash must never appear in any representation.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(authenticated(get("/api/users/" + other.getId()), token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USER_READ holders may read any record")
    void supportStaffReadAnyUser() throws Exception {
        User other = givenUser(Role.EMPLOYEE);

        mockMvc.perform(authenticated(get("/api/users/" + other.getId()),
                        tokenFor(givenUser(Role.IT_SUPPORT))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- endpoint permissions

    @Test
    @DisplayName("a requester cannot assign, change status, list users or manage the catalogue")
    void requesterIsDeniedPrivilegedEndpoints() throws Exception {
        Application application = givenApplication();
        User caller = givenUser(Role.EMPLOYEE);
        User assignee = givenUser(Role.IT_SUPPORT);
        Ticket own = givenTicket(caller, null, application);
        String token = tokenFor(caller);

        // Denied even on their own ticket: whether a requester may close their own ticket is an
        // undecided business rule, so the permission is withheld.
        mockMvc.perform(authenticated(
                        patch("/api/tickets/" + own.getId() + "/status").param("status", "CLOSED"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(
                        put("/api/tickets/" + own.getId() + "/assign/" + assignee.getId()), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(post("/api/users"), token)
                        .content("""
                                {"employeeCode":1,"fullName":"X","email":"x@example.test",
                                 "role":"ADMIN","active":true,"password":"password-1234"}"""))
                .andExpect(status().isForbidden());

        mockMvc.perform(authenticated(post("/api/applications"), token)
                        .content("""
                                {"appName":"X","moduleName":"Y","active":true}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("support staff may triage but not administer")
    void supportStaffCannotAdminister() throws Exception {
        String token = tokenFor(givenUser(Role.IT_SUPPORT));

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(post("/api/applications"), token)
                        .content("""
                                {"appName":"X","moduleName":"Y","active":true}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the 403 body names no permission, role or rule")
    void denialLeaksNothing() throws Exception {
        String token = tokenFor(givenUser(Role.EMPLOYEE));

        String body = mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("USER_READ", "EMPLOYEE", "hasAuthority", "ROLE_");
    }

    // ---------------------------------------------------------------- unauthenticated

    @Test
    @DisplayName("every endpoint except login requires a token")
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tickets/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tickets")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a 401 advertises the scheme and reveals nothing about why it failed")
    void unauthenticatedResponseShape() throws Exception {
        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void forgedTokenIsRejected() throws Exception {
        // Structurally valid, correctly shaped claims, wrong signature.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJpc3MiOiJkZXNrLXJlc29sdmVyIiwic3ViIjoiMSIsInJvbGUiOiJBRE1JTiJ9."
                + "bm90LWEtdmFsaWQtc2lnbmF0dXJl";

        mockMvc.perform(get("/api/tickets").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }
}
