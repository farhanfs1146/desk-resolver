package com.forward.desk_resolver.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the API's authentication scheme <em>in the OpenAPI document</em>.
 *
 * <p><strong>This configuration documents; it does not enforce.</strong> Enforcement is
 * {@link SecurityConfig} and the {@code @PreAuthorize} expressions on the controllers, and nothing
 * here can grant or withhold access. The two are kept deliberately separate, and this comment says so
 * because a class named "SecurityScheme" in a config bean invites the opposite assumption.
 *
 * <p><strong>Why it was needed.</strong> Without it, {@code components.securitySchemes} was empty, and
 * Swagger UI renders its <em>Authorize</em> button only when the document declares at least one scheme.
 * springdoc cannot infer one: it does not read the filter chain, so nothing told it that this API
 * authenticates with {@code Authorization: Bearer}. The effect was that the UI the README points
 * people at could reach exactly one endpoint - {@code POST /api/auth/login} - and every other call
 * answered 401 with no way to supply the token login had just returned. The API was fine; the only
 * interactive client for it was not.
 *
 * <p>The requirement is added <strong>globally</strong> rather than per operation, so a new endpoint is
 * documented as requiring a token by default. That mirrors the filter chain's own
 * {@code anyRequest().authenticated()}: in both places, the safe state is what you get by writing
 * nothing. The single public endpoint opts out explicitly with {@code @SecurityRequirements} on
 * {@code AuthController.login}, which is one annotation on the one operation that needs it rather than
 * an annotation on every operation that does not.
 */
@Configuration
public class OpenApiConfig {

    /** The scheme's key in the document; referenced by the opt-out on the login operation. */
    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI deskResolverOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("desk-resolver API")
                        .version("v1")
                        .description("""
                                Internal IT support ticketing API.

                                Authenticate with POST /api/auth/login, then paste the returned \
                                accessToken into Authorize above - the value only, without the \
                                "Bearer " prefix, which Swagger UI adds itself.

                                Endpoints assert permissions rather than roles, and a user holds any \
                                number of roles whose permissions are unioned. GET /api/roles is the \
                                authoritative role-to-permission table. A token names a session, so \
                                POST /api/auth/logout stops it working immediately rather than at \
                                expiry.

                                Errors are RFC 7807 problem documents, 401 and 403 included."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                // Documentation only - a JWT is what this application issues, but the
                                // resource server validates the token rather than believing this.
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/auth/login. Expires in 30 "
                                        + "minutes by default, and stops working as soon as its "
                                        + "session is revoked by logout, a password change or the "
                                        + "account being deactivated.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
