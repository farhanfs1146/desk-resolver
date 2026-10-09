package com.forward.desk_resolver.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/**
 * The security filter chain, authority wiring and crypto primitives.
 *
 * <p><strong>Model: JWT bearer tokens, with a server-side session registry.</strong> Bearer tokens were
 * chosen over HTTP sessions because the API is consumed by a browser SPA and the application is meant
 * to scale horizontally - no sticky routing, no shared session store, and the filter chain below is
 * still {@code STATELESS}: no cookie is set and no {@code HttpSession} is ever created.
 *
 * <p>What changed in Phase 7 is that a token now names a row in {@code auth.sessions} through its
 * {@code sid} claim, and {@link DatabaseAuthoritiesConverter} resolves it on every request. The
 * signature, expiry and issuer checks happen first and are unchanged; the session lookup is a second
 * gate, and it is what makes logout real and lets a password change or a deactivation end a session
 * that already exists. That lookup is the same query that resolves the caller's permissions from
 * {@code auth.user_roles}, so revocation did not cost a round trip of its own.
 *
 * <p>Tokens also remain the seam an external identity provider would replace: that becomes a change to
 * {@link #jwtDecoder} plus retiring the login endpoint, with no effect on business services. An
 * external issuer would mint no {@code sid}, so the session gate would have to be rethought at the
 * same time - which is the honest cost of having made it a gate.
 *
 * <p><strong>CSRF is disabled, deliberately and for a specific reason.</strong> CSRF protection exists
 * because browsers attach ambient credentials - cookies - to cross-site requests automatically. This
 * API authenticates only via an {@code Authorization: Bearer} header, which a browser never attaches on
 * its own, so there is no ambient credential for an attacker to ride. No cookie or session is created
 * anywhere. Were cookie authentication ever introduced, CSRF protection would have to come back with
 * it.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final MacAlgorithm JWS_ALGORITHM = MacAlgorithm.HS256;

    /** Endpoints reachable without authentication. Everything not listed here requires a token. */
    private static final String[] ALWAYS_PUBLIC = {
            "/api/auth/login",
            "/error"
    };

    private static final String[] SWAGGER_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain apiSecurity(
            HttpSecurity http,
            DatabaseAuthoritiesConverter authoritiesConverter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // See the class comment: bearer-only API, no ambient credentials, no sessions.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(ALWAYS_PUBLIC).permitAll();
                    if (properties.swaggerPublic()) {
                        requests.requestMatchers(SWAGGER_PATHS).permitAll();
                    }
                    // CORS preflight carries no credentials and must not be challenged.
                    requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    // Default deny: any endpoint added later is protected until someone says otherwise.
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        // The entry point has to be named HERE as well as in exceptionHandling below,
                        // and the duplication is not redundant. Two different filters produce a 401:
                        //
                        //   * No token at all -> BearerTokenAuthenticationFilter never attempts
                        //     authentication, the AuthorizationFilter denies, and
                        //     ExceptionTranslationFilter uses the entry point from exceptionHandling.
                        //   * A token that is present but unusable -> BearerTokenAuthenticationFilter
                        //     fails and calls ITS OWN entry point, which defaults to
                        //     BearerTokenAuthenticationEntryPoint and is not affected by
                        //     exceptionHandling at all.
                        //
                        // Leaving the default in place had two consequences, and the second is why this
                        // line exists. The response body was EMPTY, breaking the project-wide invariant
                        // that every error - 401 included - is an RFC 7807 problem document; and the
                        // default writes WWW-Authenticate: Bearer error="invalid_token",
                        // error_description="...", which says out loud whether the token was malformed,
                        // expired or badly signed. That was tolerable while an unusable token meant a
                        // broken client. Since V17 it is routine - a logged-out or revoked session
                        // arrives here - so the 401 a client actually meets has to be the documented
                        // shape, and it has to reveal no more than any other.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authoritiesConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // Header policy. Spring Security's defaults already cover the ones that matter most for
                // an API, so this block adds one header and otherwise leaves them alone - see the
                // "Security headers" section of docs/DECISIONS.md for what was reviewed and rejected.
                .headers(headers -> headers
                        // Already a default; kept explicit because this is an API that should never be
                        // framed.
                        .frameOptions(frame -> frame.deny())
                        // Already a default; stops a browser second-guessing our declared content type.
                        .contentTypeOptions(options -> {
                        })
                        // Added in Phase 5. Not a Spring Security default. Cheap, and it stops a URL
                        // containing a ticket or user id leaking to third parties through the Referer
                        // header. Safe for both the JSON API and the Swagger UI, neither of which
                        // depends on referrer information.
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));

        return http.build();
    }

    /**
     * BCrypt, through Spring Security's own encoder.
     *
     * <p>No custom cryptography, no reversible encryption, and no plaintext. BCrypt is deliberately
     * slow, which is the property that matters for a password verifier.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private SecretKey signingKey() {
        if (properties.jwt().hasSecret()) {
            byte[] keyBytes = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < SecurityProperties.Jwt.MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException(
                        "app.security.jwt.secret must be at least "
                                + SecurityProperties.Jwt.MINIMUM_SECRET_BYTES
                                + " bytes for HS256; it is " + keyBytes.length
                                + ". Configure a longer secret via the environment.");
            }
            return new SecretKeySpec(keyBytes, JWS_ALGORITHM.getName());
        }

        // No secret configured: generate a strong ephemeral one rather than falling back to a
        // predictable default. Secure, but deliberately noisy, because tokens will not survive a
        // restart and will not validate across instances.
        byte[] random = new byte[SecurityProperties.Jwt.MINIMUM_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        log.warn("""
                app.security.jwt.secret is not configured. A random signing key was generated for this \
                JVM. Access tokens will be invalidated by a restart and will not validate on another \
                instance. Set app.security.jwt.secret (for example from the APP_JWT_SECRET environment \
                variable) in any shared or production environment.""");
        return new SecretKeySpec(random, JWS_ALGORITHM.getName());
    }

    /** Shared so the encoder and decoder cannot drift apart. */
    @Bean
    public SecretKey jwtSigningKey() {
        return signingKey();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * Validates signature, expiry and issuer.
     *
     * <p>This is the single seam an external identity provider would replace: point the decoder at the
     * provider's JWKS endpoint and nothing else in the application changes.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(JWS_ALGORITHM)
                .build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Exact origins only. A wildcard origin combined with credentials is rejected by browsers and
        // is a misconfiguration regardless.
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Without this a browser client cannot read the pagination metadata added in Phase 3.
        configuration.setExposedHeaders(List.of(
                "X-Total-Count", "X-Total-Pages", "X-Page-Number", "X-Page-Size", "X-Has-Next"));
        configuration.setAllowCredentials(properties.cors().allowCredentials());
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
