package net.ripe.rpki.rsyncit.rrdp;

public class SnapshotNotModifiedException extends Exception {
    public SnapshotNotModifiedException(String uri) {
        super(String.format("Snapshot uri in notification.xml (%s) is identical to previous update", uri));
    }
}
