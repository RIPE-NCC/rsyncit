package net.ripe.rpki.rsyncit.rrdp;

import java.net.URI;
import java.time.Instant;

public record RpkiObject(URI url, byte[] bytes, Instant modificationTime) {
}

