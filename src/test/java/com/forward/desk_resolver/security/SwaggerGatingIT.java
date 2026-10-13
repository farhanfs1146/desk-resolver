package com.forward.desk_resolver.security;

import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code app.security.swagger-public=false} actually does - which is now the shipped default.
 *
 * <p>The flag existed before this test and defaulted to {@code true}, so the only behaviour any suite
 * exercised was the ungated one. Flipping the default without pinning the gated path would have meant
 * shipping, as the default, the branch nothing ran.
 *
 * <p><strong>This class pays for a second application context</strong>, because the property is read
 * when the filter chain is built and {@link AbstractPostgresIT} sets it to {@code true} for every other
 * suite. Two values of one startup-time property cannot share a cached context, so the choice was
 * between an extra context and no coverage. The container is still the one static instance, so the cost
 * is a second Spring startup rather than a second database.
 */
@TestPropertySource(properties = "app.security.swagger-public=false")
class SwaggerGatingIT extends AbstractPostgresIT {

    @Test
    @DisplayName("the OpenAPI document needs a token when swagger-public is off")
    void theDocumentIsGated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the Swagger UI needs a token too, not just the document behind it")
    void theUiIsGated() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The 401 is the application's own problem document, not an empty body.
     *
     * <p>Same invariant the resource-server entry point exists for: a path that is public in one
     * environment and gated in another must refuse in the documented shape, or a client that meets the
     * gated deployment gets a blank 401 it cannot report usefully.
     */
    @Test
    @DisplayName("the refusal is an RFC 7807 document like every other 401")
    void theRefusalIsAProblemDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").exists());
    }

    /** With a token it is readable again - the flag gates it, it does not remove it. */
    @Test
    @DisplayName("an authenticated caller can still read the document")
    void authenticatedAccessStillWorks() throws Exception {
        String token = tokenFor(givenUser("EMPLOYEE"));

        mockMvc.perform(authenticated(get("/v3/api-docs"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }
}
