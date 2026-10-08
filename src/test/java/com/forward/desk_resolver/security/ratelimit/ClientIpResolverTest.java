package com.forward.desk_resolver.security.ratelimit;

import com.forward.desk_resolver.security.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving the address an attempt came from.
 *
 * <p>The naive implementation - read {@code X-Forwarded-For} - is a rate-limiter bypass, because any
 * client can send that header with any value. These tests pin down both halves of the policy: ignore it
 * entirely by default, and when it is trusted, read the entry a proxy appended rather than one the
 * client supplied.
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(boolean trustForwardedHeaders) {
        return new ClientIpResolver(new SecurityProperties(null, null, null,
                new SecurityProperties.RateLimit(true, 5, 20,
                        Duration.ofMinutes(15), Duration.ofMinutes(15), 1_000, trustForwardedHeaders),
                true));
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    @DisplayName("by default the socket address is used and the header is ignored")
    void defaultsToSocketAddress() {
        var result = resolver(false).resolve(request("198.51.100.5", "10.0.0.1"));

        assertThat(result).isEqualTo("198.51.100.5");
    }

    /**
     * The security property of the default. Were the header believed, an attacker would defeat
     * per-address throttling by varying one string, and could pin the blame on somebody else's address
     * and have them throttled instead.
     */
    @Test
    @DisplayName("a spoofed header cannot shift or escape the address budget")
    void spoofedHeaderIsIgnoredByDefault() {
        var resolver = resolver(false);

        assertThat(resolver.resolve(request("198.51.100.5", "1.1.1.1")))
                .isEqualTo(resolver.resolve(request("198.51.100.5", "2.2.2.2")))
                .isEqualTo("198.51.100.5");
    }

    @Test
    @DisplayName("when trusted, the right-most entry is read: the one the proxy appended")
    void trustsRightmostEntry() {
        // A client can prefill the header with any number of fake entries; a proxy that appends puts the
        // only trustworthy value last. Taking the left-most entry - the common mistake - reads
        // attacker-controlled data.
        var result = resolver(true).resolve(
                request("10.0.0.1", "203.0.113.9, 192.0.2.44, 198.51.100.5"));

        assertThat(result).isEqualTo("198.51.100.5");
    }

    @Test
    @DisplayName("a single-entry header is used as-is when trusted")
    void trustsSingleEntry() {
        assertThat(resolver(true).resolve(request("10.0.0.1", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("falls back to the socket address when a trusted header is absent or blank")
    void fallsBackWhenHeaderUnusable() {
        assertThat(resolver(true).resolve(request("10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(resolver(true).resolve(request("10.0.0.1", "   "))).isEqualTo("10.0.0.1");
        assertThat(resolver(true).resolve(request("10.0.0.1", "203.0.113.9,  "))).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("a request with no resolvable address still produces a usable key")
    void unknownAddress() {
        // The limiter keys on this string, so it must never be null.
        assertThat(resolver(false).resolve(request(null, null))).isEqualTo("unknown");
        assertThat(resolver(false).resolve(null)).isEqualTo("unknown");
    }
}
