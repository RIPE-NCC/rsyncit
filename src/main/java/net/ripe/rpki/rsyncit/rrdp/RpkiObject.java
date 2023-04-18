package net.ripe.rpki.rsyncit.rrdp;

import lombok.Value;

import java.net.URI;
import java.time.Instant;

@Value
public class RpkiObject {
    URI url;
    byte[] bytes;
    Instant createdAt;
}
