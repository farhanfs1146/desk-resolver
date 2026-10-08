package com.forward.desk_resolver;

import com.forward.desk_resolver.service.ApplicationService;
import com.forward.desk_resolver.service.AuthService;
import com.forward.desk_resolver.service.TicketService;
import com.forward.desk_resolver.service.UserService;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context smoke test: the application wires up, Flyway migrates and Hibernate validates the mappings.
 *
 * <p><strong>This extends {@link AbstractPostgresIT} on purpose.</strong> It used to be a bare
 * {@code @SpringBootTest}, which meant it booted against whatever {@code application.yaml} points at -
 * a developer's own PostgreSQL. Running {@code mvn test} therefore created the configured schema locally
 * and migrated it, as a side effect of running the tests. That is a surprising thing for a test suite to
 * do to a database it was never pointed at deliberately, and here it was actively harmful: it created an
 * empty {@code desk_resolver_db} alongside the real data, which is the state a deployment must not be in.
 *
 * <p>Taking the container from the base class means no test in this project can reach a local database.
 */
class DeskResolverApplicationTests extends AbstractPostgresIT {

    @Autowired private TicketService ticketService;
    @Autowired private UserService userService;
    @Autowired private ApplicationService applicationService;
    @Autowired private AuthService authService;
    @Autowired private SecurityFilterChain securityFilterChain;

    @Test
    @DisplayName("the context loads with every service and the security chain present")
    void contextLoads() {
        assertThat(ticketService).isNotNull();
        assertThat(userService).isNotNull();
        assertThat(applicationService).isNotNull();
        assertThat(authService).isNotNull();
        assertThat(securityFilterChain).isNotNull();
    }

    /**
     * Flyway ran and Hibernate's {@code ddl-auto: validate} accepted the result. Both are implied by the
     * context starting at all, but asserting on the tables makes the failure legible: a missing column is
     * a schema problem, not a wiring problem.
     */
    @Test
    @DisplayName("the migrated schema is queryable through every repository")
    void schemaIsMigrated() {
        assertThat(userRepository.count()).isNotNegative();
        assertThat(applicationRepository.count()).isNotNegative();
        assertThat(ticketRepository.count()).isNotNegative();
        assertThat(ticketHistoryTrackingRepository.count()).isNotNegative();
    }

    /** V10's sequence: the ticket number generator depends on it, and it is easy to omit. */
    @Test
    @DisplayName("the ticket number sequence exists and hands out increasing values")
    void ticketNumberSequenceExists() {
        long first = ticketRepository.nextTicketNumberValue();
        long second = ticketRepository.nextTicketNumberValue();

        assertThat(second).isGreaterThan(first);
    }
}
