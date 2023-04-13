package net.ripe.rpki.rsyncit.rrdp;

import lombok.Getter;
import net.ripe.rpki.rsyncit.util.Sha256;

public class RpkiObject {
    @Getter
    private final String url;
    @Getter
    private final byte[] bytes;

    byte[] hash;

    public RpkiObject(String url, byte[] bytes) {
        this.url = url;
        this.bytes = bytes;
    }

    synchronized byte[] getHash() {
        if (hash == null) {
            hash = Sha256.asBytes(bytes);
        }
        return hash;
    }
}
