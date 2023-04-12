package net.ripe.rpki.rsyncit.rrdp;

import lombok.AllArgsConstructor;
import lombok.Value;

@AllArgsConstructor
@Value
public class RpkiObject {
    byte[] bytes;
}
