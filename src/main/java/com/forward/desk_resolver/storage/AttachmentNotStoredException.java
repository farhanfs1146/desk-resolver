package com.forward.desk_resolver.storage;

/**
 * A row names a blob that is not readable.
 *
 * <p>This is the failure mode the database/filesystem split buys, and it exists as its own type so
 * that it is handled rather than discovered. A missing file is not a client error - the client asked
 * for something this application said it had - so it is a 500, but a 500 that says which attachment
 * and logs the key, instead of a {@code NoSuchFileException} from somewhere inside a stream copy.
 *
 * <p>It is never thrown for an attachment that does not exist: that is a 404 decided before storage is
 * consulted. Reaching this means the row is there and the bytes are not.
 */
public class AttachmentNotStoredException extends RuntimeException {

    public AttachmentNotStoredException(String message) {
        super(message);
    }

    public AttachmentNotStoredException(String message, Throwable cause) {
        super(message, cause);
    }
}
