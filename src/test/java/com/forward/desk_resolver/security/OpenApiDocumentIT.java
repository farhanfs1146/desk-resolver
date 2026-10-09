package com.forward.desk_resolver.security;

import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document's description of how to authenticate.
 *
 * <p><strong>Why this is worth a test.</strong> The document had declared no security scheme at all
 * since authentication was introduced in Phase 4. Nothing failed: the API worked, every integration
 * test passed, and the defect lived entirely in the one client the README tells people to use - Swagger
 * UI, which renders its Authorize button only when a scheme is declared, so there was no way to hand
 * it the token that {@code POST /api/auth/login} had just returned.
 *
 * <p>That is the shape of bug this file exists to catch: a documentation contract that no functional
 * test touches, and that a person only discovers by opening the page and looking for a button.
 */
class OpenApiDocumentIT extends AbstractPostgresIT {

    private static final String DOC = "/v3/api-docs";

    @Test
    @DisplayName("the document declares an HTTP bearer scheme, which is what renders Authorize")
    void bearerSchemeIsDeclared() throws Exception {
        mockMvc.perform(get(DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    /**
     * The requirement is global, so the padlock appears on every operation by default.
     *
     * <p>The same reasoning as {@code anyRequest().authenticated()} in the filter chain: an endpoint
     * added later is documented as needing a token without anybody remembering to say so.
     */
    @Test
    @DisplayName("the bearer requirement applies to the whole document, not endpoint by endpoint")
    void theRequirementIsGlobal() throws Exception {
        mockMvc.perform(get(DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }

    @Test
    @DisplayName("login opts out, so Swagger UI does not attach a token to the one public endpoint")
    void loginIsDocumentedAsPublic() throws Exception {
        mockMvc.perform(get(DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/auth/login.post.security").isArray())
                .andExpect(jsonPath("$.paths./api/auth/login.post.security").isEmpty());
    }

    /**
     * Logout does <em>not</em> opt out, unlike login.
     *
     * <p>Easy to get wrong, because the two sit next to each other in {@code AuthController} and read
     * like a pair. They are not: logout acts on the session the token names, so it needs the token.
     */
    @Test
    @DisplayName("logout keeps the bearer requirement - it acts on the caller's own session")
    void logoutRequiresAToken() throws Exception {
        mockMvc.perform(get(DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/auth/logout.post.security").doesNotExist());
    }

    @Test
    @DisplayName("the endpoints added with the auth schema are in the document")
    void theNewEndpointsAreDocumented() throws Exception {
        mockMvc.perform(get(DOC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/roles.get").exists())
                .andExpect(jsonPath("$.paths./api/users/{id}/roles.put").exists())
                .andExpect(jsonPath("$.paths./api/auth/logout.post").exists());
    }

    /**
     * The document itself stays reachable without a token.
     *
     * <p>Declaring a global security requirement describes what the <em>API</em> needs; it must not
     * start gating the description. That is {@code app.security.swagger-public}, which is true here and
     * is meant to be set false in production.
     */
    @Test
    @DisplayName("the document is readable unauthenticated while swagger-public is on")
    void theDocumentItselfIsNotGated() throws Exception {
        mockMvc.perform(get(DOC)).andExpect(status().isOk());
    }
}
