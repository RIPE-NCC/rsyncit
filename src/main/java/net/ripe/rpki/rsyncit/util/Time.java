package net.ripe.rpki.rsyncit.util;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.function.Supplier;

public class Time {
    @Value
    @AllArgsConstructor
    public static class Timed<T> {
        T result;
        long time;
    }

    public static <T> Timed<T> timed(Supplier<T> s) {
        long begin = System.nanoTime();
        T t = s.get();
        long end = System.nanoTime();
        return new Timed<>(t, ((end - begin) + 500_000) / 1_000_000);
    }

    public static long timed(Runnable s) {
        return timed(() -> {
            s.run();
            return null;
        }).getTime();
    }
}