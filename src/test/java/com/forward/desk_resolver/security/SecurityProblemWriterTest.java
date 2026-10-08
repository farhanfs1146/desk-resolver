package com.forward.desk_resolver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The problem document written from inside the security filter chain.
 *
 * <p>This class builds JSON by hand - deliberately, so a misconfigured serializer cannot break the 401
 * path - which makes escaping its own responsibility and therefore worth testing. Every assertion here
 * parses the output with a real JSON parser rather than matching substrings, because the failure being
 * guarded against is output that is not valid JSON at all.
 */
class SecurityProblemWriterTest {

    private final SecurityProblemWriter writer = new SecurityProblemWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    private MockHttpServletResponse write(HttpStatus status, String title, String detail, String instance)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.write(response, status, title, detail, instance);
        return response;
    }

    @Test
    @DisplayName("writes an RFC 7807 document with the problem+json content type")
    void writesProblemDocument() throws Exception {
        MockHttpServletResponse response = write(
                HttpStatus.UNAUTHORIZED, "Authentication required",
                "A valid access token is required to call this endpoint.", "/api/tickets");

        assertThat(response.getStatus()).isEqualTo(401);
        // The writer sets the charset explicitly, so compare the media type without its parameters.
        assertThat(MediaType.parseMediaType(response.getContentType()))
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");

        var document = mapper.readTree(response.getContentAsString());
        // No "type": Spring's ProblemDetail omits it for the default about:blank, and these responses
        // must have the same shape as the ones the @ControllerAdvice produces.
        assertThat(document.has("type")).isFalse();
        assertThat(document.get("title").asText()).isEqualTo("Authentication required");
        assertThat(document.get("status").asInt()).isEqualTo(401);
        assertThat(document.get("detail").asText())
                .isEqualTo("A valid access token is required to call this endpoint.");
        assertThat(document.get("instance").asText()).isEqualTo("/api/tickets");
    }

    @Test
    @DisplayName("403 uses the same shape as 401")
    void forbiddenHasSameShape() throws Exception {
        MockHttpServletResponse response = write(
                HttpStatus.FORBIDDEN, "Access denied",
                "You do not have permission to perform this operation.", "/api/users");

        var document = mapper.readTree(response.getContentAsString());
        assertThat(document.get("status").asInt()).isEqualTo(403);
        assertThat(document.has("title")).isTrue();
        assertThat(document.has("detail")).isTrue();
        assertThat(document.has("instance")).isTrue();
    }

    /**
     * {@code instance} is {@code request.getRequestURI()} - caller-supplied. A quote or backslash that
     * reached the output unescaped would terminate the JSON string early and produce a document the
     * client cannot parse.
     */
    @Test
    @DisplayName("quotes and backslashes in the request path stay inside the string")
    void escapesQuotesAndBackslashes() throws Exception {
        String hostile = "/api/\"injected\":\"value\"/\\path";

        MockHttpServletResponse response = write(
                HttpStatus.UNAUTHORIZED, "Authentication required", "Token required.", hostile);

        var document = mapper.readTree(response.getContentAsString());
        assertThat(document.get("instance").asText()).isEqualTo(hostile);
        // The injected pair must be data inside instance, not a field of its own.
        assertThat(document.has("injected")).isFalse();
        assertThat(document.size()).isEqualTo(4);
    }

    /**
     * Regression test. The previous implementation escaped only backslash, quote, CR and LF, carrying a
     * comment asserting that none of its inputs were user data. A tab, a form feed or any other C0
     * character therefore reached the output raw, which is invalid JSON - so a caller hitting a 401
     * received a parser error instead of being told to authenticate.
     */
    @Test
    @DisplayName("every control character is escaped, not an enumerated few")
    void escapesAllControlCharacters() throws Exception {
        StringBuilder path = new StringBuilder("/api/");
        for (char c = 0x01; c < 0x20; c++) {
            path.append(c);
        }

        MockHttpServletResponse response = write(
                HttpStatus.UNAUTHORIZED, "Authentication required", "Token required.", path.toString());

        String body = response.getContentAsString();
        // Parses at all - this is what used to fail.
        var document = mapper.readTree(body);
        assertThat(document.get("instance").asText()).isEqualTo(path.toString());

        // No raw control character survived into the serialized form.
        for (int i = 0; i < body.length(); i++) {
            assertThat(body.charAt(i))
                    .describedAs("raw control character 0x%02X at index %d", (int) body.charAt(i), i)
                    .isGreaterThanOrEqualTo((char) 0x20);
        }
    }

    @Test
    @DisplayName("a newline in the path does not break the document")
    void escapesNewlines() throws Exception {
        MockHttpServletResponse response = write(
                HttpStatus.FORBIDDEN, "Access denied", "Denied.", "/api/a\nb\rc\td");

        var document = mapper.readTree(response.getContentAsString());
        assertThat(document.get("instance").asText()).isEqualTo("/api/a\nb\rc\td");
    }

    @Test
    @DisplayName("null inputs become empty strings rather than the text null")
    void nullsBecomeEmptyStrings() throws Exception {
        MockHttpServletResponse response = write(HttpStatus.UNAUTHORIZED, null, null, null);

        var document = mapper.readTree(response.getContentAsString());
        assertThat(document.get("title").asText()).isEmpty();
        assertThat(document.get("detail").asText()).isEmpty();
        assertThat(document.get("instance").asText()).isEmpty();
    }

    @Test
    @DisplayName("non-ASCII paths survive intact")
    void handlesNonAscii() throws Exception {
        MockHttpServletResponse response = write(
                HttpStatus.UNAUTHORIZED, "Authentication required", "Token required.", "/api/راستہ");

        var document = mapper.readTree(response.getContentAsString());
        assertThat(document.get("instance").asText()).isEqualTo("/api/راستہ");
    }
}
