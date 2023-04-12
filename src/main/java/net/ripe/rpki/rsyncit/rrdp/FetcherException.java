package net.ripe.rpki.rsyncit.rrdp;

public class FetcherException extends RuntimeException {

    public FetcherException(final Throwable e) {
        super(e);
    }

    public FetcherException(final String cause) {
        super(cause);
    }
}
