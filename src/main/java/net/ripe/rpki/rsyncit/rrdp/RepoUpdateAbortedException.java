package net.ripe.rpki.rsyncit.rrdp;

import java.net.URI;
import java.util.Map;

/**
 * Fetcher did not update repository for any non-fatal error reason.
 */
public class RepoUpdateAbortedException extends Exception{
    public static final String MESSAGE_TEMPLATE = "repository update aborted %s: %s";

    public RepoUpdateAbortedException(String url, String message) {
        super(MESSAGE_TEMPLATE.formatted(url, message));
    }

    public RepoUpdateAbortedException(String url, String message, Throwable cause) {
        super(MESSAGE_TEMPLATE.formatted(url, message), cause);
    }

    public RepoUpdateAbortedException(String url, Throwable cause) {
        super(MESSAGE_TEMPLATE.formatted(url, cause.getMessage()), cause);
    }

    public RepoUpdateAbortedException(URI uri, Throwable cause) {
        super(MESSAGE_TEMPLATE.formatted(uri, cause.getMessage()), cause);
    }
}
