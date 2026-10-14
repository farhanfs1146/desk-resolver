package com.forward.desk_resolver.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the application reacts to the JWT secret it was given, including not being given one.
 *
 * <p>A unit test rather than an integration test on purpose: the behaviour under examination is a
 * <em>refusal to start</em>, and asserting that through {@code @SpringBootTest} means asserting on a
 * context that failed to come up - which proves a bean failed, not which one or why. Calling the bean
 * method directly names the component and reads its message.
 */
class JwtSigningKeyTest {

    private static final String VALID_SECRET = "a-signing-key-that-is-32-bytes!!";

    private static SecurityConfig configuredWith(String secret, boolean allowGenerated) {
        return new SecurityConfig(new SecurityProperties(
                new SecurityProperties.Jwt(secret, null, null, allowGenerated),
                null, null, null, null, null, false));
    }

    @Test
    @DisplayName("a configured secret of at least 32 bytes is used as the HS256 key")
    void aValidSecretIsAccepted() {
        SecretKey key = configuredWith(VALID_SECRET, false).jwtSigningKey();

        assertThat(key.getAlgorithm()).isEqualTo(SecurityConfig.JWS_ALGORITHM.getName());
        assertThat(key.getEncoded()).hasSize(SecurityProperties.Jwt.MINIMUM_SECRET_BYTES);
    }

    /**
     * The length check is not cosmetic: HS256 with a key shorter than its digest weakens the signature,
     * and Nimbus refuses such a key at signing time - which would be a 500 on the login endpoint rather
     * than a configuration error at startup.
     */
    @Test
    @DisplayName("a secret shorter than 32 bytes is refused, and the message says how short it is")
    void aShortSecretIsRefused() {
        assertThatThrownBy(() -> configuredWith("too-short", false).jwtSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.secret")
                .hasMessageContaining("at least 32")
                .hasMessageContaining("it is 9");
    }

    /**
     * The point of the change. Previously this generated a random key and logged a warning, so a
     * deployment with no secret started, issued tokens, and then produced 401s that outlived the log
     * line explaining them.
     */
    @Test
    @DisplayName("no secret and no opt-in is a startup failure, not a warning")
    void aMissingSecretIsRefusedByDefault() {
        assertThatThrownBy(() -> configuredWith(null, false).jwtSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.secret is not configured")
                // The message has to carry the way out, or it is a dead end for whoever meets it.
                .hasMessageContaining("APP_JWT_SECRET")
                .hasMessageContaining("app.security.jwt.allow-generated-secret=true");
    }

    @Test
    @DisplayName("a blank secret counts as missing rather than as a one-character key")
    void aBlankSecretIsTreatedAsMissing() {
        assertThatThrownBy(() -> configuredWith("   ", false).jwtSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not configured");
    }

    @Test
    @DisplayName("the opt-in restores the generated key, at full strength")
    void theOptInGeneratesAKey() {
        SecretKey key = configuredWith(null, true).jwtSigningKey();

        assertThat(key.getEncoded()).hasSize(SecurityProperties.Jwt.MINIMUM_SECRET_BYTES);
        assertThat(key.getAlgorithm()).isEqualTo(SecurityConfig.JWS_ALGORITHM.getName());
    }

    /**
     * Two JVMs would not agree on the key, which is the whole reason the opt-in exists and has to be
     * deliberate. Asserting it here keeps the flag's cost visible next to its convenience.
     */
    @Test
    @DisplayName("each generated key is different, so tokens do not survive a restart")
    void generatedKeysAreNotStable() {
        byte[] first = configuredWith(null, true).jwtSigningKey().getEncoded();
        byte[] second = configuredWith(null, true).jwtSigningKey().getEncoded();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a configured secret wins over the opt-in rather than being ignored because of it")
    void aConfiguredSecretBeatsTheOptIn() {
        SecretKey key = configuredWith(VALID_SECRET, true).jwtSigningKey();

        assertThat(key.getEncoded()).isEqualTo(VALID_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the encoder and decoder are built from the one key, so they cannot drift apart")
    void encoderAndDecoderShareTheKey() {
        SecurityConfig config = configuredWith(VALID_SECRET, false);
        SecretKey key = config.jwtSigningKey();

        assertThatCode(() -> {
            config.jwtEncoder(key);
            config.jwtDecoder(key);
        }).doesNotThrowAnyException();
    }
}
