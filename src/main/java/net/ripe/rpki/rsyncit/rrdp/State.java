package net.ripe.rpki.rsyncit.rrdp;

import lombok.Value;

import java.util.Map;

@Value
public class State {
    Map<String, RpkiObject> objects;
}
