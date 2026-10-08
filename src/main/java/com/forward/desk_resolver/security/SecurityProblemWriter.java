package com.forward.desk_resolver.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes RFC 7807 problem documents from inside the security filter chain.
 *
 * <p>Security failures happen before Spring MVC's exception handling is reachable, so
 * {@code GlobalExceptionHandler} never sees them. Rather than let those responses fall back to a
 * different shape, this writes the same {@code title/status/detail/instance} document by hand, so
 * a client parses 401 and 403 exactly as it parses 404 and 409.
 *
 * <p>Built as a literal string rather than through an {@code ObjectMapper} on purpose: it keeps the
 * writer free of any dependency on the application's JSON configuration, which is the sort of coupling
 * that turns a misconfigured serializer into a broken error response. The cost of that choice is that
 * escaping becomes this class's own responsibility - see {@link #escape} - and that matching Spring's
 * own serialization is a thing to get right by hand.
 *
 * <p><strong>No {@code type} member, deliberately.</strong> This document used to carry
 * {@code "type":"about:blank"}, which Spring's {@code ProblemDetail} serializer omits, because
 * {@code about:blank} is what RFC 7807 says an absent {@code type} means. The result was that 401 and 403
 * had one shape and every other error had another - the precise divergence this class exists to prevent,
 * hidden in the one line that looked most like boilerplate. Omitting it loses no information and makes
 * the shapes identical.
 */
@Component
public class SecurityProblemWriter {

    public void write(HttpServletResponse response,
                      HttpStatus status,
                      String title,
                      String detail,
                      String instance) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(escape(title), status.value(), escape(detail), escape(instance)));
    }

    /**
     * JSON string escaping.
     *
     * <p>{@code title} and {@code detail} are application-authored constants, but {@code instance} is
     * {@code request.getRequestURI()} - a caller-supplied path. The previous version of this method
     * carried a comment asserting that none of these inputs were user data, and escaped only
     * backslash, quote, CR and LF to match. Any other control character would have been written raw
     * into the document, which is invalid JSON: a client hitting a 401 would get a parser error
     * instead of being told to authenticate.
     *
     * <p>So the whole C0 range is escaped, not an enumerated few.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04X", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
