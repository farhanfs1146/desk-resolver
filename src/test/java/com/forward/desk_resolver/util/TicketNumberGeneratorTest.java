package com.forward.desk_resolver.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ticket number formatting.
 *
 * <p>Uniqueness is the database's job - {@code nextval} on {@code ticket_number_seq} - and is covered by
 * {@code TicketLifecycleIT}, which checks the generated numbers against a real sequence. What is left to
 * test here is the format, and specifically that it never truncates: the previous scheme was
 * {@code "TKT-" + System.currentTimeMillis()}, where 200 concurrent calls produced only 68 distinct
 * values.
 */
class TicketNumberGeneratorTest {

    @Test
    @DisplayName("pads to the minimum width")
    void padsToMinimumWidth() {
        assertThat(TicketNumberGenerator.format(1)).isEqualTo("TKT-00000001");
        assertThat(TicketNumberGenerator.format(42)).isEqualTo("TKT-00000042");
        assertThat(TicketNumberGenerator.format(99_999_999L)).isEqualTo("TKT-99999999");
    }

    /**
     * {@code %0Nd} pads but never truncates, so the sequence is not capped by the format. If it did
     * truncate, number 100000001 would collide with number 1 and violate
     * {@code tickets_ticket_number_key}.
     */
    @Test
    @DisplayName("grows wider rather than truncating past the minimum width")
    void growsBeyondMinimumWidth() {
        assertThat(TicketNumberGenerator.format(100_000_000L)).isEqualTo("TKT-100000000");
        assertThat(TicketNumberGenerator.format(1_234_567_890_123L)).isEqualTo("TKT-1234567890123");
        assertThat(TicketNumberGenerator.format(Long.MAX_VALUE))
                .isEqualTo("TKT-" + Long.MAX_VALUE);
    }

    @Test
    @DisplayName("distinct sequence values always give distinct ticket numbers")
    void distinctValuesNeverCollide() {
        assertThat(TicketNumberGenerator.format(1)).isNotEqualTo(TicketNumberGenerator.format(100_000_001L));
        assertThat(TicketNumberGenerator.format(99_999_999L))
                .isNotEqualTo(TicketNumberGenerator.format(999_999_990L));
    }

    @Test
    @DisplayName("the external shape is TKT- followed by digits, so old and new numbers both match")
    void matchesTheDocumentedPattern() {
        // Pre-sequence rows hold epoch-millisecond values; both forms must satisfy TKT-\d+ so no data
        // migration was needed when the generator changed.
        assertThat(TicketNumberGenerator.format(7)).matches("TKT-\\d+");
        assertThat(TicketNumberGenerator.format(1_769_000_000_000L)).matches("TKT-\\d+");
        assertThat(TicketNumberGenerator.PREFIX).isEqualTo("TKT-");
    }

    @Test
    @DisplayName("the digit section is at least the declared minimum width")
    void digitWidthHonoursTheConstant() {
        String formatted = TicketNumberGenerator.format(5);

        assertThat(formatted.substring(TicketNumberGenerator.PREFIX.length()))
                .hasSizeGreaterThanOrEqualTo(TicketNumberGenerator.MIN_DIGITS);
    }
}
