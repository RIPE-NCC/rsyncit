package net.ripe.rpki.rsyncit.rrdp;

public class SnapshotStructureException extends RuntimeException {
    public SnapshotStructureException(String url, String msg) {
        super("Structure of snapshot at %s did not match expected structure: %s".formatted(url, msg));
    }
}
