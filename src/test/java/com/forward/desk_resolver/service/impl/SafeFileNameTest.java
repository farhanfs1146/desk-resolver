package com.forward.desk_resolver.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a client-supplied filename before it is stored and displayed.
 *
 * <p><strong>This is not the path-traversal defence</strong> - {@code LocalAttachmentStorage} generates
 * every path, so the name never reaches the filesystem. What is being tested is what the string itself
 * can do once it is in a database column and, later, in a {@code Content-Disposition} header: mislead a
 * reader by looking like a location, inject a header by carrying a {@code CR}, or break an insert by
 * exceeding the column.
 */
class SafeFileNameTest {

    @ParameterizedTest
    @CsvSource({
            "payslip.png,                        payslip.png",
            "../../../../etc/passwd,             passwd",
            "..\\..\\windows\\system32\\cmd.exe, cmd.exe",
            "/absolute/path/report.pdf,          report.pdf",
            "C:\\Users\\me\\Desktop\\notes.txt,  notes.txt",
            "folder/sub/file.csv,                file.csv",
    })
    @DisplayName("only the last path segment survives, so a name cannot read as a location")
    void onlyTheLastSegmentIsKept(String submitted, String expected) {
        assertThat(TicketAttachmentServiceImpl.safeFileName(submitted.trim()))
                .isEqualTo(expected.trim());
    }

    /**
     * Control characters are removed, which is what stops header injection on download.
     *
     * <p>The name ends up in {@code Content-Disposition}. A {@code CR} or {@code LF} there would end
     * the header and begin another one of the attacker's choosing. {@code ContentDisposition} in the
     * controller also encodes the value, so this is the second of two independent reasons it cannot
     * happen - and the cheaper one, because it means the stored data is clean rather than only its
     * rendering.
     */
    @Test
    @DisplayName("newlines and control characters are stripped, not encoded")
    void controlCharactersAreStripped() {
        assertThat(TicketAttachmentServiceImpl.safeFileName("inject\r\nX-Evil: yes.png"))
                .isEqualTo("injectX-Evil: yes.png");
        assertThat(TicketAttachmentServiceImpl.safeFileName("tab\there.png"))
                .isEqualTo("tabhere.png");
        assertThat(TicketAttachmentServiceImpl.safeFileName("nul\u0000byte.png"))
                .isEqualTo("nulbyte.png");
        assertThat(TicketAttachmentServiceImpl.safeFileName("del\u007Fchar.png"))
                .isEqualTo("delchar.png");
    }

    /** Quotes and backslashes go, because both are the quoting syntax of the header they land in. */
    @Test
    @DisplayName("quotes are stripped rather than escaped downstream")
    void quotesAreStripped() {
        assertThat(TicketAttachmentServiceImpl.safeFileName("say \"hello\".png"))
                .isEqualTo("say hello.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "..", "/", "\\", "\u0000", "\"\""})
    @DisplayName("a name with nothing usable left becomes 'attachment'")
    void anEmptyResultFallsBack(String submitted) {
        assertThat(TicketAttachmentServiceImpl.safeFileName(submitted)).isEqualTo("attachment");
    }

    @Test
    @DisplayName("a null filename becomes 'attachment', because MultipartFile permits one")
    void aNullNameFallsBack() {
        assertThat(TicketAttachmentServiceImpl.safeFileName(null)).isEqualTo("attachment");
    }

    /**
     * Truncated to the column width rather than allowed to fail the insert.
     *
     * <p>An upload refused by a 255-character database error, after the file has already been written,
     * is the worst of the available outcomes: the request fails and a blob is left behind.
     */
    @Test
    @DisplayName("an overlong name is truncated to the column width")
    void anOverlongNameIsTruncated() {
        String submitted = "a".repeat(300) + ".png";

        String safe = TicketAttachmentServiceImpl.safeFileName(submitted);

        assertThat(safe).hasSize(255);
        assertThat(safe).isEqualTo("a".repeat(255));
    }
}
