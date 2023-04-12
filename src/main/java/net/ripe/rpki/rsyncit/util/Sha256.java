package net.ripe.rpki.rsyncit.util;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

public class Sha256 {
    public static String asString(byte[] bytes) {
        return calculateHash(bytes).toString().toLowerCase();
    }

    public static String asString(String content) {
        return asString(content.getBytes());
    }

    public static byte[] asBytes(byte[] bytes) {
        return calculateHash(bytes).asBytes();
    }

    private static HashCode calculateHash(byte[] bytes) {
        return Hashing.sha256().hashBytes(bytes);
    }
}
