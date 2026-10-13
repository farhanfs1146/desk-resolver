package com.forward.desk_resolver.common.exception;

/**
 * Thrown when an uploaded file is refused on its own merits - empty, too large, or a content type
 * that is not on the allow-list.
 *
 * <p>Distinguished from {@link InvalidReferenceException}, which is also a 400, because the two carry
 * different advice to the caller: an invalid reference means a value in the payload names something
 * that does not exist, while this means the file itself is not acceptable. A client showing an upload
 * form needs the second message next to the file picker, so the message is written to be shown - it
 * names the limit or the allowed types rather than saying only that something was wrong.
 *
 * <p>Note what is <em>not</em> this exception. A file whose bytes could not be written is an
 * {@code AttachmentNotStoredException} and a 500: the request was fine and this application failed.
 */
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}
