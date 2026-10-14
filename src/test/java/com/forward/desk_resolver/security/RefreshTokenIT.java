package com.forward.desk_resolver.security;

import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.entity.UserSession;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh-token rotation: the behaviour V21 introduced.
 *
 * <p>Before it, docs/DECISIONS.md recorded "no refresh tokens - a token lasts 30 minutes and then the
 * client logs in again", which reads like a small inconvenience and is not: the expiry lands mid-task
 * and nothing but a password can recover from it.
 *
 * <p>What is worth testing here is almost entirely the <em>refusals</em>. Issuing a new token pair is
 * the easy half and would pass under several designs that are quietly broken; single-use rotation,
 * reuse detection, the absolute cap and the deactivation check are the parts that have to hold, and
 * each of them is a case where the endpoint must do less than it could.
 */
class RefreshTokenIT extends AbstractPostgresIT {

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private SessionService sessionService;

    private List<UserSession> sessionsNewestFirst() {
        return userSessionRepository.findAll().stream()
                .sorted(Comparator.comparing(UserSession::getIssuedAt).reversed())
                .toList();
    }

    // ------------------------------------------------------------------ issuing

    @Test
    @DisplayName("a login issues a refresh token alongside the access token, and reports both lifetimes")
    void loginIssuesARefreshToken() throws Exception {
        User user = givenUser("EMPLOYEE");

        MvcResult result = login(user);

        String refreshToken = read(result, "$.refreshToken");
        assertThat(refreshToken).isNotBlank();
        assertThat((Integer) read(result, "$.expiresIn"))
                .isEqualTo((int) securityProperties.jwt().ttl().toSeconds());
        assertThat((Integer) read(result, "$.refreshExpiresIn"))
                .isEqualTo((int) securityProperties.refresh().ttl().toSeconds());

        // The plaintext is returned once and never stored. What the row holds is a digest, which is
        // the property a database dump depends on.
        UserSession session = userSessionRepository.findAll().getFirst();
        assertThat(session.getRefreshTokenHash())
                .isNotNull()
                .isNotEqualTo(refreshToken)
                .isEqualTo(refreshTokenService.hash(refreshToken));
    }

    @Test
    @DisplayName("refreshing returns a working access token without a password")
    void refreshIssuesAUsableAccessToken() throws Exception {
        User user = givenUser("IT_SUPPORT");
        String refreshToken = refreshTokenFor(user);

        MvcResult refreshed = refresh(refreshToken);
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);

        String newAccessToken = read(refreshed, "$.accessToken");
        mockMvc.perform(authenticated(get("/api/users"), newAccessToken))
                .andExpect(status().isOk());

        // Identity and rendering data come back too, so a client restoring a session after a reload
        // does not need a second request to find out who it is or what to show.
        assertThat((Integer) read(refreshed, "$.userId")).isEqualTo(user.getId().intValue());
        assertThat((List<String>) read(refreshed, "$.permissions")).contains("TICKET_READ_ALL");
    }

    /**
     * The old access token stays valid, and that is deliberate rather than an oversight.
     *
     * <p>Rotation marks the previous session's refresh token used but does not revoke the session, so
     * a browser holding requests in flight at the instant it refreshes does not collect 401s for them.
     * The credential that can mint more tokens dies immediately; the one that merely expires is left
     * to expire.
     */
    @Test
    @DisplayName("rotation does not kill the access token the client is still holding")
    void theOldAccessTokenSurvivesRotation() throws Exception {
        User user = givenUser("IT_SUPPORT");
        MvcResult login = login(user);
        String oldAccessToken = read(login, "$.accessToken");

        assertThat(refresh(read(login, "$.refreshToken")).getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(authenticated(get("/api/users"), oldAccessToken))
                .andExpect(status().isOk());

        UserSession previous = sessionsNewestFirst().getLast();
        assertThat(previous.getRefreshUsedAt()).isNotNull();
        assertThat(previous.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("rotation opens a new session in the same family, and issues a different token each time")
    void rotationChainsSessionsInOneFamily() throws Exception {
        User user = givenUser("EMPLOYEE");
        String first = refreshTokenFor(user);

        String second = read(refresh(first), "$.refreshToken");
        String third = read(refresh(second), "$.refreshToken");

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();

        List<UserSession> sessions = sessionsNewestFirst();
        assertThat(sessions).hasSize(3);
        // One login, one family - which is what makes logout and reuse detection able to end the whole
        // chain rather than the slice the caller happens to hold.
        assertThat(sessions).extracting(UserSession::getFamilyId).containsOnly(
                sessions.getLast().getId());
    }

    // ------------------------------------------------------------------ single use and reuse

    @Test
    @DisplayName("a refresh token works exactly once")
    void aRefreshTokenIsSingleUse() throws Exception {
        User user = givenUser("EMPLOYEE");
        String refreshToken = refreshTokenFor(user);

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * The reason rotation exists at all.
     *
     * <p>Rotating on its own only shortens the window a stolen token is useful for. What it buys is a
     * signal: a single-use credential presented twice means two parties hold it. Nothing can say which
     * one is the owner, so the login ends for both - the user logs in again, and whoever copied the
     * token loses a credential that had hours left on it.
     *
     * <p>This is the test that would fail if somebody added {@code @Transactional} to
     * {@code AuthServiceImpl.refresh}: the revocation happens and is then followed by a throw, so under
     * a method-level transaction it would roll back and the family would stay alive while the response
     * still said 401.
     */
    @Test
    @DisplayName("presenting a used refresh token revokes the whole login, not just that request")
    void reusingARefreshTokenRevokesTheFamily() throws Exception {
        User user = givenUser("IT_SUPPORT");
        String stolen = refreshTokenFor(user);

        String liveAccessToken = read(refresh(stolen), "$.accessToken");
        mockMvc.perform(authenticated(get("/api/users"), liveAccessToken))
                .andExpect(status().isOk());

        // The same token again. One of the two holders copied it; the server cannot tell which.
        assertThat(refresh(stolen).getResponse().getStatus()).isEqualTo(401);

        // Not merely refused - the session the legitimate holder was using is gone too.
        mockMvc.perform(authenticated(get("/api/users"), liveAccessToken))
                .andExpect(status().isUnauthorized());

        assertThat(userSessionRepository.findAll())
                .isNotEmpty()
                .allSatisfy(session -> assertThat(session.getRevokedReason())
                        .isEqualTo(SessionService.RevocationReason.REFRESH_TOKEN_REUSED.name()));
    }

    @Test
    @DisplayName("a token that was never issued is refused and revokes nothing")
    void anUnknownTokenRevokesNothing() throws Exception {
        User user = givenUser("EMPLOYEE");
        String good = refreshTokenFor(user);

        assertThat(refresh("not-a-token-this-server-ever-issued").getResponse().getStatus())
                .isEqualTo(401);

        // A bad guess must not be able to end somebody else's session, or an unauthenticated caller
        // could log people out by submitting rubbish.
        assertThat(refresh(good).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("every refusal is the same 401 problem document, whatever the cause")
    void everyRefusalLooksIdentical() throws Exception {
        User user = givenUser("EMPLOYEE");
        String used = refreshTokenFor(user);
        refresh(used);

        for (String token : List.of(used, "unknown-token")) {
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}""".formatted(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid or expired refresh token."))
                    .andExpect(jsonPath("$.title").value("Authentication failed"));
        }
    }

    // ------------------------------------------------------------------ the limits of a chain

    /**
     * The chain cannot be extended by using it, which is the difference between an absolute window and
     * a sliding one.
     *
     * <p>A sliding expiry would mean a session somebody uses every day never expires, so one stolen
     * refresh token would be permanent access. {@code SessionService.rotate} copies
     * {@code refresh_expires_at} forward unchanged, and this asserts the consequence: the successor
     * ends when its predecessor would have, and the lifetime reported to the client shrinks.
     */
    @Test
    @DisplayName("refreshing does not extend the window - the cap is measured from the login")
    void theRefreshWindowIsAbsolute() throws Exception {
        User user = givenUser("EMPLOYEE");
        MvcResult login = login(user);
        Instant originalCap = userSessionRepository.findAll().getFirst().getRefreshExpiresAt();

        MvcResult refreshed = refresh(read(login, "$.refreshToken"));

        UserSession successor = sessionsNewestFirst().getFirst();
        assertThat(successor.getRefreshExpiresAt()).isEqualTo(originalCap);
        assertThat((Integer) read(refreshed, "$.refreshExpiresIn"))
                .isLessThanOrEqualTo((int) securityProperties.refresh().ttl().toSeconds());
    }

    @Test
    @DisplayName("once the chain has expired, a password is required again")
    void anExpiredChainCannotBeRefreshed() throws Exception {
        User user = givenUser("EMPLOYEE");
        String refreshToken = refreshTokenFor(user);

        UserSession session = userSessionRepository.findAll().getFirst();
        session.setRefreshExpiresAt(Instant.now().minusSeconds(1));
        userSessionRepository.save(session);

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * A session issued before V21 has no refresh token, so it cannot be refreshed.
     *
     * <p>Represented here by a null {@code refresh_expires_at}, which is what the migration leaves
     * behind: there was nothing to backfill those rows with. The check fails closed - absent is not
     * treated as unlimited - and the session remains usable as an access-token session until it
     * expires, which is the correct outcome rather than a gap.
     */
    @Test
    @DisplayName("a session with no refresh window is refused rather than treated as unlimited")
    void aMissingRefreshWindowIsNotUnlimited() throws Exception {
        User user = givenUser("EMPLOYEE");
        String refreshToken = refreshTokenFor(user);

        UserSession session = userSessionRepository.findAll().getFirst();
        session.setRefreshExpiresAt(null);
        userSessionRepository.save(session);

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ interaction with revocation

    @Test
    @DisplayName("logging out ends the whole login, so the refresh token stops working too")
    void logoutKillsTheRefreshToken() throws Exception {
        User user = givenUser("EMPLOYEE");
        MvcResult login = login(user);

        mockMvc.perform(authenticated(post("/api/auth/logout"), read(login, "$.accessToken")))
                .andExpect(status().isNoContent());

        // The half that a single-session revoke would have missed: without family revocation the
        // access token would be dead and the refresh token would happily mint a new one.
        assertThat(refresh(read(login, "$.refreshToken")).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * Logging out several refreshes in ends the login, not the slice being held.
     *
     * <p>This is the case that made a single-session revoke wrong. The caller's newest session is the
     * only one revoking by id would have touched, and the refresh token on it is the live one - so the
     * old implementation would have passed {@link #logoutKillsTheRefreshToken} above (one session, no
     * rotations) while leaving a refreshed login fully usable.
     */
    @Test
    @DisplayName("logout after several refreshes revokes every session in the chain")
    void logoutRevokesTheWholeChain() throws Exception {
        User user = givenUser("EMPLOYEE");
        MvcResult login = login(user);

        MvcResult second = refresh(read(login, "$.refreshToken"));
        MvcResult third = refresh(read(second, "$.refreshToken"));

        mockMvc.perform(authenticated(post("/api/auth/logout"), read(third, "$.accessToken")))
                .andExpect(status().isNoContent());

        assertThat(userSessionRepository.findAll())
                .hasSize(3)
                .allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
        assertThat(refresh(read(third, "$.refreshToken")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("changing a password invalidates the refresh token as well as the access token")
    void passwordChangeKillsTheRefreshToken() throws Exception {
        User user = givenUser("EMPLOYEE");
        MvcResult login = login(user);

        mockMvc.perform(authenticated(patch("/api/users/me/password"), read(login, "$.accessToken"))
                        .content("""
                                {"currentPassword":"%s","newPassword":"a-much-longer-password"}"""
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(refresh(read(login, "$.refreshToken")).getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * A deactivated account cannot refresh, and the check has to be here rather than inherited.
     *
     * <p>The per-request path rejects a deactivated user's live sessions by joining {@code users.active}
     * in the authority query. Refresh does not go through that query - it is reached without an access
     * token at all - so without an explicit check a disabled account could keep minting working tokens
     * until the chain expired.
     */
    @Test
    @DisplayName("a deactivated account cannot refresh, even with a valid token")
    void aDeactivatedAccountCannotRefresh() throws Exception {
        User user = givenUser("IT_SUPPORT");
        String refreshToken = refreshTokenFor(user);

        user.setActive(false);
        userRepository.save(user);

        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ concurrency

    /**
     * The primitive that makes a concurrent refresh safe, tested where it actually lives.
     *
     * <p>A single page races itself easily: three requests get 401 together and three refreshes start.
     * The conditional UPDATE inside {@code rotate} is what decides between them - exactly one caller
     * can find {@code refresh_used_at} null and set it, so exactly one gets a successor session and the
     * rest get nothing.
     *
     * <p><strong>Deliberately tested through the service rather than through two HTTP requests.</strong>
     * Driving it from the endpoint cannot reach this branch at all: by the time a second request
     * arrives the row is already marked, so it takes the <em>reuse</em> path and revokes the family -
     * which is the correct response to a token presented twice minutes apart, and the wrong one to two
     * requests presenting it in the same millisecond. Distinguishing them is a question of who wins the
     * UPDATE, and this is that question with the timing taken out: a second call sees the same state a
     * losing concurrent caller would, and the assertion is that it is refused without the family being
     * touched.
     *
     * <p>A genuinely threaded version would assert the same thing less reliably.
     */
    @Test
    @DisplayName("only one caller can claim a refresh token, and the loser revokes nothing")
    void onlyOneCallerCanClaimARefreshToken() throws Exception {
        User user = givenUser("EMPLOYEE");
        tokenFor(user);
        UserSession session = userSessionRepository.findAll().getFirst();

        Instant now = Instant.now();
        Instant accessExpiry = now.plus(30, ChronoUnit.MINUTES);

        assertThat(sessionService.rotate(session, now, accessExpiry,
                refreshTokenService.issue().hash(), null, null))
                .as("the first caller claims the token and gets a successor session")
                .isPresent();

        assertThat(sessionService.rotate(session, now, accessExpiry,
                refreshTokenService.issue().hash(), null, null))
                .as("the second caller finds the token claimed and gets nothing")
                .isEmpty();

        // Two sessions, not three - the loser inserted nothing - and the login is still alive, because
        // losing a race is not evidence of theft.
        assertThat(userSessionRepository.findAll())
                .hasSize(2)
                .allSatisfy(open -> assertThat(open.getRevokedAt()).isNull());
    }
}
