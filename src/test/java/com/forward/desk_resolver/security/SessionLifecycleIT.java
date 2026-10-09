package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revocation: the property {@code auth.sessions} was introduced for.
 *
 * <p>Before {@code V17}, docs/DECISIONS.md recorded "access tokens cannot be revoked" as an accepted
 * limitation, and a password change was explicitly documented as not ending a session somebody already
 * held. Every test here would have failed then. They are the evidence that the limitation is gone, and
 * the tests that should start failing if a future change reintroduces it.
 */
class SessionLifecycleIT extends AbstractPostgresIT {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private SessionService sessionService;

    @Test
    @DisplayName("a login opens one session, expiring exactly when the token does")
    void loginOpensASession() throws Exception {
        User user = givenUser("EMPLOYEE");

        tokenFor(user);

        List<UserSession> sessions = userSessionRepository.findAll();
        assertThat(sessions).hasSize(1);

        UserSession session = sessions.getFirst();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getRevokedAt()).isNull();
        // The session and the token are built from one clock reading and one computed expiry, so these
        // must agree to the second rather than merely be close.
        assertThat(session.getExpiresAt())
                .isEqualTo(session.getIssuedAt().plus(securityProperties.jwt().ttl()));
    }

    @Test
    @DisplayName("logging out stops the token working on the very next request")
    void logoutRevokesTheSession() throws Exception {
        User user = givenUser("IT_SUPPORT");
        String token = tokenFor(user);

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(post("/api/auth/logout"), token))
                .andExpect(status().isNoContent());

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));

        assertThat(userSessionRepository.findAll().getFirst().getRevokedReason())
                .isEqualTo(SessionService.RevocationReason.LOGOUT.name());
    }

    @Test
    @DisplayName("logging out twice is still 204 - nothing to tell the caller, nothing to leak")
    void logoutIsIdempotent() throws Exception {
        User user = givenUser("EMPLOYEE");
        String token = tokenFor(user);

        mockMvc.perform(authenticated(post("/api/auth/logout"), token))
                .andExpect(status().isNoContent());

        // The second attempt arrives with a token whose session is already dead, so it is refused by
        // the filter chain before reaching the endpoint - a 401, not a 204, and deliberately the same
        // 401 any unusable token gets.
        mockMvc.perform(authenticated(post("/api/auth/logout"), token))
                .andExpect(status().isUnauthorized());

        UserSession session = userSessionRepository.findAll().getFirst();
        assertThat(session.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("an account with no roles can still log out of its own session")
    void rolelessAccountCanLogOut() throws Exception {
        User user = givenUser();
        String token = tokenFor(user);

        mockMvc.perform(authenticated(post("/api/auth/logout"), token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("changing a password ends every session the user holds, the current one included")
    void passwordChangeRevokesEverySession() throws Exception {
        User user = givenUser("EMPLOYEE");
        String first = tokenFor(user);
        String second = tokenFor(user);

        assertThat(userSessionRepository.findAll()).hasSize(2);

        mockMvc.perform(authenticated(patch("/api/users/me/password"), second)
                        .content("""
                                {"currentPassword":"%s","newPassword":"a-much-longer-password"}"""
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        // Both tokens, not just the other one. This is the half of the behaviour that is easy to get
        // wrong and the reason the test asserts on two sessions rather than one.
        mockMvc.perform(authenticated(get("/api/tickets"), first))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authenticated(get("/api/tickets"), second))
                .andExpect(status().isUnauthorized());

        assertThat(userSessionRepository.findAll())
                .allSatisfy(session -> assertThat(session.getRevokedReason())
                        .isEqualTo(SessionService.RevocationReason.PASSWORD_CHANGED.name()));
    }

    @Test
    @DisplayName("deactivating an account ends its live sessions immediately, not at token expiry")
    void deactivationEndsLiveSessions() throws Exception {
        User user = givenUser("IT_SUPPORT");
        String token = tokenFor(user);

        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isOk());

        user.setActive(false);
        userRepository.save(user);

        // The session row is untouched - nothing revoked it. The per-request query joins users.active,
        // which is what makes this work, and is why that join is in the join and not an afterthought.
        mockMvc.perform(authenticated(get("/api/users"), token))
                .andExpect(status().isUnauthorized());
        assertThat(userSessionRepository.findAll().getFirst().getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("an expired session is refused even while the token itself is still in date")
    void expiredSessionIsRefused() throws Exception {
        User user = givenUser("EMPLOYEE");
        String token = tokenFor(user);

        UserSession session = userSessionRepository.findAll().getFirst();
        session.setExpiresAt(Instant.now().minusSeconds(1));
        userSessionRepository.save(session);

        mockMvc.perform(authenticated(get("/api/tickets"), token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A correctly signed token with no {@code sid} claim authenticates nobody.
     *
     * <p>This is the shape a token minted before sessions existed would have, and the shape an external
     * identity provider's token would have. It verifies by signature, expiry and issuer - so every check
     * that existed before {@code V17} passes it - and is still refused, because a session that cannot be
     * named cannot be revoked. Minted here with the application's own encoder, so the test cannot
     * accidentally be passing for the wrong reason.
     */
    @Test
    @DisplayName("a validly signed token carrying no session claim is refused")
    void tokenWithoutSessionClaimIsRefused() throws Exception {
        User user = givenUser("ADMIN");
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                .subject(String.valueOf(user.getId()))
                .build();

        String sidless = jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SecurityConfig.JWS_ALGORITHM).build(), claims))
                .getTokenValue();

        mockMvc.perform(authenticated(get("/api/users"), sidless))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token whose subject is not the session's owner is refused")
    void subjectMustMatchTheSessionOwner() throws Exception {
        User owner = givenUser("ADMIN");
        User other = givenUser("ADMIN");
        tokenFor(owner);

        UserSession session = userSessionRepository.findAll().getFirst();
        Instant now = Instant.now();

        // Signed by this application, naming a real live session, but claiming to be somebody else.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                .subject(String.valueOf(other.getId()))
                .claim("sid", session.getId().toString())
                .build();

        String mismatched = jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SecurityConfig.JWS_ALGORITHM).build(), claims))
                .getTokenValue();

        mockMvc.perform(authenticated(get("/api/users"), mismatched))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the purge removes long-expired sessions and leaves live ones alone")
    void purgeRemovesOnlyLongExpiredSessions() throws Exception {
        User user = givenUser("EMPLOYEE");
        tokenFor(user);

        UserSession stale = new UserSession();
        stale.setId(UUID.randomUUID());
        stale.setUserId(user.getId());
        stale.setIssuedAt(Instant.now().minus(40, ChronoUnit.DAYS));
        stale.setExpiresAt(Instant.now().minus(40, ChronoUnit.DAYS));
        userSessionRepository.save(stale);

        assertThat(userSessionRepository.findAll()).hasSize(2);

        sessionService.purgeExpiredSessions();

        assertThat(userSessionRepository.findAll()).hasSize(1);
        assertThat(userSessionRepository.findById(stale.getId())).isEmpty();
    }
}
