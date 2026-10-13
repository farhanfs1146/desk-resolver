package com.forward.desk_resolver.service;

import com.forward.desk_resolver.entity.Ticket;
import com.forward.desk_resolver.entity.User;
import com.forward.desk_resolver.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket attachments: upload, list, download, and the three ways an upload is refused.
 *
 * <p>The ground this covers that the comment suite does not is the <strong>second store</strong>. An
 * attachment is a database row and a file on a disk, written by two systems that share no transaction,
 * and most of what can go wrong here is about the relationship between them: a row whose bytes are
 * missing, a blob whose row never committed, a download that serves one ticket's file under another
 * ticket's path.
 */
class TicketAttachmentIT extends AbstractPostgresIT {

    private static final byte[] PNG_BYTES = "fake-png-content".getBytes(StandardCharsets.UTF_8);

    private MockMultipartHttpServletRequestBuilder upload(long ticketId, MockMultipartFile file) {
        return (MockMultipartHttpServletRequestBuilder)
                multipart("/api/tickets/" + ticketId + "/attachments").file(file);
    }

    private static MockMultipartFile png(String filename) {
        return new MockMultipartFile("file", filename, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
    }

    /**
     * Attaches a file through the API and returns its id.
     *
     * <p>Note the {@code header} call rather than {@code authenticated(...)}: that helper also sets a
     * JSON content type, which would override the multipart boundary the builder generated.
     */
    private long attach(long ticketId, String token, MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(upload(ticketId, file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result, "$.id")).longValue();
    }

    private List<Path> storedFiles() throws Exception {
        Path root = Path.of(attachmentStorageProperties.directory());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    // ---------------------------------------------------------------- uploading

    @Test
    @DisplayName("an upload returns its metadata and writes exactly one file")
    void uploadStoresTheFileAndTheRow() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        MvcResult result = mockMvc.perform(upload(ticket.getId(), png("payslip-march.png"))
                        .header("Authorization", "Bearer " + tokenFor(raiser)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("payslip-march.png"))
                .andExpect(jsonPath("$.fileType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(PNG_BYTES.length))
                // From the token, not from the request.
                .andExpect(jsonPath("$.uploadedBy").value(raiser.getFullName()))
                .andReturn();

        assertThat(this.<Integer>read(result, "$.id")).isNotNull();
        assertThat(storedFiles()).hasSize(1);
        assertThat(ticketAttachmentRepository.count()).isEqualTo(1);
    }

    /**
     * The stored path is generated, and the client's filename does not appear in it.
     *
     * <p>This is the traversal defence stated as an observable property rather than as a filter. The
     * name below is a traversal attempt and the file still lands inside the storage root under a
     * generated name - because the submitted name is never part of a path, so there was nothing to
     * escape with.
     */
    @Test
    @DisplayName("a traversal filename cannot steer the write, and is not used as the path")
    void theStoredPathIsGenerated() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        Path root = Path.of(attachmentStorageProperties.directory()).toAbsolutePath().normalize();

        MvcResult result = mockMvc.perform(
                        upload(ticket.getId(), png("../../../../etc/passwd.png"))
                                .header("Authorization", "Bearer " + tokenFor(raiser)))
                .andExpect(status().isCreated())
                // The name is kept for display, reduced to its last segment - so a reader is not shown
                // something that looks like a location.
                .andExpect(jsonPath("$.fileName").value("passwd.png"))
                .andReturn();

        List<Path> files = storedFiles();
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().toAbsolutePath().normalize()).startsWith(root);
        // Grouped by ticket, named by a generated id: nothing of the client's name survives in the path.
        assertThat(files.getFirst().getParent().getFileName().toString())
                .isEqualTo(String.valueOf(ticket.getId()));
        assertThat(files.getFirst().getFileName().toString()).doesNotContain("passwd");
        assertThat(files.getFirst().getFileName().toString()).endsWith(".png");

        assertThat(this.<Integer>read(result, "$.id")).isNotNull();
    }

    @Test
    @DisplayName("a content type outside the allow-list is a 400 that lists the allowed types")
    void aDisallowedContentTypeIsRejected() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        MockMultipartFile executable = new MockMultipartFile(
                "file", "payload.exe", "application/x-msdownload", PNG_BYTES);

        mockMvc.perform(upload(ticket.getId(), executable)
                        .header("Authorization", "Bearer " + tokenFor(raiser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Upload rejected"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("is not accepted")));

        // Refused before anything was written: validation precedes the store.
        assertThat(storedFiles()).isEmpty();
        assertThat(ticketAttachmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("an empty file is a 400, which is also what an unselected file picker sends")
    void anEmptyFileIsRejected() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        MockMultipartFile empty = new MockMultipartFile(
                "file", "nothing.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);

        mockMvc.perform(upload(ticket.getId(), empty)
                        .header("Authorization", "Bearer " + tokenFor(raiser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("empty")));

        assertThat(storedFiles()).isEmpty();
    }

    /** A part under the wrong field name is a 400 naming the part, not a 500. */
    @Test
    @DisplayName("a multipart request without a 'file' part is a 400 naming it")
    void aMissingPartIsRejected() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        MockMultipartFile wrongName = new MockMultipartFile(
                "attachment", "payslip.png", MediaType.IMAGE_PNG_VALUE, PNG_BYTES);

        mockMvc.perform(multipart("/api/tickets/" + ticket.getId() + "/attachments").file(wrongName)
                        .header("Authorization", "Bearer " + tokenFor(raiser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("file")));
    }

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("attachments list newest first with pagination headers")
    void attachmentsListNewestFirst() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);

        attach(ticket.getId(), token, png("first.png"));
        attach(ticket.getId(), token, png("second.png"));

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/attachments"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$[0].fileName").value("second.png"));
    }

    /**
     * The list never exposes the storage key.
     *
     * <p>It is internal, and a client that could see it would be one request away from trying to supply
     * one. Downloads go by id, through an endpoint that re-checks the ticket.
     */
    @Test
    @DisplayName("the storage key is not in the response")
    void theStorageKeyIsNotExposed() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);
        attach(ticket.getId(), token, png("payslip.png"));

        MvcResult result = mockMvc.perform(
                        authenticated(get("/api/tickets/" + ticket.getId() + "/attachments"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].filePath").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andReturn();

        // Belt and braces: the generated key contains the ticket id and a UUID, and neither should
        // appear anywhere in the body under any field name.
        String body = result.getResponse().getContentAsString();
        String storedKey = ticketAttachmentRepository.findAll().getFirst().getFilePath();
        assertThat(body).doesNotContain(storedKey);
    }

    // ---------------------------------------------------------------- downloading

    @Test
    @DisplayName("a download returns the bytes as an attachment, never inline")
    void downloadServesTheBytes() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);
        long attachmentId = attach(ticket.getId(), token, png("payslip-march.png"));

        MvcResult result = mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments/" + attachmentId + "/content"),
                        token))
                .andExpect(status().isOk())
                // "attachment", not "inline". The stored content type is client-declared and nothing
                // sniffed the bytes, so letting a browser render it would be stored XSS via a file
                // picker.
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("payslip-march.png")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);
    }

    /**
     * An attachment is only reachable through the ticket it belongs to.
     *
     * <p>Without this, any ticket the caller can see would be a key to every attachment in the system:
     * the visibility check would pass on their own ticket and the attachment id would then be resolved
     * on its own. The repository matches both ids, so the mismatched pair is simply not found.
     */
    @Test
    @DisplayName("an attachment cannot be fetched through a different ticket's path")
    void anAttachmentIsBoundToItsTicket() throws Exception {
        User owner = givenUser("EMPLOYEE");
        User other = givenUser("EMPLOYEE");
        Ticket theirs = givenTicket(owner, null, givenApplication());
        Ticket mine = givenTicket(other, null, givenApplication());

        long attachmentId = attach(theirs.getId(), tokenFor(owner), png("private.png"));

        // The caller can see `mine` perfectly well; the attachment is not part of it.
        mockMvc.perform(authenticated(
                        get("/api/tickets/" + mine.getId() + "/attachments/" + attachmentId + "/content"),
                        tokenFor(other)))
                .andExpect(status().isNotFound());
    }

    /**
     * A row whose file has gone is a 500 that says so, not a stack trace from inside a stream copy.
     *
     * <p>This is the inconsistency the database/filesystem split permits, simulated by deleting the
     * blob - which is exactly what a restored database backup or a cleared volume produces. It is worth
     * a test because the alternative is discovering the behaviour in production: the handler turns it
     * into a problem document and logs the key, so there is something to act on.
     */
    @Test
    @DisplayName("an attachment whose file is missing fails as an unavailable attachment")
    void aMissingBlobIsReported() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        String token = tokenFor(raiser);
        long attachmentId = attach(ticket.getId(), token, png("payslip.png"));

        for (Path file : storedFiles()) {
            Files.delete(file);
        }

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments/" + attachmentId + "/content"),
                        token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Attachment unavailable"));

        // The row is untouched - this is a storage problem, not a reason to lose the record.
        assertThat(ticketAttachmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown attachment id is a 404")
    void anUnknownAttachmentIsNotFound() throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments/999999/content"),
                        tokenFor(raiser)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- visibility

    @Test
    @DisplayName("an uninvolved user can neither list nor upload, and gets 404 either way")
    void anUninvolvedUserIsRefused() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());
        String outsiderToken = tokenFor(givenUser("EMPLOYEE"));

        mockMvc.perform(authenticated(get("/api/tickets/" + ticket.getId() + "/attachments"), outsiderToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(upload(ticket.getId(), png("sneaky.png"))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());

        // Refused before the file was looked at, so nothing was written either.
        assertThat(storedFiles()).isEmpty();
        assertThat(ticketAttachmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("an uninvolved user cannot download somebody else's attachment")
    void anUninvolvedUserCannotDownload() throws Exception {
        User owner = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(owner, null, givenApplication());
        long attachmentId = attach(ticket.getId(), tokenFor(owner), png("private.png"));

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments/" + attachmentId + "/content"),
                        tokenFor(givenUser("EMPLOYEE"))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IT_SUPPORT", "DEVELOPER", "ADMIN"})
    @DisplayName("holders of TICKET_READ_ALL reach attachments on any ticket")
    void supportStaffReachEveryAttachment(String role) throws Exception {
        User raiser = givenUser("EMPLOYEE");
        Ticket ticket = givenTicket(raiser, null, givenApplication());
        long attachmentId = attach(ticket.getId(), tokenFor(raiser), png("payslip.png"));
        String token = tokenFor(givenUser(role));

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments/" + attachmentId + "/content"),
                        token))
                .andExpect(status().isOk());
    }

    /** The assignee is involved, so they can attach a fix without TICKET_READ_ALL. */
    @Test
    @DisplayName("the assignee can upload to a ticket assigned to them")
    void theAssigneeCanUpload() throws Exception {
        User assignee = givenUser("DEVELOPER");
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), assignee, givenApplication());

        attach(ticket.getId(), tokenFor(assignee), png("patch-notes.png"));

        assertThat(ticketAttachmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a user with no roles is refused the endpoint itself")
    void aRolelessUserIsForbidden() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());

        mockMvc.perform(authenticated(
                        get("/api/tickets/" + ticket.getId() + "/attachments"), tokenFor(givenUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("attachments need a token like everything else")
    void anonymousAccessIsRefused() throws Exception {
        Ticket ticket = givenTicket(givenUser("EMPLOYEE"), null, givenApplication());

        mockMvc.perform(get("/api/tickets/" + ticket.getId() + "/attachments"))
                .andExpect(status().isUnauthorized());
    }
}
