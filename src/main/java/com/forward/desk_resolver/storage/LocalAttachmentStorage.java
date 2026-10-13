package com.forward.desk_resolver.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores attachments in a directory on the local filesystem.
 *
 * <h2>The only name this class will resolve is one it generated</h2>
 *
 * Path traversal is the defining risk of a file-upload feature, and the defence here is structural
 * rather than a filter. A client-supplied filename is <em>never</em> part of a path:
 * {@link #store} discards it and generates {@code <ticketId>/<uuid><ext>}, and the extension it keeps
 * is validated character by character rather than trusted. So there is no input shaped like a path to
 * sanitise, which is the version of this defence that cannot be bypassed by an encoding nobody
 * considered.
 *
 * <p>{@link #load} then re-checks anyway, in {@link #resolveWithinRoot}. That is not redundant: keys
 * come from the database rather than from the request, so the check is about a row having been written
 * by something other than {@link #store} - an older import, a hand-edited row, a future second writer.
 * Defence in depth where the cost is a string comparison.
 *
 * <h2>Why writes are not atomic with the database</h2>
 *
 * They cannot be: no transaction spans PostgreSQL and a filesystem. The order is chosen so that the
 * surviving inconsistency is the harmless one - the file is written first and the row second, so a
 * failure between them leaves an unreferenced blob rather than a row pointing at nothing. An orphaned
 * blob wastes disk and is invisible; a dangling row is an attachment a user can see and cannot
 * download. {@code TicketAttachmentServiceImpl} completes that story by deleting the blob if the
 * insert fails.
 *
 * <h2>What this is not suitable for</h2>
 *
 * More than one instance. A file written by one process is not visible to another unless the directory
 * is shared storage, so a horizontally scaled deployment needs an {@link AttachmentStorage} backed by
 * object storage. That is why the interface exists; see {@code docs/DECISIONS.md}.
 */
@Component
public class LocalAttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStorage.class);

    /**
     * Longest extension this will carry over from a client filename.
     *
     * <p>Generous enough for the real ones and short enough that "the part after the last dot" cannot
     * become a filename in its own right.
     */
    private static final int MAX_EXTENSION_LENGTH = 12;

    private final AttachmentStorageProperties properties;
    private Path root;

    public LocalAttachmentStorage(AttachmentStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves and creates the storage root once, at startup.
     *
     * <p>At startup rather than on first upload, so a directory that cannot be created is a boot
     * failure naming the path - not a 500 on the first attachment somebody tries to add, weeks later.
     * The same argument as the JWT secret: configuration mistakes should surface where they are made.
     */
    @PostConstruct
    void prepareRoot() {
        Path configured = Path.of(properties.directory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(configured);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not create the attachment directory '" + configured
                            + "'. Set app.storage.attachments.directory to a writable path.", e);
        }
        if (!Files.isWritable(configured)) {
            throw new IllegalStateException(
                    "The attachment directory '" + configured + "' is not writable.");
        }
        this.root = configured;
        log.info("Attachments are stored under {} (max {} per file)",
                root, properties.maxFileSize());
    }

    @Override
    public StoredFile store(Long ticketId, MultipartFile file) {
        String key = ticketId + "/" + UUID.randomUUID() + extensionOf(file.getOriginalFilename());
        Path target = resolveWithinRoot(key);

        try {
            Files.createDirectories(target.getParent());
            // REPLACE_EXISTING is absent deliberately: the key contains a fresh UUID, so an existing
            // file at this path is a collision that should fail loudly rather than overwrite somebody
            // else's attachment.
            try (InputStream in = file.getInputStream()) {
                long written = Files.copy(in, target);
                log.debug("Stored attachment {} ({} bytes) for ticket {}", key, written, ticketId);
                return new StoredFile(key, written);
            }
        } catch (IOException e) {
            // Best effort: a partially written file must not be left behind to be served later.
            deleteQuietly(target);
            throw new AttachmentNotStoredException(
                    "Could not write the attachment for ticket " + ticketId, e);
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path file = resolveWithinRoot(storageKey);
        if (!Files.isReadable(file) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new AttachmentNotStoredException(
                    "The stored file for key '" + storageKey + "' is missing or unreadable");
        }
        return new PathResource(file);
    }

    /** Deletes a stored blob, if it is still there. Used to undo a write whose row did not commit. */
    public void deleteQuietly(String storageKey) {
        deleteQuietly(resolveWithinRoot(storageKey));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Logged and swallowed: this runs while handling another failure, and masking that failure
            // with this one would hide the thing worth reporting. The leftover is an unreferenced blob.
            log.warn("Could not remove the attachment file {}; it is now unreferenced", file, e);
        }
    }

    /**
     * Resolves a key against the root and refuses anything that escapes it.
     *
     * <p>{@code normalize()} collapses {@code ..} segments <em>before</em> the comparison, so a key of
     * {@code 1/../../etc/passwd} resolves to a path outside the root and is rejected rather than
     * compared as a string that happens to start with the root. {@code startsWith} is the {@code Path}
     * overload - a component-wise prefix check - not {@code String.startsWith}, which would accept a
     * sibling directory whose name merely begins with the root's.
     */
    private Path resolveWithinRoot(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new AttachmentNotStoredException("The attachment has no storage key");
        }
        Path resolved = root.resolve(storageKey).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            log.error("Refused an attachment key that escapes the storage root: '{}'", storageKey);
            throw new AttachmentNotStoredException(
                    "The attachment key does not resolve inside the storage directory");
        }
        return resolved;
    }

    /**
     * The extension to carry over from a client filename, or an empty string.
     *
     * <p>Kept so a downloaded file still opens in the right application, and validated so that keeping
     * it cannot matter. Anything that is not a short run of ASCII letters and digits after the last dot
     * is dropped entirely - which covers a second extension, a separator, a null byte, and the
     * {@code .} / {@code ..} cases, without enumerating them.
     *
     * <p>This has no bearing on safety: the extension lands in a filename this class generated inside
     * the storage root, and the download endpoint serves every attachment as a download whatever it is
     * called.
     */
    static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        // dot <= 0 covers three cases at once: no dot at all, a name that is only a dot or dots, and a
        // leading-dot name like ".hidden" - where the text after the dot is the whole filename rather
        // than an extension, so carrying it over would be meaningless.
        if (dot <= 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String extension = originalFilename.substring(dot + 1);
        if (extension.length() > MAX_EXTENSION_LENGTH) {
            return "";
        }
        for (int index = 0; index < extension.length(); index++) {
            char character = extension.charAt(index);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
            if (!alphanumeric) {
                return "";
            }
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    /** The resolved root, for tests and for the startup log line. */
    Path root() {
        return root;
    }
}
