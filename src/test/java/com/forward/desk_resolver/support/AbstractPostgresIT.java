package com.forward.desk_resolver.support;

import com.jayway.jsonpath.JsonPath;
import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.entity.Role;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserRole;
import com.forward.desk_resolver.repository.ApplicationRepository;
import com.forward.desk_resolver.repository.RoleRepository;
import com.forward.desk_resolver.repository.TicketHistoryTrackingRepository;
import com.forward.desk_resolver.repository.TicketRepository;
import com.forward.desk_resolver.repository.UserRepository;
import com.forward.desk_resolver.repository.UserRoleRepository;
import com.forward.desk_resolver.repository.UserSessionRepository;
import com.forward.desk_resolver.security.ratelimit.InMemoryLoginAttemptLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration tests: a real PostgreSQL, the real migrations, the real security filter
 * chain.
 *
 * <p><strong>Why a container rather than an in-memory database.</strong> The parts of this application
 * most worth testing are the parts that only PostgreSQL provides. Ticket numbers come from
 * {@code nextval('ticket_number_seq')} through a native query; the migrations use {@code CREATE
 * SEQUENCE}, {@code ON DELETE RESTRICT}, {@code ALTER TABLE ... SET SCHEMA}, a partial index, a
 * {@code DO} block that raises, and index DDL with {@code DESC} ordering; and the entity mappings are
 * checked by Hibernate's schema validation against what Flyway actually built. An in-memory substitute
 * would either reject the migrations or quietly diverge, and the tests that still passed would be
 * testing the substitute.
 *
 * <p>Since {@code V17} that includes the whole authorization model: the role-to-permission seed, the
 * cross-schema foreign keys, and the per-request authority query are all exercised against the real
 * schema rather than a stub, because every one of them is SQL.
 *
 * <p><strong>Why one container for the whole run.</strong> The container is a static field started once
 * per JVM, not a {@code @Container}-managed per-class resource, and every subclass inherits exactly this
 * configuration - so Spring reuses one cached application context and Flyway migrates once. Testcontainers'
 * reaper removes the container when the JVM exits.
 *
 * <p><strong>Nothing here touches a developer's own database.</strong> The connection details come from
 * the container, so running the suite cannot create, migrate or modify a local schema.
 */
@SpringBootTest(properties = {
        // A fixed key, so tokens are deterministic and the "random key per JVM" warning path is not
        // exercised here. 32 bytes, the HS256 minimum.
        "app.security.jwt.secret=test-signing-key-at-least-32-bytes",
        "app.security.jwt.issuer=desk-resolver",
        // The bootstrap administrator is deliberately left unconfigured: these tests create their own
        // users, and BootstrapAdminInitializer must not insert a row the tests did not ask for.
        "app.security.bootstrap-admin.email=",
        "app.security.bootstrap-admin.password="
})
@AutoConfigureMockMvc
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    /** Keeps employee codes and emails unique across tests without coordinating between them. */
    private static final AtomicLong SEQUENCE = new AtomicLong(1_000);

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected UserRoleRepository userRoleRepository;
    @Autowired protected UserSessionRepository userSessionRepository;
    @Autowired protected ApplicationRepository applicationRepository;
    @Autowired protected TicketRepository ticketRepository;
    @Autowired protected TicketHistoryTrackingRepository ticketHistoryTrackingRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected InMemoryLoginAttemptLimiter loginAttemptLimiter;

    /**
     * A clean database and a clean throttle before every test.
     *
     * <p>Both matter for the same reason: the application context is cached across the whole suite, so
     * the limiter is one singleton for every test and rows outlive the test that created them. Without
     * the reset, failed-login tests would spend a later test's budget and produce 429s unrelated to what
     * is being checked - which is exactly what {@code clearAll()} exists for.
     *
     * <p>Deletion order follows the foreign keys, children first. Sessions and grants go before users,
     * and that is not merely tidiness: {@code auth.user_roles.user_id} cascades, but
     * {@code granted_by} is {@code ON DELETE RESTRICT}, so a user who granted somebody else a role
     * cannot be deleted while that grant exists. Clearing the grants first removes the question. Roles
     * and permissions are never deleted - they are seed data from {@code V17}, and a test that wiped
     * them would leave every later test unable to create a user.
     */
    @BeforeEach
    void resetState() {
        ticketHistoryTrackingRepository.deleteAll();
        ticketRepository.deleteAll();
        applicationRepository.deleteAll();
        userSessionRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();
        loginAttemptLimiter.clearAll();
    }

    // ------------------------------------------------------------------ fixtures

    protected static final String PASSWORD = "correct-horse-battery";

    /**
     * Creates an active user with a usable password, holding the named roles.
     *
     * <p>Varargs, because a user can hold several roles since {@code V17} and the union of their
     * permissions is a thing worth testing. Passing none creates an account that authenticates and can
     * do nothing but change its own password - also a case worth having.
     *
     * <p>The roles are looked up rather than created: they are {@code V17}'s seed, and a test that
     * invented its own would be asserting against a mapping the application does not ship.
     */
    protected User givenUser(String... roleCodes) {
        long unique = SEQUENCE.getAndIncrement();
        String label = roleCodes.length == 0 ? "Roleless" : roleCodes[0];

        User user = new User();
        user.setEmployeeCode(unique);
        user.setFullName(label.charAt(0) + label.substring(1).toLowerCase() + " " + unique);
        user.setEmail("user" + unique + "@example.test");
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        User saved = userRepository.save(user);

        grantRoles(saved, roleCodes);
        return saved;
    }

    /** Grants roles to an existing user. {@code granted_by} is null: no test user granted them. */
    protected void grantRoles(User user, String... roleCodes) {
        for (String code : roleCodes) {
            Role role = roleRepository.findByCode(code).orElseThrow(
                    () -> new IllegalStateException("auth.roles has no role '" + code
                            + "'; V17's seed is not what this test expects"));
            userRoleRepository.save(UserRole.of(user.getId(), role.getId(), null));
        }
    }

    /** Creates a user who cannot authenticate, because no password has ever been set (V13). */
    protected User givenUserWithoutPassword(String... roleCodes) {
        User user = givenUser(roleCodes);
        user.setPasswordHash(null);
        return userRepository.save(user);
    }

    protected User givenInactiveUser(String... roleCodes) {
        User user = givenUser(roleCodes);
        user.setActive(false);
        return userRepository.save(user);
    }

    protected Application givenApplication() {
        return givenApplication(true);
    }

    protected Application givenApplication(boolean active) {
        Application application = new Application();
        application.setAppName("Payroll " + SEQUENCE.getAndIncrement());
        application.setModuleName("Salary");
        application.setActive(active);
        return applicationRepository.save(application);
    }

    // ------------------------------------------------------------------ http helpers

    /** Authenticates through the real login endpoint and returns the bearer token. */
    protected String tokenFor(User user) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(user.getEmail(), PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return read(result, "$.accessToken");
    }

    /**
     * Reads one value out of a JSON response.
     *
     * <p>Uses JsonPath rather than an injected {@code ObjectMapper}: Spring Boot 4 defaults to Jackson 3,
     * so {@code com.fasterxml.jackson.databind.ObjectMapper} is not a bean in this context, and a test
     * helper has no business caring which Jackson the application serializes with. JsonPath is already
     * present - it is what MockMvc's own {@code jsonPath(...)} matchers use.
     */
    protected <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    /** Attaches a bearer token and a JSON content type. */
    protected MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder,
                                                          String token) {
        return builder.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
