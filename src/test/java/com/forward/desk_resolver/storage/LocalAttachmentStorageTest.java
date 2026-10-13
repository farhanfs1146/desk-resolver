package com.forward.desk_resolver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage layer on its own, without Spring or a database.
 *
 * <p>Everything here is about paths and filenames, which is where a file-upload feature goes wrong.
 * {@code TicketAttachmentIT} covers the same ground through the API; this covers the cases that cannot
 * be reached through it - a storage key that did not come from {@link LocalAttachmentStorage#store},
 * which is what an older row or a hand-edited one would be.
 */
class LocalAttachmentStorageTest {

    @TempDir
    Path tempDir;

    private LocalAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalAttachmentStorage(new AttachmentStorageProperties(
                tempDir.toString(), DataSize.ofMegabytes(1), null));
        storage.prepareRoot();
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "image/png",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- storing

    @Test
    @DisplayName("a stored file lands under <root>/<ticketId>/ with a generated name")
    void storeGeneratesTheKey() throws IOException {
        AttachmentStorage.StoredFile stored = storage.store(42L, file("payslip.png", "bytes"));

        assertThat(stored.storageKey()).startsWith("42/");
        assertThat(stored.storageKey()).endsWith(".png");
        assertThat(stored.sizeBytes()).isEqualTo(5);

        Path written = tempDir.resolve(stored.storageKey());
        assertThat(written).exists();
        assertThat(Files.readString(written)).isEqualTo("bytes");
    }

    /**
     * The client's filename contributes nothing to the key except a validated extension.
     *
     * <p>Which is the reason traversal is not possible here: there is no concatenation of a submitted
     * name into a path to be escaped from.
     */
    @Test
    @DisplayName("the client's filename does not appear in the key")
    void theClientNameIsNotPartOfTheKey() {
        AttachmentStorage.StoredFile stored = storage.store(1L, file("my-secret-report.png", "x"));

        assertThat(stored.storageKey()).doesNotContain("my-secret-report");
    }

    @Test
    @DisplayName("two uploads of the same filename are two distinct files")
    void keysAreUnique() throws IOException {
        AttachmentStorage.StoredFile first = storage.store(7L, file("same.png", "one"));
        AttachmentStorage.StoredFile second = storage.store(7L, file("same.png", "two"));

        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
        assertThat(Files.readString(tempDir.resolve(first.storageKey()))).isEqualTo("one");
        assertThat(Files.readString(tempDir.resolve(second.storageKey()))).isEqualTo("two");
    }

    // ---------------------------------------------------------------- loading

    @Test
    @DisplayName("a stored file can be read back")
    void loadReturnsTheBytes() throws IOException {
        AttachmentStorage.StoredFile stored = storage.store(3L, file("note.txt", "hello"));

        Resource loaded = storage.load(stored.storageKey());

        assertThat(loaded.exists()).isTrue();
        assertThat(loaded.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    @DisplayName("a key whose file is gone is an AttachmentNotStoredException, not an IO error")
    void loadReportsAMissingFile() throws IOException {
        AttachmentStorage.StoredFile stored = storage.store(3L, file("note.txt", "hello"));
        Files.delete(tempDir.resolve(stored.storageKey()));

        assertThatThrownBy(() -> storage.load(stored.storageKey()))
                .isInstanceOf(AttachmentNotStoredException.class)
                .hasMessageContaining("missing or unreadable");
    }

    /**
     * The second line of defence, and the only one these inputs can reach.
     *
     * <p>A key like this cannot be produced by {@link LocalAttachmentStorage#store}, so it can only
     * come from a row written by something else. {@code normalize()} collapses the {@code ..} segments
     * before the root comparison, so the escape is detected rather than being compared as a string
     * that merely starts with the right prefix.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "../outside.png",
            "1/../../outside.png",
            "../../../../../../etc/passwd",
            "1/./../../outside.png"
    })
    @DisplayName("a key that escapes the storage root is refused")
    void loadRefusesEscapingKeys(String key) {
        assertThatThrownBy(() -> storage.load(key))
                .isInstanceOf(AttachmentNotStoredException.class)
                .hasMessageContaining("does not resolve inside the storage directory");
    }

    @Test
    @DisplayName("an absolute key cannot be used to read an arbitrary file")
    void loadRefusesAnAbsolutePath() throws IOException {
        // A real file outside the root, to make the attempt a meaningful one.
        Path outside = tempDir.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        try {
            assertThatThrownBy(() -> storage.load(outside.toAbsolutePath().toString()))
                    .isInstanceOf(AttachmentNotStoredException.class);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank key is refused rather than resolving to the root directory")
    void loadRefusesABlankKey(String key) {
        assertThatThrownBy(() -> storage.load(key))
                .isInstanceOf(AttachmentNotStoredException.class)
                .hasMessageContaining("no storage key");
    }

    @Test
    @DisplayName("a key naming a directory is refused, not returned as a resource")
    void loadRefusesADirectory() {
        storage.store(9L, file("a.png", "x"));

        assertThatThrownBy(() -> storage.load("9"))
                .isInstanceOf(AttachmentNotStoredException.class);
    }

    // ---------------------------------------------------------------- deletion

    @Test
    @DisplayName("deleteQuietly removes a stored file and tolerates a second call")
    void deleteQuietlyIsIdempotent() {
        AttachmentStorage.StoredFile stored = storage.store(5L, file("a.png", "x"));

        storage.deleteQuietly(stored.storageKey());
        assertThat(tempDir.resolve(stored.storageKey())).doesNotExist();

        // Called while handling another failure, so it must never throw one of its own.
        storage.deleteQuietly(stored.storageKey());
    }

    // ---------------------------------------------------------------- extensions

    @ParameterizedTest
    @CsvSource({
            "payslip.png,            .png",
            "report.PDF,             .pdf",
            "archive.tar.gz,         .gz",
            "data.csv,               .csv",
    })
    @DisplayName("a plain alphanumeric extension is kept, lower-cased")
    void extensionsAreKept(String filename, String expected) {
        assertThat(LocalAttachmentStorage.extensionOf(filename.trim())).isEqualTo(expected.trim());
    }

    /**
     * Anything that is not a short run of letters and digits is dropped entirely.
     *
     * <p>Stated as one rule rather than as a list of blocked patterns, which is why these cases pass
     * without having been enumerated in the implementation: a separator, a trailing dot, a null byte
     * and an over-long tail all fail the same check.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "noextension",
            "trailing.",
            "weird.p/ng",
            "weird.p\\ng",
            "spaced.p ng",
            "nul.pn\u0000g",
            "dotfile.a-b",
            "long.verylongextension",
            ".",
            "..",
            ".hidden"
    })
    @DisplayName("a suspicious or absent extension yields nothing rather than being sanitised")
    void suspiciousExtensionsAreDropped(String filename) {
        assertThat(LocalAttachmentStorage.extensionOf(filename)).isEmpty();
    }

    @Test
    @DisplayName("a null filename is handled, because MultipartFile permits one")
    void aNullFilenameIsHandled() {
        assertThat(LocalAttachmentStorage.extensionOf(null)).isEmpty();
    }

    // ---------------------------------------------------------------- startup

    @Test
    @DisplayName("the root is created at startup if it does not exist")
    void theRootIsCreated() {
        Path nested = tempDir.resolve("deeply/nested/attachments");
        LocalAttachmentStorage fresh = new LocalAttachmentStorage(
                new AttachmentStorageProperties(nested.toString(), null, null));

        fresh.prepareRoot();

        assertThat(nested).isDirectory();
        assertThat(fresh.root()).isEqualTo(nested.toAbsolutePath().normalize());
    }

    // ---------------------------------------------------------------- content types

    @Test
    @DisplayName("the content-type allow-list ignores parameters and case")
    void contentTypeMatchingIsLenientAboutFormatting() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties(
                tempDir.toString(), null, List.of("text/plain", "IMAGE/PNG"));

        assertThat(properties.isAllowed("text/plain")).isTrue();
        assertThat(properties.isAllowed("text/plain; charset=utf-8")).isTrue();
        assertThat(properties.isAllowed("TEXT/PLAIN")).isTrue();
        assertThat(properties.isAllowed("image/png")).isTrue();
    }

    @Test
    @DisplayName("an absent or unlisted content type is refused, because the list is an allow-list")
    void contentTypeMatchingFailsClosed() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties(
                tempDir.toString(), null, List.of("text/plain"));

        assertThat(properties.isAllowed(null)).isFalse();
        assertThat(properties.isAllowed("")).isFalse();
        assertThat(properties.isAllowed("application/x-msdownload")).isFalse();
        // Not a prefix or wildcard match: "text/plain" does not admit the whole text/ family.
        assertThat(properties.isAllowed("text/html")).isFalse();
    }
}
