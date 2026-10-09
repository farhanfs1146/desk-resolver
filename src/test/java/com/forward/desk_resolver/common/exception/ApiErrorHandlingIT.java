package com.forward.desk_resolver.common.exception;

import com.forward.desk_resolver.entity.Application;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error responses.
 *
 * <p>Every case here used to be an HTTP 500, because there was no {@code @ControllerAdvice} at all: a
 * caller could not tell their own mistake from a server fault, and monitoring could not tell routine 404
 * noise from a real incident.
 *
 * <p>The subtle risk this class guards is specific to how the advice is built. {@code handleUnexpected}
 * matches {@code Exception}, and {@code ExceptionHandlerExceptionResolver} runs before
 * {@code DefaultHandlerExceptionResolver}, so the catch-all intercepts Spring MVC's own protocol
 * exceptions - which Spring would otherwise map to the correct 4xx itself. Each status below therefore
 * has to be asserted: a regression would not throw, it would quietly return 500.
 */
class ApiErrorHandlingIT extends AbstractPostgresIT {

    @Test
    @DisplayName("an unknown id is 404 with a problem document")
    void notFound() throws Exception {
        mockMvc.perform(authenticated(get("/api/applications/999999"), tokenFor(givenUser("ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    @DisplayName("an unmapped path is 404, not 500")
    void unknownPath() throws Exception {
        mockMvc.perform(authenticated(get("/api/does-not-exist"), tokenFor(givenUser("ADMIN"))))
                .andExpect(status().isNotFound());
    }

    /**
     * RFC 9110 requires {@code Allow} on a 405, so a client is told what the path does support rather
     * than having to guess.
     */
    @Test
    @DisplayName("the wrong method is 405 and advertises what is allowed")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(authenticated(delete("/api/tickets/1"), tokenFor(givenUser("ADMIN"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("an unreadable content type is 415")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .header("Authorization", "Bearer " + tokenFor(givenUser("EMPLOYEE")))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("an unsatisfiable Accept header is 406")
    void notAcceptable() throws Exception {
        mockMvc.perform(authenticated(get("/api/applications"), tokenFor(givenUser("ADMIN")))
                        .accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable());
    }

    /**
     * Spring MVC has a correct 400 for this, but the catch-all claimed it first and turned a caller's
     * missing parameter into a server error.
     */
    @Test
    @DisplayName("a missing required parameter is 400 and names the parameter")
    void missingParameter() throws Exception {
        mockMvc.perform(authenticated(patch("/api/tickets/1/status"), tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing parameter"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("an invalid enum in a parameter is 400")
    void invalidEnumParameter() throws Exception {
        mockMvc.perform(authenticated(
                        patch("/api/tickets/1/status").param("status", "NOT_A_STATUS"),
                        tokenFor(givenUser("IT_SUPPORT"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an invalid enum in the body is 400")
    void invalidEnumInBody() throws Exception {
        Application application = givenApplication();

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("""
                                {"title":"t","description":"d","issueType":"NOT_A_TYPE","priority":"HIGH",
                                 "applicationId":%d,"moduleName":"m"}""".formatted(application.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    @DisplayName("malformed JSON is 400")
    void malformedJson() throws Exception {
        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a non-numeric path variable is 400, not 500")
    void badPathVariableType() throws Exception {
        mockMvc.perform(authenticated(get("/api/tickets/not-a-number"), tokenFor(givenUser("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("field violations are reported per field so a form can mark them")
    void validationViolationsAreItemised() throws Exception {
        Application application = givenApplication();

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("""
                                {"title":"","description":"","issueType":null,"priority":null,
                                 "applicationId":null,"moduleName":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations.title").value("Title is required"))
                .andExpect(jsonPath("$.violations.description").value("Description is required"))
                .andExpect(jsonPath("$.violations.issueType").value("Issue type is required"))
                .andExpect(jsonPath("$.violations.priority").value("Priority is required"))
                .andExpect(jsonPath("$.violations.applicationId").value("Application id is required"))
                .andExpect(jsonPath("$.violations.moduleName").value("Module name is required"));

        // Not a silent success.
        assertThat(ticketRepository.count()).isZero();
        assertThat(application.getId()).isNotNull();
    }

    @Test
    @DisplayName("an over-long title is rejected by length, not by the database")
    void lengthViolation() throws Exception {
        Application application = givenApplication();

        mockMvc.perform(authenticated(post("/api/tickets"), tokenFor(givenUser("EMPLOYEE")))
                        .content("""
                                {"title":"%s","description":"d","issueType":"BUG","priority":"LOW",
                                 "applicationId":%d,"moduleName":"m"}"""
                                .formatted("x".repeat(101), application.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.title").exists());
    }

    @Test
    @DisplayName("an invalid sort property or direction is 400")
    void invalidSort() throws Exception {
        String token = tokenFor(givenUser("IT_SUPPORT"));

        mockMvc.perform(authenticated(get("/api/tickets").param("sort", "passwordHash"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid reference"));

        mockMvc.perform(authenticated(get("/api/tickets").param("sort", "priority,descending"), token))
                .andExpect(status().isBadRequest());
    }

    /**
     * Every error in the application - including the 401 and 403 produced inside the security filter
     * chain, which never reach the advice - uses the same document shape, so a client parses one format.
     */
    @Test
    @DisplayName("security errors use the same problem shape as application errors")
    void problemShapeIsUniform() throws Exception {
        // 401 and 403 are written by hand inside the filter chain, where the @ControllerAdvice cannot
        // reach; 404 is serialized by Spring from a ProblemDetail. Comparing the member names rather than
        // looking for a few of them is what catches a divergence - this assertion is how the stray
        // "type":"about:blank" in the hand-written document was found.
        Set<String> unauthorized = members(mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString());
        Set<String> forbidden = members(
                mockMvc.perform(authenticated(get("/api/users"), tokenFor(givenUser("EMPLOYEE"))))
                        .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString());
        Set<String> notFound = members(
                mockMvc.perform(authenticated(get("/api/applications/999999"), tokenFor(givenUser("ADMIN"))))
                        .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());

        assertThat(unauthorized).containsExactlyInAnyOrderElementsOf(notFound);
        assertThat(forbidden).containsExactlyInAnyOrderElementsOf(notFound);
        assertThat(notFound).containsExactlyInAnyOrder("title", "status", "detail", "instance");
    }

    /** The top-level member names of a JSON object. */
    private static Set<String> members(String json) {
        Map<String, Object> parsed = com.jayway.jsonpath.JsonPath.read(json, "$");
        return parsed.keySet();
    }

    @Test
    @DisplayName("no error response leaks a stack trace, SQL or an internal class name")
    void errorsRevealNoInternals() throws Exception {
        String token = tokenFor(givenUser("ADMIN"));

        for (var request : java.util.List.of(
                authenticated(get("/api/applications/999999"), token),
                authenticated(get("/api/does-not-exist"), token),
                authenticated(get("/api/tickets/not-a-number"), token),
                authenticated(delete("/api/tickets/1"), token))) {
            String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("com.forward.desk_resolver")
                    .doesNotContain("org.hibernate")
                    .doesNotContain("org.springframework")
                    .doesNotContain("Exception")
                    .doesNotContain("select ")
                    .doesNotContain("at java.");
        }
    }

    @Test
    @DisplayName("deactivating an application answers 204 and is a soft delete")
    void deactivationIsSoftAndAnswers204() throws Exception {
        Application application = givenApplication();
        String token = tokenFor(givenUser("ADMIN"));

        mockMvc.perform(authenticated(delete("/api/applications/" + application.getId()), token))
                .andExpect(status().isNoContent());

        // The row survives; only the flag changed.
        mockMvc.perform(authenticated(get("/api/applications/" + application.getId()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(authenticated(get("/api/applications/active"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }
}
